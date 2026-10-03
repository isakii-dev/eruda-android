package io.liriliri.eruda

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.liriliri.eruda.data.Bookmark
import io.liriliri.eruda.data.DataStore
import io.liriliri.eruda.data.HistoryItem
import io.liriliri.eruda.mcp.McpBridge
import io.liriliri.eruda.mcp.McpServerManager
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// https://github.com/mengkunsoft/MkBrowser
class MainActivity : AppCompatActivity(), McpBridge {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: AutoCompleteTextView
    private lateinit var btnRefresh: ImageView
    private lateinit var btnGoBack: ImageView
    private lateinit var btnGoForward: ImageView
    private lateinit var favicon: ImageView
    private lateinit var manager: InputMethodManager
    private lateinit var dataStore: DataStore
    private lateinit var suggestionAdapter: UrlSuggestionAdapter
    private lateinit var btnBookmark: ImageView
    private lateinit var btnBookmarksList: ImageView
    private lateinit var btnHistory: ImageView
    private lateinit var root: FrameLayout
    private lateinit var dock: LinearLayout
    private lateinit var fab: FrameLayout

    private var dockOpen = false
    private var fabDragging = false
    private var fabLifted = false
    private var fabDownX = 0f
    private var fabDownY = 0f
    private var fabStartX = 0f
    private var fabStartY = 0f
    private var fabPreLiftY = 0f
    private lateinit var fabInterpolator: AccelerateDecelerateInterpolator

