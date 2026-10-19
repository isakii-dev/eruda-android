package io.liriliri.eruda.mcp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Implementação única da ponte MCP sobre um WebView qualquer: o da
 * MainActivity (com UI) ou o headless do serviço (sem activity).
 *
 * Eventos de toque são MotionEvent de verdade no WebView (TouchStart/Move/
 * End do Chromium), e o teclado usa KeyEvent de verdade no elemento focado
 * — o que destrava sites com handlers de drag/gesto/tecla que ignoram
 * `el.click()` ou `el.value = x`.
 */
class WebViewBridge(private val provider: () -> WebView?) : McpBridge {

    private val main = Handler(Looper.getMainLooper())
    private val consoleDeck = ArrayDeque<String>()
    private val networkDeck = ArrayDeque<String>()

    // ------------------------------------------------------------------
    // Decks de console/rede (alimentados pelos WebViewClient da UI/headless)
    // ------------------------------------------------------------------

    fun addConsole(entry: String) = push(consoleDeck, entry)

    fun addNetwork(entry: String) = push(networkDeck, entry)

    private fun push(deck: ArrayDeque<String>, entry: String) {
        synchronized(deck) {
            deck.addLast(entry)
            while (deck.size > 100) deck.removeFirst()
        }
    }

    private fun dump(deck: ArrayDeque<String>, limit: Int): String = synchronized(deck) {
        JSONArray(deck.takeLast(limit.coerceAtLeast(1))).toString()
    }

    // ------------------------------------------------------------------
    // Execução na main thread
    // ------------------------------------------------------------------

    private fun view(): WebView =
        provider() ?: throw IllegalStateException(
            "browser not open - open the Eruda app once (grant 'display over other apps' for closed-app use)"
        )

