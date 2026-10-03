package io.liriliri.eruda.mcp

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Ciclo de vida do servidor MCP + prefs + log de chamadas. */
object McpServerManager {

    const val PREFS = "eruda_mcp"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    /** Porta padrão longe das faixas comuns de dev (8080 vive ocupada). */
    const val DEFAULT_PORT = 18789

    private const val LOG_FILE = "mcp-debug.log"
    private const val MAX_LINES = 200

    private var server: McpServer? = null
    private var bridgeRef: WeakReference<McpBridge>? = null
    private var serverBridge: McpBridge? = null
    private var runningPort = -1
    private var runningToken: String? = null
    private var logFile: File? = null
    private val logs = ArrayDeque<String>()

    /** Último erro de start (ex.: porta ocupada), legível pela UI. */
    var lastError: String? = null
        private set

    /** Registra a ponte (MainActivity) para o servidor poder controlar o WebView. */
    fun registerBridge(bridge: McpBridge) {
        bridgeRef = WeakReference(bridge)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Dual-accept (como o repo de referência): vale o bearer estático
     * OU um access token OAuth emitido pelo próprio app.
     */
    fun isAuthorized(staticToken: String, presented: String): Boolean {
        if (presented.isBlank()) return false
        if (presented == staticToken) return true
        return McpOAuth.isAccessToken(presented)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            log("Enable requested (starts when the browser opens)")
        } else {
            stop()
        }
    }

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port.coerceIn(1, 65535)).apply()
    }

    fun token(context: Context): String {
        val existing = prefs(context).getString(KEY_TOKEN, null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    fun regenerateToken(context: Context): String {
        val generated = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_TOKEN, generated).apply()
        log("Token regenerated")
        return generated
    }

    fun isRunning(): Boolean = server != null

    fun start(context: Context): Boolean {
        val port = port(context)
        val token = token(context)
        val bridge = bridgeRef?.get()
        if (server != null) {
            if (runningPort == port && runningToken == token && serverBridge === bridge) {
                return true
            }
            log("Config changed (port/token/bridge), restarting server")
            stop()
        }
        if (!isEnabled(context)) return false
        if (bridge == null) {
            log("Warning: starting without browser bridge (open the app once)")
        }
        McpOAuth.init(context.applicationContext)
        logFile = File(context.cacheDir, LOG_FILE)
        val candidate = McpServer(port, token, bridge)
        return try {
            candidate.start(5000, true)
            server = candidate
            serverBridge = bridge
            runningPort = port
            runningToken = token
            lastError = null
            log("Server started on port $port")
            true
        } catch (e: IOException) {
            lastError = "Could not bind port $port (${e.message})"
            log("Failed to start on port $port: ${e.message}")
            false
        }
    }

    /**
     * Chamado pelo MainActivity.onResume: registra a ponte atual e
     * reinicia o servidor se a instância mudou (ex.: activity recriada).
     */
    fun ensureBridge(context: Context, bridge: McpBridge) {
        registerBridge(bridge)
        if (!isEnabled(context)) return
        if (server != null && serverBridge === bridge) return
        if (server != null) {
            log("Browser instance changed, restarting server")
            stop()
        }
        start(context)
    }

    fun stop() {
        if (server == null) return
        try {
            server?.stop()
        } catch (e: Exception) {
            // ignore
        }
        server = null
        serverBridge = null
        runningPort = -1
        runningToken = null
        log("Server stopped")
    }

    /** Sobe o foreground service (com WakeLock + notificação), se habilitado. */
    fun ensureServiceRunning(context: Context) {
        if (!isEnabled(context)) return
        try {
            val intent = Intent(context.applicationContext, McpService::class.java)
                .setAction(McpService.ACTION_START)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        } catch (e: Exception) {
            log("service start failed: ${e.message}")
            lastError = "Service failed: ${e.message}"
        }
    }

    /** Para o foreground service (e com ele o servidor). */
    fun stopService(context: Context) {
        try {
            val intent = Intent(context.applicationContext, McpService::class.java)
                .setAction(McpService.ACTION_STOP)
            context.applicationContext.startService(intent)
        } catch (e: Exception) {
            log("service stop failed: ${e.message}")
            stop()
        }
    }

    fun url(context: Context): String = "http://127.0.0.1:${port(context)}/mcp"

    fun forwardCommand(context: Context): String =
        "adb forward tcp:${port(context)} tcp:${port(context)}"

    @Synchronized
    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$time  $message"
        logs.addLast(line)
        if (logs.size > MAX_LINES) logs.removeFirst()
        appendFile(line)
    }

    @Synchronized
    fun logs(): List<String> = logs.toList()

    /**
     * Espelho do log em cache/mcp-debug.log — legível via
     * `run-as io.liriliri.eruda cat cache/mcp-debug.log`, sem precisar da UI.
     */
    private fun appendFile(line: String) {
        val file = logFile ?: return
        try {
            val lines = if (file.exists()) file.readLines().toMutableList()
            else mutableListOf()
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (e: Exception) {
            // logging nunca pode quebrar o app
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