    private val TAG = "Eruda.MainActivity"
    var mFilePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        initView()
        initWebView(intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL, savedInstanceState)
        initFab()
        handleBackButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun initView() {
        root = findViewById(R.id.root)
        dock = findViewById(R.id.dock)
        fab = findViewById(R.id.fab)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        textUrl = findViewById(R.id.textUrl)
        favicon = findViewById(R.id.webIcon)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnGoBack = findViewById(R.id.goBack)
        btnGoForward = findViewById(R.id.goForward)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnBookmarksList = findViewById(R.id.btnBookmarksList)
        btnHistory = findViewById(R.id.btnHistory)

        fabInterpolator = AccelerateDecelerateInterpolator()

        dataStore = DataStore(this)
        suggestionAdapter = UrlSuggestionAdapter(this)
        suggestionAdapter.setData(dataStore.getRecentHistory(20))
        textUrl.setAdapter(suggestionAdapter)

        // Esconde a dock quando o toque cai fora dela (e fora do FAB).
        dock.animate().setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!dockOpen) {
                    dock.visibility = View.INVISIBLE
                }
            }
        })

        btnBookmark.setOnClickListener {
            toggleBookmark()
        }

        btnBookmarksList.setOnClickListener {
            closeDock()
            startActivity(BookmarksActivity.intent(this, BookmarksActivity.MODE_BOOKMARKS))
        }

        btnHistory.setOnClickListener {
            closeDock()
            startActivity(BookmarksActivity.intent(this, BookmarksActivity.MODE_HISTORY))
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            closeDock()
            startActivity(SettingsActivity.intent(this))
        }

        findViewById<View>(R.id.btnSiteInfo).setOnClickListener {
            textUrl.clearFocus()
            showSiteInfo()
        }

        btnRefresh.setOnClickListener {
            if (textUrl.hasFocus()) {
                submitUrl()
            } else {
                webView.reload()
            }
        }

        btnGoBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        btnGoForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }

        val urlGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                textUrl.post { textUrl.selectAll() }
                return true
            }
        })
        textUrl.setOnTouchListener { _, event ->
            urlGesture.onTouchEvent(event)
            false
        }
        textUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                suggestionAdapter.setData(dataStore.getRecentHistory(20))
                textUrl.setText(webView.url)
                textUrl.post { textUrl.selectAll() }
                btnRefresh.setImageResource(R.drawable.ic_arrow_right)
            } else {
                textUrl.setText(webView.title)
                btnRefresh.setImageResource(R.drawable.ic_refresh)
            }
        }
        textUrl.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN) {
                submitUrl()
            }

            return@setOnKeyListener false
        }
        textUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitUrl()
                true
            } else {
                false
            }
        }
    }

    private fun submitUrl() {
        if (manager.isActive) {
            manager.hideSoftInputFromWindow(textUrl.applicationWindowToken, 0)
        }
        var input = textUrl.text.toString()
        if (!isHttpUrl(input)) {
            if (mayBeUrl(input)) {
                input = "https://${input}"
            } else {
                try {
                    input = URLEncoder.encode(input, "utf-8")
                } catch (e: UnsupportedEncodingException) {
                    Log.e(TAG, e.message.toString())
                }
                input = "https://www.google.com/search?q=${input}"
            }
        }
        webView.loadUrl(input)
        textUrl.clearFocus()
    }

    private fun showSiteInfo() {
        val v = layoutInflater.inflate(R.layout.dialog_site_info, null)
        val url = webView.url
        val secure = url != null && url.startsWith("https://", ignoreCase = true)

        val host = try {
            Uri.parse(url ?: "").host ?: url ?: ""
        } catch (e: Exception) {
            url ?: ""
        }
        v.findViewById<TextView>(R.id.infoHost).text = host

        v.findViewById<ImageView>(R.id.infoSecIcon).apply {
            setImageResource(if (secure) R.drawable.ic_lock else R.drawable.ic_alert_triangle)
            setColorFilter(ContextCompat.getColor(context, if (secure) R.color.secure_green else R.color.warn_orange))
        }
        v.findViewById<TextView>(R.id.infoSecTitle).setText(if (secure) R.string.site_info_secure else R.string.site_info_insecure)
        v.findViewById<TextView>(R.id.infoSecSub).setText(if (secure) R.string.site_info_secure_sub else R.string.site_info_insecure_sub)

        var dialog: AlertDialog? = null
        val clearAction = View.OnClickListener {
            clearSiteDataNow()
            dialog?.dismiss()
        }
        v.findViewById<View>(R.id.infoDataRow).setOnClickListener(clearAction)
        v.findViewById<View>(R.id.infoClear).setOnClickListener(clearAction)

        dialog = AlertDialog.Builder(this, R.style.AppDialog)
            .setView(v)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
    }

    private fun clearSiteDataNow() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.reload()
        Toast.makeText(this, R.string.toast_site_data_cleared, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // FAB flutuante + dock
    // ---------------------------------------------------------------------

    private fun initFab() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        fab.setOnClickListener {
            toggleDock()
        }

        fab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fabDownX = event.rawX
                    fabDownY = event.rawY
                    fabStartX = v.x
                    fabStartY = v.y
                    fabDragging = false
                    v.animate().cancel()
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - fabDownX
                    val dy = event.rawY - fabDownY
                    if (!fabDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        fabDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (fabDragging) {
                        val maxX = (root.width - v.width).toFloat().coerceAtLeast(0f)
                        val maxY = (root.height - v.height).toFloat().coerceAtLeast(0f)
                        v.x = (fabStartX + dx).coerceIn(0f, maxX)
                        v.y = (fabStartY + dy).coerceIn(0f, maxY)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (fabDragging) {
                        fabDragging = false
                        snapFab()
                        saveFabPosition()
                    } else {
                        v.performClick()
                    }
                    true
                }

                else -> false
            }
        }

        restoreFabPosition()

        // Rede de segurança: se qualquer layout deixar o FAB fora da
        // área visível (rotação, teclado, multi-janela), traz de volta.
        // Não mexe em nada quando já está dentro (não briga com o arrasto).
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampFabIfOutside()
        }
    }

    private fun toggleDock() {
        if (dockOpen) closeDock() else openDock()
    }

    private fun openDock() {
        if (dockOpen) return
        dockOpen = true
        dock.visibility = View.VISIBLE
        dock.animate().cancel()
        dock.alpha = 0f
        dock.translationY = dock.height.toFloat()
        dock.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(240)
            .setInterpolator(fabInterpolator)
            .start()

        suggestionAdapter.setData(dataStore.getRecentHistory(20))
        animateFab(active = true, targetY = fabTargetYAboveDock())
    }

    private fun closeDock() {
        if (!dockOpen) return
        dockOpen = false
        dock.animate().cancel()
        dock.animate()
            .translationY(dock.height.toFloat())
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(fabInterpolator)
            .start()

        val targetY = if (fabLifted) {
            fabLifted = false
            fabPreLiftY
        } else {
            null
        }
        animateFab(active = false, targetY = targetY)
    }

    /** Sobe o FAB para fora da dock quando ele ficaria coberto por ela. */
    private fun fabTargetYAboveDock(): Float? {
        if (dock.height == 0) return null
        val dockTop = (root.height - dock.height).toFloat()
        if (fab.y + fab.height <= dockTop) {
            return null
        }
        if (!fabLifted) {
            fabLifted = true
            fabPreLiftY = fab.y
        }
        val margin = 8f * resources.displayMetrics.density
        return (dockTop - fab.height - margin).coerceAtLeast(0f)
    }

    private fun animateFab(
        active: Boolean,
        targetX: Float? = null,
        targetY: Float? = null,
        duration: Long = 200L
    ) {
        val alpha = if (active) 0.95f else 0.32f
        val scale = if (active) 1f else 0.9f
        val anim = fab.animate()
            .alpha(alpha)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration)
            .setInterpolator(fabInterpolator)
        targetX?.let { anim.x(it) }
        targetY?.let { anim.y(it) }
        anim.start()
    }

    /** Traz o FAB para dentro da área visível (sem animação). */
    private fun clampFabIfOutside() {
        if (root.width <= 0 || root.height <= fab.height || fab.width <= 0) return
        if (fab.visibility != View.VISIBLE) return
        val maxX = (root.width - fab.width).toFloat()
        val maxY = (root.height - fab.height).toFloat()
        val nx = fab.x.coerceIn(0f, maxX)
        val ny = fab.y.coerceIn(0f, maxY)
        if (nx != fab.x || ny != fab.y) {
            fab.animate().cancel()
            fab.x = nx
            fab.y = ny
            saveFabPosition()
        }
    }

    /** Encosta o FAB na borda mais próxima (esquerda/direita), mantendo o Y. */
    private fun snapFab() {
        val maxX = (root.width - fab.width).toFloat().coerceAtLeast(0f)
        val targetX = if (fab.x + fab.width / 2f < root.width / 2f) 0f else maxX
        animateFab(active = dockOpen, targetX = targetX, duration = 180L)
    }

    private fun saveFabPosition() {
        if (root.width <= 0 || root.height <= fab.height) return // layout degenerado: não corrompe
        val maxY = (root.height - fab.height).toFloat().coerceAtLeast(1f)
        getSharedPreferences(PREFS_FAB, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FAB_LEFT, fab.x + fab.width / 2f < root.width / 2f)
            .putFloat(KEY_FAB_Y, (fab.y / maxY).coerceIn(0f, 1f))
            .apply()
    }

    private fun restoreFabPosition() {
        placeFabByFraction()
    }

    /**
     * Rotação não recria mais a activity (configChanges): o FAB manteria
     * as coordenadas absolutas do retrato e sumiria no deitado.
     * Reposiciona pela mesma fração salva (lado + Y relativo).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (dockOpen) closeDock()
        fabLifted = false
        placeFabByFraction()
    }

    /**
     * Posiciona o FAB pela fração salva (lado + Y relativo). O primeiro
     * layout pode vir com dimensões degeneradas (ex.: altura == altura do
     * FAB durante a animação de rotação) — nesse caso tenta de novo no
     * próximo frame em vez de aceitar 0,0.
     */
    private fun placeFabByFraction(attempt: Int = 0) {
        if (attempt > 30) return
        if (root.width <= 0 || root.height <= fab.height || fab.width <= 0) {
            root.post { placeFabByFraction(attempt + 1) }
            return
        }
        fab.animate().cancel()
        val prefs = getSharedPreferences(PREFS_FAB, Context.MODE_PRIVATE)
        val onLeft = prefs.getBoolean(KEY_FAB_LEFT, true)
        val yFrac = prefs.getFloat(KEY_FAB_Y, 0.45f)
        val maxX = (root.width - fab.width).toFloat().coerceAtLeast(0f)
        val maxY = (root.height - fab.height).toFloat().coerceAtLeast(0f)
        fab.x = if (onLeft) 0f else maxX
        fab.y = (maxY * yFrac).coerceIn(0f, maxY)
        fab.visibility = View.VISIBLE
        animateFab(active = false, duration = 0L)
        saveFabPosition()
    }

    private fun handleBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    dockOpen -> closeDock()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ---------------------------------------------------------------------

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun initWebView(startUrl: String, savedState: Bundle? = null) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                if (isHttpUrl(url)) {
                    return false
                }

                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    false
                } catch (e: Exception) {
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                addMcpEntry(
                    JSONObject()
                        .put("method", request.method)
                        .put("url", request.url.toString())
                        .toString(),
                    mcpNetwork
                )
                // O WebView bloqueia file:///android_asset como subrecurso de páginas
                // https, então servimos o eruda.js por uma URL falsa interceptada.
                if (request.url.toString() == ERUDA_ASSET_URL) {
                    return try {
                        WebResourceResponse(
                            "application/javascript",
                            "utf-8",
                            assets.open("eruda.js")
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, e.message.toString())
                        null
                    }
                }

                if (request.isForMainFrame) {
                    val url = request.url.toString()
                    if (!isHttpUrl(url)) {
                        return null
                    }
                    Log.i(TAG, "Loading url: $url")

                    var headers = request.requestHeaders.toHeaders()
                    val contentType = headers["content-type"]
                    if (contentType == "application/x-www-form-urlencoded") {
                        return null
                    }
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (cookie != null) {
                        headers = (headers.toMap() + Pair("cookie", cookie)).toHeaders()
                    }
                    Log.i(TAG, "Intercept url: $url")
                    Log.i(TAG, "Request headers: ${headers.toMap()}")

                    val client = OkHttpClient.Builder().followRedirects(false).build()
                    val req = Request.Builder()
                        .url(url)
                        .headers(headers)
                        .build()

                    return try {
                        val response = client.newCall(req).execute()
                        if (response.headers["content-security-policy"] == null) {
                            return null
                        }
                        val resHeaders =
                            response.headers.toMap().filter { it.key != "content-security-policy" }
                        Log.i(TAG, "Response headers: $resHeaders")

                        WebResourceResponse(
                            "text/html",
                            response.header("content-encoding", "utf-8"),
                            response.code,
                            "ok",
                            resHeaders,
                            response.body?.byteStream()
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, e.message.toString())
                        null
                    }
                }

                return null
            }

            override fun onPageStarted(view: WebView?, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
                setTextUrl("Loading...")
                this@MainActivity.favicon.setImageResource(R.drawable.tool)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                progressBar.visibility = View.INVISIBLE
                title = view.title
                setTextUrl(view.title)

                saveHistory(url, view.title ?: url)

                updateBookmarkIcon(url)

                val script = """
                    (function () {
                        if (window.eruda || window.__erudaLoading) return;
                        window.__erudaLoading = true;
                        var define;
                        if (window.define) {
                            define = window.define;
                            window.define = null;
                        }
                        var script = document.createElement('script');
                        script.onload = function () {
                            window.__erudaLoading = false;
                            eruda.init();
                            if (define) {
                                window.define = define;
                            }
                        };
                        script.onerror = function () {
                            window.__erudaLoading = false;
                        };
                        script.src = '$ERUDA_ASSET_URL';
                        document.body.appendChild(script);
                    })();
                """
                webView.evaluateJavascript(script) {}
            }
        }

        val selectFileLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (mFilePathCallback != null) {
                    mFilePathCallback!!.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(
                            result.resultCode,
                            result.data
                        )
                    )
                    mFilePathCallback = null
                }
            }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)

                progressBar.progress = newProgress
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                addMcpEntry(
                    JSONObject()
                        .put("level", consoleMessage.messageLevel().name)
                        .put("message", consoleMessage.message())
                        .put("source", consoleMessage.sourceId())
                        .put("line", consoleMessage.lineNumber())
                        .toString(),
                    mcpConsole
                )
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                super.onReceivedIcon(view, icon)

                favicon.setImageBitmap(icon)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                if (mFilePathCallback != null) {
                    mFilePathCallback!!.onReceiveValue(null)
                    mFilePathCallback = null
                }
                mFilePathCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                try {
                    selectFileLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    mFilePathCallback = null
                    return false
                }
                return true
            }
        }
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.builtInZoomControls = true
        settings.setDisplayZoomControls(false)
        settings.setSupportZoom(true)

        if (resources.getString(R.string.mode) == "night") {
            // https://stackoverflow.com/questions/57449900/letting-webview-on-android-work-with-prefers-color-scheme-dark
            val supportForceDarkStrategy =
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)
            val supportForceDark = WebViewFeature.isFeatureSupported(
                WebViewFeature.FORCE_DARK
            )
            if (supportForceDarkStrategy && supportForceDark) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        }

        // Restaura a página após recreação (troca de tema / morte pelo LMK).
        val restored = savedState != null && webView.restoreState(savedState) != null
        if (!restored) {
            webView.loadUrl(startUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        intent.getStringExtra(EXTRA_URL)?.let { url ->
            if (isHttpUrl(url)) {
                closeDock()
                webView.loadUrl(url)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        suggestionAdapter.setData(dataStore.getRecentHistory(20))
        webView.url?.let { updateBookmarkIcon(it) }

        val settings = getSharedPreferences(SettingsActivity.PREFS_SETTINGS, MODE_PRIVATE)
        if (settings.getBoolean(SettingsActivity.KEY_PENDING_RELOAD, false)) {
            settings.edit().putBoolean(SettingsActivity.KEY_PENDING_RELOAD, false).apply()
            webView.clearCache(true)
            webView.reload()
        }

        McpServerManager.registerBridge(this)
        McpServerManager.ensureServiceRunning(this)
    }

    // Sem override de onDestroy para o MCP: o McpService (foreground)
    // é o dono do servidor e sobrevive à destruição da activity.

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    if (manager.isActive) {
                        manager.hideSoftInputFromWindow(textUrl.applicationWindowToken, 0)
                    }
                }
            }

            if (dockOpen) {
                val rootLoc = IntArray(2)
                root.getLocationOnScreen(rootLoc)
                val x = event.rawX - rootLoc[0]
                val y = event.rawY - rootLoc[1]
                val inDock = x >= dock.x && x <= dock.x + dock.width &&
                    y >= dock.y && y <= dock.y + dock.height
                val inFab = x >= fab.x && x <= fab.x + fab.width &&
                    y >= fab.y && y <= fab.y + fab.height
                if (!inDock && !inFab) {
                    closeDock()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun saveHistory(url: String, title: String) {
        dataStore.addHistory(HistoryItem(url = url, title = title))
    }

    private fun toggleBookmark() {
        val url = webView.url ?: return
        val pageTitle = webView.title ?: url
        if (dataStore.isBookmarked(url)) {
            dataStore.removeBookmark(url)
        } else {
            dataStore.addBookmark(Bookmark(url = url, title = pageTitle))
        }
        updateBookmarkIcon(url)
    }

    private fun updateBookmarkIcon(url: String) {
        btnBookmark.setImageResource(
            if (dataStore.isBookmarked(url)) R.drawable.ic_star_filled
            else R.drawable.ic_star
        )
    }

    private fun setTextUrl(text: String?) {
        if (!textUrl.hasFocus() && text != null) {
            textUrl.setText(text)
        }
    }

    // ---------------------------------------------------------------------
    // MCP bridge (io.liriliri.eruda.mcp.McpBridge)
    // ---------------------------------------------------------------------

    private val mcpConsole = ArrayDeque<String>()
    private val mcpNetwork = ArrayDeque<String>()

    @Synchronized
    private fun addMcpEntry(entry: String, deck: ArrayDeque<String>) {
        deck.addLast(entry)
        if (deck.size > 100) deck.removeFirst()
    }

    @Synchronized
    private fun dumpMcpDeck(deck: ArrayDeque<String>, limit: Int): String =
        JSONArray(deck.takeLast(limit.coerceAtLeast(1))).toString()

    /** Roda o bloco na UI thread a partir de uma thread do servidor MCP. */
    private fun <T> uiResult(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<Any?>(1)
        runOnUiThread {
            try {
                box[0] = block()
            } catch (e: Exception) {
                box[0] = e
            } finally {
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        val value = box[0]
        if (value is Exception) throw value
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun uiRun(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
    }

    override fun screenshotBase64(): String = uiResult {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return@uiResult ""
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        webView.draw(Canvas(bitmap))
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
        bitmap.recycle()
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    override fun tap(x: Float, y: Float) = uiRun {
        val t = SystemClock.uptimeMillis()
        webView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        webView.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    override fun press(x: Float, y: Float, durationMs: Long) {
        val latch = CountDownLatch(1)
        val t = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())
        uiRun {
            webView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            handler.postDelayed({
                webView.dispatchTouchEvent(
                    MotionEvent.obtain(t, t + durationMs, MotionEvent.ACTION_UP, x, y, 0)
                )
                latch.countDown()
            }, durationMs)
        }
        latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val steps = 24
        val latch = CountDownLatch(1)
        val t = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())
        uiRun {
            webView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x1, y1, 0))
            var step = 1
            val move = object : Runnable {
                override fun run() {
                    if (step < steps) {
                        val f = step.toFloat() / steps
                        webView.dispatchTouchEvent(
                            MotionEvent.obtain(
                                t, t + (durationMs * f).toLong(), MotionEvent.ACTION_MOVE,
                                x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, 0
                            )
                        )
                        step++
                        handler.postDelayed(this, durationMs / steps)
                    } else {
                        webView.dispatchTouchEvent(
                            MotionEvent.obtain(t, t + durationMs, MotionEvent.ACTION_UP, x2, y2, 0)
                        )
                        latch.countDown()
                    }
                }
            }
            handler.postDelayed(move, durationMs / steps)
        }
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
    }

    override fun scroll(direction: String, amount: Float) {
        val width = uiResult { webView.width }
        val height = uiResult { webView.height }
        if (width <= 0 || height <= 0) return
        val dx = width * 0.6f * amount
        val dy = height * 0.6f * amount
        val halfW = width / 2f
        val halfH = height / 2f
        when (direction.lowercase()) {
            "up" -> swipe(halfW, height * 0.25f, halfW, height * 0.25f + dy, 300)
            "down" -> swipe(halfW, height * 0.75f, halfW, height * 0.75f - dy, 300)
            "left" -> swipe(width * 0.75f, halfH, width * 0.75f - dx, halfH, 300)
            "right" -> swipe(width * 0.25f, halfH, width * 0.25f + dx, halfH, 300)
            else -> throw IllegalArgumentException("direction must be up|down|left|right")
        }
    }

    override fun pinch(
        centerX: Float, centerY: Float,
        startDistance: Float, endDistance: Float,
        durationMs: Long
    ) {
        val steps = 24
        val latch = CountDownLatch(1)
        val t = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())

        val p0 = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val p1 = MotionEvent.PointerProperties().apply {
            id = 1
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }

        fun twoPointer(eventTime: Long, action: Int, distance: Float): MotionEvent {
            val c0 = MotionEvent.PointerCoords().apply {
                x = centerX
                y = centerY - distance / 2f
                pressure = 1f
                size = 1f
            }
            val c1 = MotionEvent.PointerCoords().apply {
                x = centerX
                y = centerY + distance / 2f
                pressure = 1f
                size = 1f
            }
            return MotionEvent.obtain(
                t, eventTime, action, 2,
                arrayOf(p0, p1), arrayOf(c0, c1),
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
        }

        uiRun {
            val c0 = MotionEvent.PointerCoords().apply {
                x = centerX
                y = centerY - startDistance / 2f
                pressure = 1f
                size = 1f
            }
            webView.dispatchTouchEvent(
                MotionEvent.obtain(
                    t, t, MotionEvent.ACTION_DOWN, 1,
                    arrayOf(p0), arrayOf(c0),
                    0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0
                )
            )
            webView.dispatchTouchEvent(
                twoPointer(
                    t,
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) or MotionEvent.ACTION_POINTER_DOWN,
                    startDistance
                )
            )
            var step = 1
            val move = object : Runnable {
                override fun run() {
                    if (step < steps) {
                        val f = step.toFloat() / steps
                        val distance = startDistance + (endDistance - startDistance) * f
                        webView.dispatchTouchEvent(
                            twoPointer(t + (durationMs * f).toLong(), MotionEvent.ACTION_MOVE, distance)
                        )
                        step++
                        handler.postDelayed(this, durationMs / steps)
                    } else {
                        webView.dispatchTouchEvent(
                            twoPointer(
                                t + durationMs,
                                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) or MotionEvent.ACTION_POINTER_UP,
                                endDistance
                            )
                        )
                        val cEnd = MotionEvent.PointerCoords().apply {
                            x = centerX
                            y = centerY - endDistance / 2f
                            pressure = 1f
                            size = 1f
                        }
                        webView.dispatchTouchEvent(
                            MotionEvent.obtain(
                                t, t + durationMs + 10, MotionEvent.ACTION_UP, 1,
                                arrayOf(p0), arrayOf(cEnd),
                                0, 0, 1f, 1f, 0, 0,
                                InputDevice.SOURCE_TOUCHSCREEN, 0
                            )
                        )
                        latch.countDown()
                    }
                }
            }
            handler.postDelayed(move, durationMs / steps)
        }
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
    }

    override fun typeText(text: String): String = evalJs(
        "(function(){var el=document.activeElement;" +
            "if(!el)return'no focused element';" +
            "var v=${JSONObject.quote(text)};" +
            "if(el.isContentEditable){el.textContent=v;}" +
            "else if(el.value!==undefined){el.value=v;}" +
            "else return'no focused input';" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return'ok';})()"
    )

    override fun clickSelector(selector: String): String = evalJs(
        "(function(){var el=document.querySelector(${JSONObject.quote(selector)});" +
            "if(!el)return'not found';" +
            "el.scrollIntoView({block:'center'});" +
            "el.click();" +
            "return'clicked';})()"
    )

    override fun domQuery(selector: String, limit: Int): String {
        val raw = evalJs(
            "(function(){var els=document.querySelectorAll(${JSONObject.quote(selector)});" +
                "var out=[];var n=Math.min(els.length,$limit);" +
                "for(var i=0;i<n;i++){var el=els[i];var r=el.getBoundingClientRect();" +
                "out.push({tag:el.tagName,text:(el.textContent||'').trim().slice(0,120)," +
                "rect:{x:r.x,y:r.y,width:r.width,height:r.height}});}" +
                "return JSON.stringify(out);})()"
        )
        return unwrapJsString(raw)
    }

    override fun evalJs(script: String): String {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        runOnUiThread {
            webView.evaluateJavascript(script) { value ->
                box[0] = value
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return box[0] ?: "null"
    }

    private fun unwrapJsString(raw: String?): String {
        if (raw.isNullOrEmpty()) return "[]"
        return try {
            val value = JSONTokener(raw).nextValue()
            if (value is String) value else raw
        } catch (e: Exception) {
            raw
        }
    }

    override fun navigate(url: String) = uiRun { webView.loadUrl(url) }

    override fun back() = uiRun { if (webView.canGoBack()) webView.goBack() }

    override fun forward() = uiRun { if (webView.canGoForward()) webView.goForward() }

    override fun pageInfo(): String = uiResult {
        val url = webView.url ?: ""
        JSONObject()
            .put("url", if (url.isEmpty()) JSONObject.NULL else url)
            .put("title", webView.title ?: JSONObject.NULL)
            .put("secure", url.startsWith("https://", ignoreCase = true))
            .toString()
    }

    override fun consoleLogs(limit: Int): String = dumpMcpDeck(mcpConsole, limit)

    override fun networkRequests(limit: Int): String = dumpMcpDeck(mcpNetwork, limit)

    override fun clearSiteData() = uiRun { clearSiteDataNow() }

    companion object {
        const val EXTRA_URL = "url"
        private const val DEFAULT_URL = "https://github.com/liriliri/eruda"
        private const val PREFS_FAB = "eruda_fab"
        private const val KEY_FAB_LEFT = "fab_left"
        private const val KEY_FAB_Y = "fab_y"

        /** URL interceptada que serve o assets/eruda.js (file:// é bloqueado em páginas https). */
        private const val ERUDA_ASSET_URL = "https://appassets.eruda.local/eruda.js"
    }
}

fun isHttpUrl(url: String): Boolean {
    return url.startsWith("http:") || url.startsWith("https:")
}

fun mayBeUrl(text: String): Boolean {
    val domains = arrayOf(".com", ".io", ".me", ".org", ".net", ".tv", ".cn")

    return domains.any { text.contains(it) }
}