    private fun <T> uiResult(block: (WebView) -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block(view())
        var result: Any? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                result = block(view())
            } catch (e: Exception) {
                result = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("browser busy (main thread timeout)")
        }
        val value = result
        if (value is Exception) throw value
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun uiRun(block: (WebView) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(view())
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                block(view())
            } catch (e: Exception) {
                McpServerManager.log("uiRun failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------
    // Captura e toque
    // ------------------------------------------------------------------

    override fun screenshotBase64(): String = uiResult { wv ->
        val width = wv.width
        val height = wv.height
        if (width <= 0 || height <= 0) return@uiResult ""
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        wv.draw(Canvas(bitmap))
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
        bitmap.recycle()
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    override fun tap(x: Float, y: Float) = click(x, y)

    override fun click(x: Float, y: Float) = uiRun { wv ->
        val t = SystemClock.uptimeMillis()
        wv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        wv.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    override fun doubleClick(x: Float, y: Float) {
        val latch = CountDownLatch(1)
        val t0 = SystemClock.uptimeMillis()
        main.post {
            try {
                val wv = provider()
                if (wv == null) {
                    latch.countDown()
                    return@post
                }
                fun send(t: Long, action: Int) =
                    wv.dispatchTouchEvent(MotionEvent.obtain(t0, t, action, x, y, 0))

                send(t0, MotionEvent.ACTION_DOWN)
                send(t0 + 40, MotionEvent.ACTION_UP)
                main.postDelayed({
                    send(t0 + 130, MotionEvent.ACTION_DOWN)
                    send(t0 + 170, MotionEvent.ACTION_UP)
                    latch.countDown()
                }, 180)
            } catch (e: Exception) {
                McpServerManager.log("doubleClick failed: ${e.message}")
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    override fun press(x: Float, y: Float, durationMs: Long) {
        val latch = CountDownLatch(1)
        val t = SystemClock.uptimeMillis()
        uiRun { wv ->
            wv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            main.postDelayed({
                try {
                    wv.dispatchTouchEvent(
                        MotionEvent.obtain(t, t + durationMs, MotionEvent.ACTION_UP, x, y, 0)
                    )
                } finally {
                    latch.countDown()
                }
            }, durationMs)
        }
        latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val steps = 24
        val latch = CountDownLatch(1)
        val t = SystemClock.uptimeMillis()
        uiRun { wv ->
            wv.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x1, y1, 0))
            var step = 1
            val move = object : Runnable {
                override fun run() {
                    if (step < steps) {
                        val f = step.toFloat() / steps
                        wv.dispatchTouchEvent(
                            MotionEvent.obtain(
                                t, t + (durationMs * f).toLong(), MotionEvent.ACTION_MOVE,
                                x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, 0
                            )
                        )
                        step++
                        main.postDelayed(this, durationMs / steps)
                    } else {
                        wv.dispatchTouchEvent(
                            MotionEvent.obtain(t, t + durationMs, MotionEvent.ACTION_UP, x2, y2, 0)
                        )
                        latch.countDown()
                    }
                }
            }
            main.postDelayed(move, durationMs / steps)
        }
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
    }

    override fun scroll(direction: String, amount: Float) {
        val width = uiResult { it.width }
        val height = uiResult { it.height }
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

        uiRun { wv ->
            val c0 = MotionEvent.PointerCoords().apply {
                x = centerX
                y = centerY - startDistance / 2f
                pressure = 1f
                size = 1f
            }
            wv.dispatchTouchEvent(
                MotionEvent.obtain(
                    t, t, MotionEvent.ACTION_DOWN, 1,
                    arrayOf(p0), arrayOf(c0),
                    0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0
                )
            )
            wv.dispatchTouchEvent(
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
                        wv.dispatchTouchEvent(
                            twoPointer(t + (durationMs * f).toLong(), MotionEvent.ACTION_MOVE, distance)
                        )
                        step++
                        main.postDelayed(this, durationMs / steps)
                    } else {
                        wv.dispatchTouchEvent(
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
                        wv.dispatchTouchEvent(
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
            main.postDelayed(move, durationMs / steps)
        }
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------------
    // Teclado
    // ------------------------------------------------------------------

    override fun typeText(text: String): String = unwrapJsString(
        evalJs(
            "(function(){var el=document.activeElement;" +
                "if(!el)return'no focused element';" +
                "var v=${JSONObject.quote(text)};" +
                "if(el.isContentEditable){el.textContent=v;}" +
                "else if(el.value!==undefined){var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                "var d=Object.getOwnPropertyDescriptor(proto,'value');" +
                "if(d&&d.set)d.set.call(el,v);else el.value=v;}" +
                "else return'no focused input';" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                "return'ok';})()"
        )
    )

    override fun pressKey(key: String, times: Int): String {
        val repeats = times.coerceIn(1, 50)
        val events = keyEvents(key) ?: return "unknown key: $key"
        // Sem elemento focado os eventos vão para o <body>: handlers de
        // documento ainda funcionam, mas digitar num campo não.
        val focused = unwrapJsString(
            evalJs("(function(){var e=document.activeElement;" +
                "if(!e||e===document.body)return'none';" +
                "return e.tagName+(e.id?'#'+e.id:'');})()")
        )
        uiRun { wv ->
            wv.requestFocus()
            repeat(repeats) {
                val base = SystemClock.uptimeMillis()
                events.forEachIndexed { i, ev ->
                    wv.dispatchKeyEvent(
                        KeyEvent(
                            base, base + i * 5L, ev.action, ev.keyCode, ev.repeatCount,
                            ev.metaState, ev.deviceId, ev.scanCode, ev.flags, ev.source
                        )
                    )
                }
            }
        }
        return if (focused == "none") {
            "ok (no focused element: keys went to the page; use eruda_click_selector or eruda_fill first)"
        } else {
            "ok ($focused)"
        }
    }

    /** KeyEvents reais para teclas nomeadas ou um único caractere. */
    private fun keyEvents(key: String): Array<KeyEvent>? {
        val named = keyCodeFor(key.uppercase())
        if (named != null) {
            val t = SystemClock.uptimeMillis()
            return arrayOf(
                KeyEvent(t, t, KeyEvent.ACTION_DOWN, named, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
                KeyEvent(t, t + 5, KeyEvent.ACTION_UP, named, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            )
        }
        if (key.length == 1) {
            val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = map.getEvents(key.toCharArray())
            if (events != null && events.isNotEmpty()) return events
        }
        return null
    }

    private fun keyCodeFor(name: String): Int? = when (name) {
        "ENTER", "RETURN" -> KeyEvent.KEYCODE_ENTER
        "TAB" -> KeyEvent.KEYCODE_TAB
        "ESC", "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
        "BACKSPACE" -> KeyEvent.KEYCODE_DEL
        "DELETE", "DEL" -> KeyEvent.KEYCODE_FORWARD_DEL
        "SPACE" -> KeyEvent.KEYCODE_SPACE
        "UP", "ARROWUP" -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN", "ARROWDOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        "LEFT", "ARROWLEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT", "ARROWRIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "HOME" -> KeyEvent.KEYCODE_MOVE_HOME
        "END" -> KeyEvent.KEYCODE_MOVE_END
        "PAGEUP" -> KeyEvent.KEYCODE_PAGE_UP
        "PAGEDOWN" -> KeyEvent.KEYCODE_PAGE_DOWN
        else -> null
    }

    // ------------------------------------------------------------------
    // Helpers JS (click por seletor, hover, fill, wait)
    // ------------------------------------------------------------------

    override fun clickSelector(selector: String): String = unwrapJsString(
        evalJs(
            "(function(){var el=document.querySelector(${JSONObject.quote(selector)});" +
                "if(!el)return'not found';" +
                "el.scrollIntoView({block:'center'});" +
                "el.click();" +
                "try{if(typeof el.focus==='function')el.focus({preventScroll:true});}catch(e){}" +
                "return'clicked';})()"
        )
    )

    override fun hover(selector: String): String = unwrapJsString(evalJs(hoverJs(selector)))

    private fun hoverJs(selector: String): String =
        "(function(){var el=document.querySelector(${JSONObject.quote(selector)});" +
            "if(!el)return'not found';" +
            "el.scrollIntoView({block:'center'});" +
            "var r=el.getBoundingClientRect();" +
            "var cx=r.x+r.width/2,cy=r.y+r.height/2;" +
            "['pointerover','mouseover','mousemove','pointerenter','mouseenter']" +
            ".forEach(function(t){el.dispatchEvent(new MouseEvent(t,{bubbles:t!=='pointerenter'&&t!=='mouseenter',cancelable:true,clientX:cx,clientY:cy}));});" +
            "return'hovered';})()"

    override fun fill(selector: String, text: String): String = unwrapJsString(
        evalJs(
            "(function(){var el=document.querySelector(${JSONObject.quote(selector)});" +
                "if(!el)return'not found';" +
                "el.scrollIntoView({block:'center'});" +
                "var v=${JSONObject.quote(text)};" +
                "if(el.isContentEditable){el.textContent=v;}" +
                "else{var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                "var d=Object.getOwnPropertyDescriptor(proto,'value');" +
                "if(d&&d.set)d.set.call(el,v);else el.value=v;}" +
                "if(typeof el.focus==='function')el.focus();" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                "return'ok';})()"
        )
    )

    override fun waitFor(selector: String, timeoutMs: Long, visible: Boolean): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceIn(0, 60_000)
        val check = "(function(){var el=document.querySelector(${JSONObject.quote(selector)});" +
            "if(!el)return'waiting';" +
            (if (visible) {
                "var r=el.getBoundingClientRect();var s=getComputedStyle(el);" +
                    "if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return'waiting';" +
                    "if(r.width<=0||r.height<=0)return'waiting';return'visible';" +
                    "})()"
            } else {
                "return'found';})()"
            })
        while (true) {
            val state = unwrapJsString(evalJs(check))
            if (state == "visible") return "visible"
            if (!visible && state == "found") return "found"
            if (SystemClock.uptimeMillis() >= deadline) {
                throw IllegalStateException("timeout waiting for $selector")
            }
            Thread.sleep(250)
        }
    }

    override fun domQuery(selector: String, limit: Int): String {
        val raw = evalJs(
            "(function(){var els=document.querySelectorAll(${JSONObject.quote(selector)});" +
                "var out=[];var n=Math.min(els.length,$limit);var dpr=window.devicePixelRatio||1;" +
                "for(var i=0;i<n;i++){var el=els[i];var r=el.getBoundingClientRect();" +
                "out.push({tag:el.tagName,text:(el.textContent||'').trim().slice(0,120)," +
                "rect:{x:r.x,y:r.y,width:r.width,height:r.height}," +
                // Pixels de device: é o que eruda_tap/eruda_click/eruda_screenshot usam.
                "rectDevice:{x:Math.round(r.x*dpr),y:Math.round(r.y*dpr)," +
                "width:Math.round(r.width*dpr),height:Math.round(r.height*dpr)}});}" +
                "return JSON.stringify(out);})()"
        )
        return unwrapJsString(raw)
    }

    // ------------------------------------------------------------------
    // Navegação / página
    // ------------------------------------------------------------------

    override fun evalJs(script: String): String {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        uiRun { wv ->
            wv.evaluateJavascript(script) { value ->
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

    override fun navigate(url: String) = uiRun { it.loadUrl(url) }

    override fun back() = uiRun { if (it.canGoBack()) it.goBack() }

    override fun forward() = uiRun { if (it.canGoForward()) it.goForward() }

    override fun reload() = uiRun { it.reload() }

    override fun pageInfo(): String = uiResult { wv ->
        val url = wv.url ?: ""
        JSONObject()
            .put("url", if (url.isEmpty()) JSONObject.NULL else url)
            .put("title", wv.title ?: JSONObject.NULL)
            .put("secure", url.startsWith("https://", ignoreCase = true))
            .toString()
    }

    override fun consoleLogs(limit: Int): String = dump(consoleDeck, limit)

    override fun networkRequests(limit: Int): String = dump(networkDeck, limit)

    override fun clearSiteData() = uiRun { wv ->
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
        wv.clearCache(true)
        wv.reload()
    }
}
