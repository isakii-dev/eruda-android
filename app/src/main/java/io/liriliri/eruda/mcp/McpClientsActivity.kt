package io.liriliri.eruda.mcp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.liriliri.eruda.R
import io.liriliri.eruda.showConfirmDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista os apps autorizados via OAuth com opção de revogar o acesso. */
class McpClientsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Garante storage mesmo se o servidor nunca subiu neste processo.
        McpOAuth.init(applicationContext)
        setContentView(R.layout.activity_mcp_clients)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val container = findViewById<LinearLayout>(R.id.clientsContainer)
        val empty = findViewById<TextView>(R.id.emptyText)
        container.removeAllViews()
        val clients = McpOAuth.listClients()
        empty.visibility = if (clients.isEmpty()) View.VISIBLE else View.GONE
        val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        clients.forEach { client ->
            container.addView(row(client, dateFmt))
        }
    }

    private fun row(client: McpOAuth.OAuthClient, dateFmt: SimpleDateFormat): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = TextView(ctx).apply {
            text = client.name
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 15f
        }
        val sub = TextView(ctx).apply {
            val dateFmt2 = dateFmt
            val (statusText, colorRes) = when {
                !client.approved -> getString(R.string.mcp_status_waiting) to R.color.warn_orange
                client.lastUsed <= 0 -> getString(R.string.mcp_status_never) to R.color.text_secondary
                else -> getString(
                    R.string.mcp_status_used,
                    dateFmt2.format(Date(client.lastUsed))
                ) to R.color.secure_green
            }
            text = statusText
            setTextColor(ContextCompat.getColor(ctx, colorRes))
            textSize = 12f
        }
        texts.addView(name)
        texts.addView(sub)
        val revoke = Button(ctx).apply {
            text = getString(R.string.mcp_revoke)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 14f
            isAllCaps = false
            setBackgroundResource(R.drawable.settings_card)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                showConfirmDialog(
                    ctx,
                    R.string.mcp_revoke_title,
                    R.string.mcp_revoke_message
                ) {
                    McpOAuth.revokeClient(client.id)
                    refresh()
                }
            }
        }
        row.addView(texts)
        row.addView(revoke)
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context) = Intent(context, McpClientsActivity::class.java)
    }
}
