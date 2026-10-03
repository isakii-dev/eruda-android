package io.liriliri.eruda.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.liriliri.eruda.R

/**
 * Foreground service que mantém o servidor MCP (e o processo do app,
 * logo o WebView) vivo mesmo com a tela desligada ou o app "fechado"
 * (tarefa removida). Equivalente ao `termux-wake-lock`:
 * PARTIAL_WAKE_LOCK + START_STICKY + notificação permanente.
 *
 * Limites honestos: o WakeLock impede o sono da CPU, mas NÃO impede o
 * sistema de matar o processo sob pressão extrema de memória ou
 * otimizações agressivas do fabricante — daí o botão de isenção de
 * bateria na tela de configuração. Se o processo morrer, o sistema
 * recria o serviço (START_STICKY), mas sem o navegador aberto as
 * ferramentas de página respondem "browser not open".
 */
class McpService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "eruda:mcp"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        McpServerManager.log("WakeLock acquired, service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            McpServerManager.log("Stop requested from notification")
            McpServerManager.setEnabled(this, false)
            McpServerManager.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!McpServerManager.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        McpServerManager.start(this)
        // Reemite para atualizar porta/texto caso tenha mudado.
        notifyNow(buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        McpServerManager.log("Service destroyed, releasing WakeLock")
        McpServerManager.stop()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val port = McpServerManager.port(this)
        val openIntent = McpServerActivity.intent(this).let {
            PendingIntent.getActivity(
                this, 0, it,
                immutableFlag() or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val stopIntent = Intent(this, McpService::class.java)
            .setAction(ACTION_STOP).let {
                PendingIntent.getService(
                    this, 1, it,
                    immutableFlag() or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mcp_status)
            .setContentTitle(getString(R.string.mcp_notif_title))
            .setContentText(
                getString(R.string.mcp_notif_text, McpServerManager.url(this))
            )
            .setContentIntent(openIntent)
            .addAction(
                R.drawable.ic_stop, getString(R.string.mcp_notif_stop), stopIntent
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyNow(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, notification)
        } catch (e: Exception) {
            McpServerManager.log("notify failed: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mcp_channel_status),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mcp_channel_desc)
            }
        )
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_START = "io.liriliri.eruda.mcp.START"
        const val ACTION_STOP = "io.liriliri.eruda.mcp.STOP"
        private const val CHANNEL_ID = "mcp_status"
        private const val NOTIF_ID = 41
    }
}
