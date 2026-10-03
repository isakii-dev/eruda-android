package io.liriliri.eruda.mcp

/** Ponte entre o servidor MCP e o WebView da MainActivity. */
interface McpBridge {
    fun screenshotBase64(): String
    fun tap(x: Float, y: Float)
    fun press(x: Float, y: Float, durationMs: Long)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long)
    fun scroll(direction: String, amount: Float)
    fun pinch(
        centerX: Float, centerY: Float,
        startDistance: Float, endDistance: Float,
        durationMs: Long
    )
    fun typeText(text: String): String
    fun clickSelector(selector: String): String
    fun domQuery(selector: String, limit: Int): String
    fun evalJs(script: String): String
    fun navigate(url: String)
    fun back()
    fun forward()
    fun pageInfo(): String
    fun consoleLogs(limit: Int): String
    fun networkRequests(limit: Int): String
    fun clearSiteData()
}
