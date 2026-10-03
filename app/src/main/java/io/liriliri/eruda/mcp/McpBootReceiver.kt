package io.liriliri.eruda.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-start opcional do servidor no boot (só se o usuário ligou
 * "Start on device boot" NA TELA e o servidor está habilitado).
 * Nada liga sozinho numa instalação fresca: os dois prefs nascem false.
 *
 * Nota honesta: no Android 12+ o sistema pode barrar/demorrar o start
 * do foreground service direto do boot; nesse caso o servidor sobe
 * na primeira abertura do app (comportamento garantido).
 */
class McpBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!McpServerManager.isAutoBoot(context)) {
            return
        }
        McpServerManager.log("Boot completed, auto-start requested")
        McpServerManager.ensureServiceRunning(context)
    }
}
