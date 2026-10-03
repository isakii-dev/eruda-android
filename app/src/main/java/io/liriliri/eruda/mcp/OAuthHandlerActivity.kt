package io.liriliri.eruda.mcp

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.liriliri.eruda.R

/**
 * Handler dos links de pairing: o sistema oferece o Eruda para abrir
 * URLs http://127.0.0.1 ou localhost em paths /oauth/ (ex.: termux-open
 * no Termux). Mostra o dialog nativo de aprovação; ao aprovar, carrega o
 * redirect numa WebView oculta — o que completa o fluxo quando o
 * cliente MCP roda NESTE aparelho (ex.: opencode no Termux).
 *
 * Se o cliente está noutra máquina, aprove no navegador que ele abriu.
 */
class OAuthHandlerActivity : AppCompatActivity() {

    private var dialog: AlertDialog? = null
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        McpOAuth.init(applicationContext)
        val id = intent?.data?.getQueryParameter("id")
        val pending = id?.let { McpOAuth.getPending(it) }
        if (pending == null) {
            Toast.makeText(this, R.string.mcp_oauth_expired, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        dialog = AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle(R.string.mcp_approve_title)
            .setMessage(getString(R.string.mcp_approve_msg, pending.code2, pending.clientName))
            .setPositiveButton(R.string.mcp_approve) { _, _ -> approve(pending.id) }
            .setNegativeButton(R.string.mcp_deny) { _, _ ->
                McpOAuth.decideDirect(pending.id, false)
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun approve(id: String) {
        dialog?.dismiss()
        dialog = null
        val url = McpOAuth.approveDirect(id)
        if (url == null) {
            Toast.makeText(this, R.string.mcp_oauth_expired, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = false
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (isFinishing) return
                Toast.makeText(
                    this@OAuthHandlerActivity,
                    R.string.mcp_approved, Toast.LENGTH_SHORT
                ).show()
                finish()
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (isFinishing) return
                Toast.makeText(
                    this@OAuthHandlerActivity,
                    R.string.mcp_approve_failed, Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
        setContentView(w, FrameLayout.LayoutParams(1, 1))
        w.loadUrl(url)
        w.postDelayed({ if (!isFinishing) finish() }, 20000)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        try {
            web?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        web = null
        super.onDestroy()
    }
}
