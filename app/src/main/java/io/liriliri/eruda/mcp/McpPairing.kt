package io.liriliri.eruda.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.liriliri.eruda.App
import io.liriliri.eruda.R

/**
 * Coordenador do popup de pairing: garante UMA apresentação confiável por
 * pedido, escolhida por prioridade:
 *
 *   1. app em foreground  → OAuthHandlerActivity (dialog nativo);
 *   2. app atrás, com permissão de overlay → McpOverlay (sobre outros apps);
 *   3. fallback → notificação heads-up com ações Approve/Deny.
 *
 * Aprovar tenta ENTREGAR o código ao cliente na hora (GET no redirect_uri
 * via OkHttp). Cliente no próprio aparelho (Termux/chroot) conclui o fluxo
 * sem navegador. Cliente noutra máquina: o callback é inalcançável, o
 * pedido CONTINUA pendente e o usuário é orientado a aprovar na janela do
 * navegador que o cliente abriu (onde o 302 é entregue de verdade).
 */
object McpPairing {

    private const val CHANNEL = "mcp_pairing_v2"
    private const val NOTIF_ID = 42
    private const val RESULT_NOTIF_ID = 43
    private const val EXPIRE_CHECK_MS = 11 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())

    private data class Presentation(
        val id: String,
        val code2: Int,
        val clientId: String,
        val clientName: String
    )

    /** Pedido em exibição (qualquer superfície). @Volatile: lido de threads do servidor. */
    @Volatile private var shown: Presentation? = null

    /** Como fechar o dialog aberto (OAuthHandlerActivity), quando houver. */
    @Volatile private var dialogDismiss: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // Apresentação
    // ------------------------------------------------------------------

    /** Chamado pelo /oauth/authorize a cada GET: mostra o pedido do jeito mais confiável. */
    fun present(context: Context, id: String, code2: Int, clientId: String, clientName: String) {
        val ctx = context.applicationContext
        val p = Presentation(id, code2, clientId, clientName)
        main.post {
            try {
                if (shown?.id == id) return@post // reload/retry do mesmo pedido: não duplica
                val previous = shown
                hideSurface()
                if (previous != null && previous.id != id) {
                    cancelNotification(ctx, previous.clientId)
                }
                shown = p
                val activity = App.activity()
                when {
                    activity != null -> showDialog(ctx, p)
                    McpOverlay.canShow(ctx) -> McpOverlay.show(ctx, id, code2, clientName)
                    else -> showNotification(ctx, p)
                }
                main.postDelayed({ expireCheck(id) }, EXPIRE_CHECK_MS)
            } catch (e: Exception) {
                McpServerManager.log("pairing present failed: ${e.message}")
            }
        }
    }

    private fun showDialog(ctx: Context, p: Presentation) {
        try {
            cancelNotification(ctx, p.clientId)
            ctx.startActivity(OAuthHandlerActivity.requestIntent(ctx, p.id))
            McpServerManager.log("Pairing dialog shown (code ${p.code2})")
        } catch (e: Exception) {
            // Raro (app morrendo): degrada para as outras superfícies.
            McpServerManager.log("pairing dialog failed: ${e.message}")
            if (McpOverlay.canShow(ctx)) McpOverlay.show(ctx, p.id, p.code2, p.clientName)
            else showNotification(ctx, p)
        }
    }

    private fun expireCheck(id: String) {
        if (shown?.id != id) return
        if (McpOAuth.getPending(id) != null) return
        dismiss(id) // expirou sem decisão: some com tudo
    }

    // ------------------------------------------------------------------
    // Resolução (chamada por notificação, overlay, dialog ou browser)
    // ------------------------------------------------------------------

    /** Aprovar: entrega o código ao cliente (pode bloquear; rode fora da UI). */
    fun completeApprove(context: Context, id: String) {
        val ctx = context.applicationContext
        when (McpOAuth.approveAndDeliver(id)) {
            McpOAuth.ApproveOutcome.DELIVERED -> {
                main.post { notifyResult(ctx, ctx.getString(R.string.mcp_approved)) }
            }
            McpOAuth.ApproveOutcome.UNREACHABLE -> {
                main.post {
                    // Pedido continua pendente: mantém uma notificação para não perder o fio.
                    shown?.takeIf { it.id == id }?.let { showNotification(ctx, it) }
                    notifyResult(ctx, ctx.getString(R.string.mcp_pair_unreachable))
                }
            }
            McpOAuth.ApproveOutcome.EXPIRED -> {
                main.post { notifyResult(ctx, ctx.getString(R.string.mcp_oauth_expired)) }
            }
        }
    }

    /** Negar (imediato, server-side). */
    fun deny(context: Context, id: String) {
        val ctx = context.applicationContext
        if (McpOAuth.decideDirect(id, false)) {
            notifyResult(ctx, ctx.getString(R.string.mcp_pair_denied))
        }
    }

    /** Overlay/dialog: botão Approve (roda a entrega numa thread de trabalho). */
    fun approveClicked(id: String) {
        val ctx = App.instance ?: return
        Thread { completeApprove(ctx, id) }.start()
    }

    /** Overlay/dialog: botão Deny. */
    fun denyClicked(id: String) {
        App.instance?.let { deny(it, id) }
    }

    /** X do overlay / cancelar do dialog: some com o popup e deixa a notificação. */
    fun dismissClicked(id: String) {
        val ctx = App.instance ?: return
        main.post { dismissSurface(ctx, id) }
    }

    /** Resolução externa (browser, expiração): fecha tudo que estiver aberto. */
    fun dismiss(id: String) {
        main.post {
            val p = shown ?: return@post
            if (p.id != id) return@post
            hideSurface()
            cancelNotification(App.instance ?: return@post, p.clientId)
            shown = null
        }
    }

    /** Trocou de superfície mantendo o pedido vivo (o `shown` não muda). */
    private fun dismissSurface(ctx: Context, id: String) {
        val p = shown ?: return
        if (p.id != id) return
        hideSurface()
        showNotification(ctx, p)
        McpServerManager.log("Pairing popup dismissed, notification kept (code ${p.code2})")
    }

    /** Esconde overlay + dialog SEM mexer em `shown` nem na notificação. */
    private fun hideSurface() {
        McpOverlay.hide()
        val f = dialogDismiss
        dialogDismiss = null
        if (f != null) main.post { f() }
    }

    // ------------------------------------------------------------------
    // Dialog nativo (OAuthHandlerActivity)
    // ------------------------------------------------------------------

    fun registerDialog(id: String, dismiss: () -> Unit) {
        dialogDismiss = dismiss
        if (McpOAuth.getPending(id) == null) dismiss()
    }

    fun unregisterDialog(id: String) {
        if (shown?.id == id) dialogDismiss = null
    }

    // ------------------------------------------------------------------
    // Notificações
    // ------------------------------------------------------------------

    private fun showNotification(ctx: Context, p: Presentation) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(ctx, nm)
            val flags = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
            val openPi = PendingIntent.getActivity(
                ctx, p.id.hashCode(), OAuthHandlerActivity.requestIntent(ctx, p.id), flags
            )
            val approvePi = PendingIntent.getService(
                ctx, p.id.hashCode() + 7,
                McpService.pairActionIntent(ctx, McpService.ACTION_PAIR_APPROVE, p.id), flags
            )
            val denyPi = PendingIntent.getService(
                ctx, p.id.hashCode() + 13,
                McpService.pairActionIntent(ctx, McpService.ACTION_PAIR_DENY, p.id), flags
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_mcp_status)
                .setContentTitle(ctx.getString(R.string.mcp_pair_title))
                .setContentText(ctx.getString(R.string.mcp_pair_text, p.code2, p.clientName))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        ctx.getString(R.string.mcp_pair_big, p.code2, p.clientName)
                    )
                )
                .setContentIntent(openPi)
                .addAction(0, ctx.getString(R.string.mcp_approve), approvePi)
                .addAction(0, ctx.getString(R.string.mcp_deny), denyPi)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            // Tag estável por cliente: re-notificar ATUALIZA em vez de duplicar.
            nm.notify("pair:${p.clientId}", NOTIF_ID, notif)
            McpServerManager.log("Pairing notification shown (code ${p.code2})")
        } catch (e: Exception) {
            McpServerManager.log("pairing notify failed: ${e.message}")
        }
    }

    /** Resultado da decisão (some sozinho; toast quando o app está na frente). */
    private fun notifyResult(ctx: Context, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(ctx, nm)
            val notif = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_mcp_status)
                .setContentTitle(ctx.getString(R.string.mcp_pair_title))
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(RESULT_NOTIF_ID, notif)
            main.postDelayed({
                try {
                    nm.cancel(RESULT_NOTIF_ID)
                } catch (e: Exception) {
                    // ignore
                }
            }, 6000)
            if (App.foreground()) Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun cancelNotification(ctx: Context, clientId: String) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel("pair:$clientId", NOTIF_ID)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun ensureChannel(ctx: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Remove o canal antigo (sem vibração) de installs anteriores.
        try {
            nm.deleteNotificationChannel("mcp_pairing")
        } catch (e: Exception) {
            // ignore
        }
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    ctx.getString(R.string.mcp_pair_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 250, 250)
                }
            )
        }
    }
}
