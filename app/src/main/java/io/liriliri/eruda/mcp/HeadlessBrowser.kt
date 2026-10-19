package io.liriliri.eruda.mcp

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView "headless": existe sem activity, preso a um overlay invisível
 * do sistema (tela inteira com alpha ~0 e FLAG_NOT_TOUCHABLE — nada
 * aparece na tela e nada intercepta toques reais; só existe para o
 * WebView ter uma janela e continuar renderizando).
 *
 * É a resposta para "as ferramentas continuam funcionando com o app
 * fechado?": se o usuário concedeu a permissão de overlay, sim — o
 * WebView vive no processo do serviço (foreground + WakeLock +
 * START_STICKY). Sem a permissão (ou se o fabricante proibir WebView
 * fora de activity), as ferramentas de página voltam com
 * "browser not open".
 *
 * Ciclo: criado lazy na primeira ferramenta sem bridge de UI; destruído
 * quando a MainActivity volta (a UI real assume) ou quando o serviço para.
 */
object HeadlessBrowser {

    private const val KEY_LAST_URL = "last_page_url"
    private const val DEFAULT_URL = "https://github.com/liriliri/eruda"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var bridgeRef: WebViewBridge? = null
    private var webView: WebView? = null
    @Volatile private var failed = false

    fun isActive(): Boolean = bridgeRef != null

    /** Criado/consultado de qualquer thread; o addView acontece na main. */
    fun ensureOnMain(context: Context): WebViewBridge? {
        if (Looper.myLooper() == Looper.getMainLooper()) return ensure(context)
        val latch = CountDownLatch(1)
        var result: WebViewBridge? = null
        main.post {
            result = ensure(context)
            latch.countDown()
        }
        latch.await(8, TimeUnit.SECONDS)
        return result
    }

    private fun ensure(context: Context): WebViewBridge? {
        bridgeRef?.let { return it }
        if (failed) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                failed = true
                McpServerManager.log("headless browser unavailable (overlay permission missing)")
                return null
            }
            val wv = WebView(context)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            val bridge = WebViewBridge { webView }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    bridge.addNetwork(
                        JSONObject()
                            .put("method", request.method)
                            .put("url", request.url.toString())
                            .toString()
                    )
                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("http")) rememberUrl(context, url)
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    bridge.addConsole(
                        JSONObject()
                            .put("level", consoleMessage.messageLevel().name)
                            .put("message", consoleMessage.message())
                            .put("source", consoleMessage.sourceId())
                            .put("line", consoleMessage.lineNumber())
                            .toString()
                    )
                    return super.onConsoleMessage(consoleMessage)
                }
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                alpha = 0.01f
            }
            wm.addView(wv, params)
            webView = wv
            bridgeRef = bridge
            val url = lastUrl(context)
            wv.loadUrl(url)
            McpServerManager.log("Headless browser created ($url)")
            bridge
        } catch (e: Exception) {
            failed = true
            McpServerManager.log("headless browser failed: ${e.message}")
            destroy()
            null
        }
    }

    fun destroy() {
        main.post {
            try {
                webView?.let {
                    val wm = it.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(it)
                }
            } catch (e: Exception) {
                // já removido
            }
            try {
                webView?.destroy()
            } catch (e: Exception) {
                // ignore
            }
            webView = null
            bridgeRef = null
        }
    }

    /** Guarda a última página da UI para o headless continuar de onde parou. */
    fun rememberUrl(context: Context, url: String) {
        try {
            context.getSharedPreferences(McpServerManager.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_URL, url).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun lastUrl(context: Context): String =
        context.getSharedPreferences(McpServerManager.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URL, DEFAULT_URL) ?: DEFAULT_URL
}
