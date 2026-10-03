package io.liriliri.eruda.mcp

import org.json.JSONArray
import org.json.JSONObject
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession

import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.Method
import java.io.IOException

/**
 * Servidor MCP (Streamable HTTP, JSON-RPC 2.0) expondo o WebView do app
 * para ferramentas de IA (opencode, Claude Code, ...).
 *
 * Autenticação: bearer token estático (fase 1).
 */
class McpServer(
    port: Int,
    private val token: String,
    val bridge: McpBridge?
) : NanoHTTPD(port) {

    val listenPort: Int = port

    companion object {
        private val SUPPORTED_VERSIONS = listOf(
            "2025-03-26",
            "2025-06-18",
            "2025-11-25",
            "2026-07-28"
        )
        private const val SERVER_VERSION = "2025-06-18"
        private const val SERVER_NAME = "eruda"
        private const val APP_VERSION = "1.2.0"
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            serveInternal(session)
        } catch (e: Exception) {
            McpServerManager.log("serve failed: ${e.message}")
            val envelope = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", JSONObject.NULL)
                .put(
                    "error", JSONObject()
                        .put("code", -32603)
                        .put("message", "InternalError: ${e.message}")
                        .put("data", "InternalError")
                )
            NanoHTTPD.newFixedLengthResponse(
                Status.INTERNAL_ERROR, "application/json", envelope.toString()
            )
        }
    }

    private fun serveInternal(session: IHTTPSession): Response {
        McpServerManager.log("${session.method} ${session.uri}")
        // OAuth 2.1 (metadata, registro, aprovação, token) tem rotas
        // próprias com métodos próprios — antes do gate POST-only do /mcp.
        McpOAuth.handle(session, this)?.let { return it }
        if (Method.POST != session.method) {
            return json(Status.METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}")
        }
        if (session.uri != "/mcp") {
            return json(Status.NOT_FOUND, "{\"error\":\"not found\"}")
        }

        val presented = bearerCredential(header(session, "authorization") ?: "")
        if (!McpServerManager.isAuthorized(token, presented)) {
            McpServerManager.log("unauthorized request")
            val res = json(Status.UNAUTHORIZED, "{\"error\":\"unauthorized\"}")
            res.addHeader(
                "WWW-Authenticate",
                "Bearer realm=\"$SERVER_NAME\", " +
                    "resource_metadata=\"${oauthBase(session)}/.well-known/oauth-protected-resource\""
            )
            return res
        }

        val origin = header(session, "origin")
        if (origin != null && !isAllowedOrigin(origin)) {
            return json(Status.FORBIDDEN, "{\"error\":\"forbidden origin\"}")
        }

        val protocolHeader = header(session, "mcp-protocol-version")
        if (protocolHeader != null && protocolHeader !in SUPPORTED_VERSIONS) {
            return rpcError(null, -32001, "UnsupportedProtocolVersionError",
                "Supported: $SUPPORTED_VERSIONS")
        }

        val body = readBody(session)
        val request = try {
            JSONObject(body)
        } catch (e: Exception) {
            return rpcError(null, -32700, "ParseError", "Invalid JSON")
        }

        return try {
            handleRpc(request)
        } catch (e: RpcError) {
            rpcError(request.opt("id"), e.code, e.name, e.message)
        } catch (e: Exception) {
            McpServerManager.log("tools/call error: ${e.message}")
            rpcError(request.opt("id"), -32603, "InternalError", e.message ?: "internal error")
        }
    }

    private fun handleRpc(request: JSONObject): Response {
        val method = request.optString("method")
        val id = request.opt("id")
        val params = request.optJSONObject("params") ?: JSONObject()

        if (!request.has("id") || request.isNull("id")) {
            // notifications/* são aceitas silenciosamente (202 Accepted).
            return NanoHTTPD.newFixedLengthResponse(Status.ACCEPTED, "application/json", null)
        }

        val result = when (method) {
            "initialize" -> initialize(params)
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", toolList())
            "tools/call" -> callTool(params)
            else -> throw RpcError(-32601, "MethodNotFound", "Unknown method: $method")
        }

        return rpcResult(id, result)
    }

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        val version = if (requested in SUPPORTED_VERSIONS) requested else SERVER_VERSION
        McpServerManager.log("initialize ($version)")
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", APP_VERSION))
            .put(
                "instructions",
                "Eruda browser MCP: control the in-app WebView to test mobile websites. " +
                    "Coordinates are in screenshot pixels (top-left origin)."
            )
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        McpServerManager.log("tools/call $name")

        val text = try {
            runTool(name, args)
        } catch (e: Exception) {
            return errorResult(e.message ?: "tool failed")
        }
        McpServerManager.log("tools/call $name -> ${text.length} chars")
        return if (name == "eruda_screenshot") imageResult(text) else textResult(text)
    }

    private fun runTool(name: String, args: JSONObject): String {
        // Sem navegador (serviço reiniciado pelo sistema sem activity):
        // ping/list funcionam, ferramentas de página falham com mensagem clara.
        val bridge = bridge
            ?: throw IllegalStateException("browser not open - open the Eruda app once")
        return when (name) {
        "eruda_screenshot" -> bridge.screenshotBase64()
        "eruda_tap" -> {
            bridge.tap(args.getDouble("x").toFloat(), args.getDouble("y").toFloat())
            "ok"
        }
        "eruda_press" -> {
            bridge.press(
                args.getDouble("x").toFloat(), args.getDouble("y").toFloat(),
                args.optLong("durationMs", 600)
            )
            "ok"
        }
        "eruda_swipe" -> {
            bridge.swipe(
                args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat(),
                args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat(),
                args.optLong("durationMs", 300)
            )
            "ok"
        }
        "eruda_scroll" -> {
            bridge.scroll(
                args.optString("direction", "down"),
                args.optDouble("amount", 1.0).toFloat()
            )
            "ok"
        }
        "eruda_pinch" -> {
            bridge.pinch(
                args.optDouble("centerX", 540.0).toFloat(),
                args.optDouble("centerY", 1200.0).toFloat(),
                args.getDouble("startDistance").toFloat(),
                args.getDouble("endDistance").toFloat(),
                args.optLong("durationMs", 300)
            )
            "ok"
        }
        "eruda_type" -> bridge.typeText(args.getString("text"))
        "eruda_click_selector" -> bridge.clickSelector(args.getString("selector"))
        "eruda_dom_query" -> bridge.domQuery(args.getString("selector"), args.optInt("limit", 10))
        "eruda_eval_js" -> bridge.evalJs(args.getString("script"))
        "eruda_navigate" -> {
            bridge.navigate(args.getString("url"))
            "ok"
        }
        "eruda_back" -> {
            bridge.back()
            "ok"
        }
        "eruda_forward" -> {
            bridge.forward()
            "ok"
        }
        "eruda_page_info" -> bridge.pageInfo()
        "eruda_console_logs" -> bridge.consoleLogs(args.optInt("limit", 20))
        "eruda_network_requests" -> bridge.networkRequests(args.optInt("limit", 20))
        "eruda_clear_site_data" -> {
            bridge.clearSiteData()
            "ok"
        }
        else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    // ---------------------------------------------------------------------
    // Tool registry
    // ---------------------------------------------------------------------

    private fun toolList(): JSONArray = JSONArray(
        listOf(
            tool(
                "eruda_screenshot",
                "Captures the current WebView page as a PNG image. Use it to see the page state.",
                schema()
            ),
            tool(
                "eruda_tap",
                "Taps the screen at the given coordinates (screenshot pixels, top-left origin).",
                schema(
                    num("x", "X coordinate", required = true),
                    num("y", "Y coordinate", required = true)
                )
            ),
            tool(
                "eruda_press",
                "Presses and holds (long press) at the given coordinates.",
                schema(
                    num("x", "X coordinate", required = true),
                    num("y", "Y coordinate", required = true),
                    num("durationMs", "How long to hold, default 600")
                )
            ),
            tool(
                "eruda_swipe",
                "Swipes from (x1,y1) to (x2,y2) in screenshot pixels.",
                schema(
                    num("x1", "Start X", required = true),
                    num("y1", "Start Y", required = true),
                    num("x2", "End X", required = true),
                    num("y2", "End Y", required = true),
                    num("durationMs", "Swipe duration, default 300")
                )
            ),
            tool(
                "eruda_scroll",
                "Scrolls the page in a direction. 'down' moves the content down (finger swipes up).",
                schema(
                    str("direction", "up|down|left|right", required = true),
                    num("amount", "Multiplier of default distance, default 1")
                )
            ),
            tool(
                "eruda_pinch",
                "Two-finger pinch around a center point. endDistance > startDistance zooms in; " +
                    "smaller zooms out. Distances in pixels.",
                schema(
                    num("startDistance", "Initial finger distance in px", required = true),
                    num("endDistance", "Final finger distance in px", required = true),
                    num("centerX", "Center X, default 540"),
                    num("centerY", "Center Y, default 1200"),
                    num("durationMs", "Duration, default 300")
                )
            ),
            tool(
                "eruda_type",
                "Types text into the currently focused input.",
                schema(str("text", "Text to type", required = true))
            ),
            tool(
                "eruda_click_selector",
                "Finds the first element matching a CSS selector, scrolls it into view and clicks it.",
                schema(str("selector", "CSS selector", required = true))
            ),
            tool(
                "eruda_dom_query",
                "Returns tag, visible text and bounding rect of elements matching a CSS selector.",
                schema(
                    str("selector", "CSS selector", required = true),
                    num("limit", "Max elements, default 10")
                )
            ),
            tool(
                "eruda_eval_js",
                "Evaluates a JavaScript expression and returns its value.",
                schema(str("script", "JavaScript to evaluate", required = true))
            ),
            tool(
                "eruda_navigate",
                "Loads a URL in the WebView.",
                schema(str("url", "URL to load", required = true))
            ),
            tool("eruda_back", "Goes back in browser history.", schema()),
            tool("eruda_forward", "Goes forward in browser history.", schema()),
            tool(
                "eruda_page_info",
                "Returns the current URL, page title and whether the connection is HTTPS.",
                schema()
            ),
            tool(
                "eruda_console_logs",
                "Returns recent console messages captured from the page.",
                schema(num("limit", "Max entries, default 20"))
            ),
            tool(
                "eruda_network_requests",
                "Returns recent network requests made by the page.",
                schema(num("limit", "Max entries, default 20"))
            ),
            tool(
                "eruda_clear_site_data",
                "Clears WebView cache, cookies and storage, then reloads the page.",
                schema()
            )
        ).map { (name, description, inputSchema) ->
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", inputSchema)
        }
    )

    private fun tool(
        name: String,
        description: String,
        inputSchema: JSONObject
    ): Triple<String, String, JSONObject> = Triple(name, description, inputSchema)

    private fun schema(vararg props: Prop): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        props.forEach { prop ->
            properties.put(prop.name, prop.schema)
            if (prop.required) required.put(prop.name)
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.length() > 0) schema.put("required", required)
        return schema
    }

    private class Prop(
        val name: String,
        val schema: JSONObject,
        val required: Boolean
    )

    private fun num(name: String, description: String, required: Boolean = false) =
        Prop(name, JSONObject().put("type", "number").put("description", description), required)

    private fun str(name: String, description: String, required: Boolean = false) =
        Prop(name, JSONObject().put("type", "string").put("description", description), required)

    // ---------------------------------------------------------------------
    // Response helpers
    // ---------------------------------------------------------------------

    private fun textResult(text: String): JSONObject =
        JSONObject().put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", text))
        )

    private fun imageResult(base64Png: String): JSONObject =
        JSONObject().put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "image")
                    .put("data", base64Png)
                    .put("mimeType", "image/png")
            )
        )

    private fun errorResult(message: String): JSONObject =
        JSONObject()
            .put("isError", true)
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", message))
            )

    private fun rpcResult(id: Any?, result: JSONObject): Response {
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("result", result)
        envelope.put("id", id ?: JSONObject.NULL)
        return json(Status.OK, envelope.toString())
    }

    private fun rpcError(id: Any?, code: Int, name: String, message: String): Response {
        val error = JSONObject()
            .put("code", code)
            .put("message", message)
            .put("data", name)
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("error", error)
        envelope.put("id", id ?: JSONObject.NULL)
        return json(Status.OK, envelope.toString())
    }

    private fun json(status: Status, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body)

    private fun readBody(session: IHTTPSession): String {
        // Caminho documentado do NanoHTTPD 2.x: parseBody() + postData.
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"]?.let { if (it.isNotEmpty()) return it }
        } catch (e: Exception) {
            McpServerManager.log("parseBody failed: ${e.message}")
        }
        // Fallback: leitura crua limitada pelo Content-Length.
        val length = (header(session, "content-length") ?: "0").toIntOrNull() ?: 0
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    /** Busca de header insensível a maiúsculas (NanoHTTPD nem sempre normaliza). */
    private fun header(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Extrai a credencial de "Bearer <cred>"; vazia se o esquema for outro. */
    private fun bearerCredential(auth: String): String {
        val prefix = "bearer "
        if (!auth.startsWith(prefix, ignoreCase = true)) return ""
        return auth.substring(prefix.length).trim()
    }

    /** Base pública das URLs OAuth: ecoa o Host de quem chamou. */
    private fun oauthBase(session: IHTTPSession): String {
        val host = header(session, "host")?.takeIf { it.isNotBlank() }
        return "http://$host".takeIf { host != null }
            ?: "http://127.0.0.1:$listenPort"
    }

    private fun isAllowedOrigin(origin: String): Boolean {
        val lower = origin.lowercase()
        return lower.startsWith("http://localhost") ||
            lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://[::1]")
    }

    private class RpcError(val code: Int, val name: String, override val message: String) :
        Exception(message)
}
