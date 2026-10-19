package io.liriliri.eruda.mcp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.liriliri.eruda.R

/**
 * Dialog nativo de aprovação do pairing (uma popup só).
 *
 * Aberto quando: (a) o pedido chega com o app na frente (McpPairing), (b)
 * o usuário toca a notificação / a linha amarela da tela de config, ou
 * (c) o sistema entrega um link /oauth/ (ex.: termux-open).
 *
 * Aprovar entrega o código na hora via McpOAuth.approveAndDeliver (GET no
 * redirect_uri). Cliente no próprio aparelho conclui aqui; cliente noutra
 * máquina cai no aviso para aprovar no navegador que o cliente abriu.
 */
class OAuthHandlerActivity : AppCompatActivity() {

    private var dialog: AlertDialog? = null
    private var pendingId: String? = null
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        McpOAuth.init(applicationContext)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.45f)

        val id = intent?.data?.getQueryParameter("id") ?: intent?.getStringExtra(EXTRA_ID)
        val pending = id?.let { McpOAuth.getPending(it) }
        if (pending == null) {
            Toast.makeText(this, R.string.mcp_oauth_expired, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pendingId = pending.id

        val d = AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle(R.string.mcp_approve_title)
            .setMessage(getString(R.string.mcp_approve_msg, pending.code2, pending.clientName))
            .setPositiveButton(R.string.mcp_approve, null)
            .setNegativeButton(R.string.mcp_deny) { _, _ ->
                McpPairing.deny(this, pending.id)
                finish()
            }
            .setOnCancelListener { McpPairing.dismissClicked(pending.id) }
            .create()
        d.setOnDismissListener { McpPairing.unregisterDialog(pending.id) }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (working) return@setOnClickListener
                working = true
                d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                d.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                Thread { McpPairing.completeApprove(this, pending.id) }.start()
                d.dismiss()
                finish()
            }
        }
        dialog = d
        d.show()
        // Dim do dialog desligado: o da activity já escurece o fundo uma vez só.
        d.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        McpPairing.registerDialog(pending.id) { if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        pendingId?.let { McpPairing.unregisterDialog(it) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "pairing_id"

        fun requestIntent(context: Context, id: String): Intent =
            Intent(context, OAuthHandlerActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
    }
}
