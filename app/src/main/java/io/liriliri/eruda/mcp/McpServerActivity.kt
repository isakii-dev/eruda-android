package io.liriliri.eruda.mcp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.liriliri.eruda.R

class McpServerActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val logRefresher = object : Runnable {
        override fun run() {
            refreshLog()
            refreshPairing()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mcp_server)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val switch = findViewById<Switch>(R.id.switchEnabled)
        val statusValue = findViewById<TextView>(R.id.statusValue)
        val urlValue = findViewById<TextView>(R.id.urlValue)
        val tokenValue = findViewById<TextView>(R.id.tokenValue)
        val portValue = findViewById<EditText>(R.id.portValue)
        val hintText = findViewById<TextView>(R.id.hintText)

        switch.isChecked = McpServerManager.isEnabled(this)
        switch.setOnCheckedChangeListener { _, enabled ->
            McpServerManager.setEnabled(this, enabled)
            if (enabled) {
                requestNotifPermission()
                McpServerManager.ensureServiceRunning(this)
                // O serviço sobe de forma assíncrona; reconfere em seguida.
                handler.postDelayed({ refreshStatus(statusValue) }, 1500)
            } else {
                McpServerManager.stopService(this)
            }
            refreshStatus(statusValue)
        }

        val autoBoot = findViewById<Switch>(R.id.switchAutoBoot)
        autoBoot.isChecked = McpServerManager.isAutoBoot(this)
        autoBoot.setOnCheckedChangeListener { _, on ->
            McpServerManager.setAutoBoot(this, on)
            if (on && McpServerManager.isEnabled(this) && !McpServerManager.isRunning()) {
                McpServerManager.ensureServiceRunning(this)
            }
        }

        findViewById<LinearLayout>(R.id.rowUrl).setOnClickListener {
            copy(McpServerManager.url(this))
        }

        findViewById<LinearLayout>(R.id.rowClients).setOnClickListener {
            startActivity(McpClientsActivity.intent(this))
        }

        findViewById<LinearLayout>(R.id.rowPairing).setOnClickListener {
            Toast.makeText(this, R.string.mcp_pairing_toast, Toast.LENGTH_LONG).show()
        }

        findViewById<ImageButton>(R.id.btnCopyToken).setOnClickListener {
            copy(McpServerManager.token(this))
        }

        findViewById<ImageButton>(R.id.btnRegenerateToken).setOnClickListener {
            tokenValue.text = McpServerManager.regenerateToken(this)
            restartIfRunning(statusValue)
        }

        portValue.setText(McpServerManager.port(this).toString())
        portValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val port = s?.toString()?.toIntOrNull()
                if (port != null) {
                    McpServerManager.setPort(this@McpServerActivity, port)
                    restartIfRunning(statusValue)
                    urlValue.text = McpServerManager.url(this@McpServerActivity)
                }
            }
        })

        tokenValue.text = McpServerManager.token(this)
        urlValue.text = McpServerManager.url(this)

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    Toast.makeText(this, R.string.mcp_toast_battery_ok, Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.mcp_toast_start_failed, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, R.string.mcp_toast_start_failed, Toast.LENGTH_LONG).show()
            }
        }

        val forward = McpServerManager.forwardCommand(this)
        val url = McpServerManager.url(this)
        hintText.text =
            "To connect from your computer:\n" +
                "1. $forward\n" +
                "2. Point your MCP client to $url with header\n" +
                "   Authorization: Bearer <token>\n" +
                "   or run: opencode mcp auth eruda\n" +
                "   (OAuth with on-device 2-digit approval)\n\n" +
                "The server keeps running in the background\n" +
                "(see the notification) even with the app closed."

        // Self-heal: se habilitado mas o serviço morreu, ressuscita ao abrir a tela.
        if (McpServerManager.isEnabled(this)) {
            McpServerManager.ensureServiceRunning(this)
        }
        refreshStatus(statusValue)
    }

    override fun onResume() {
        super.onResume()
        handler.post(logRefresher)
        refreshClientsCount()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(logRefresher)
    }

    private fun refreshStatus(statusValue: TextView) {
        if (McpServerManager.isRunning()) {
            statusValue.setText(R.string.mcp_running)
        } else {
            val error = McpServerManager.lastError
            statusValue.text = error ?: getString(R.string.mcp_stopped)
        }
        refreshClientsCount()
    }

    private fun refreshClientsCount() {
        try {
            findViewById<TextView>(R.id.clientsValue).text =
                McpOAuth.listClients().size.toString()
        } catch (e: Exception) {
            // OAuth ainda não inicializado (servidor nunca subiu)
        }
    }

    /** Mostra o pedido de pairing pendente (código para conferir). */
    private fun refreshPairing() {
        try {
            val row = findViewById<LinearLayout>(R.id.rowPairing)
            val value = findViewById<TextView>(R.id.pairingValue)
            val pending = McpOAuth.pendingSummaries()
            if (pending.isEmpty()) {
                row.visibility = View.GONE
            } else {
                val first = pending.first()
                value.text = "Code ${first.code2} • ${first.clientName}" +
                    if (pending.size > 1) " (+${pending.size - 1})" else ""
                row.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Aplica porta/token novos sem sair da tela. */
    private fun restartIfRunning(statusValue: TextView) {
        if (!McpServerManager.isEnabled(this)) return
        McpServerManager.ensureServiceRunning(this)
        Toast.makeText(this, R.string.mcp_toast_restarted, Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refreshStatus(statusValue) }, 1500)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41
            )
        }
    }

    private fun refreshLog() {
        val logs = McpServerManager.logs()
        findViewById<TextView>(R.id.logText).text =
            if (logs.isEmpty()) getString(R.string.mcp_log_empty) else logs.joinToString("\n")
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("eruda", text))
        Toast.makeText(this, R.string.mcp_toast_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context) = android.content.Intent(context, McpServerActivity::class.java)
    }
}
