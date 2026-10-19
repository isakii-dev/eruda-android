package io.liriliri.eruda.mcp

import android.content.Context
import android.util.Base64 as AndroidBase64
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Servidor OAuth 2.1 próprio (self-contained), espelhando o fluxo do
 * android-remote-control-mcp: Dynamic Client Registration + página de
 * aprovação no navegador com código de 2 dígitos + popup nativo no
 * aparelho para conferir o código e aprovar (McpPairing).
 *
 * Fluxo com o opencode:
 * 1. `opencode mcp auth eruda` (com o adb forward ativo)
 * 2. opencode descobre via 401/resource_metadata, registra o cliente (DCR)
 * 3. abre o navegador em /oauth/authorize (chega ao app via forward)
 * 4. o aparelho mostra UM popup (dialog/overlay/notificação) com o MESMO
 *    código da página; Approve tenta entregar o code direto ao cliente —
 *    se o cliente roda no próprio aparelho, o fluxo fecha sem navegador
 * 5. opencode troca o code (PKCE S256) por access_token e guarda
 */
object McpOAuth {

    private const val PREFS = "eruda_oauth"
    private const val K_CLIENTS = "clients"
    private const val K_CODES = "codes"
    private const val K_TOKENS = "tokens"

    private const val CODE_TTL_MS = 5 * 60 * 1000L
    private const val PENDING_TTL_MS = 10 * 60 * 1000L
    private const val ACCESS_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val REFRESH_TTL_MS = 90L * 24 * 60 * 60 * 1000

    private var appContext: Context? = null
    private val random = SecureRandom()

    /** Aprovações pendentes (só em memória: reiniciar o processo invalida). */
    private data class Pending(
        val id: String,
        val code2: Int,
        val clientId: String,
        val clientName: String,
        val redirectUri: String,
        val state: String,
        val challenge: String,
        val expiresAt: Long
    )
    private val pendings = HashMap<String, Pending>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------
    // Roteamento (chamado pelo McpServer antes do gate POST-only do /mcp)
    // ------------------------------------------------------------------

    /** Retorna null quando a URI não é do OAuth (cai no fluxo JSON-RPC). */
    fun handle(session: IHTTPSession, server: McpServer): Response? {
        return when (session.uri) {
            "/.well-known/oauth-protected-resource" ->
                if (session.method == NanoHTTPD.Method.GET) protectedResourceMeta(session)
                else methodNotAllowed()
            "/.well-known/oauth-authorization-server" ->
                if (session.method == NanoHTTPD.Method.GET) authServerMeta(session)
                else methodNotAllowed()
            "/oauth/register" ->
                if (session.method == NanoHTTPD.Method.POST) register(server, session)
                else methodNotAllowed()
            "/oauth/authorize" ->
                if (session.method == NanoHTTPD.Method.GET) authorize(server, session)
                else methodNotAllowed()
            "/oauth/decision" -> when (session.method) {
                NanoHTTPD.Method.GET -> decisionPage(server, session)
                NanoHTTPD.Method.POST -> decide(server, session)
                else -> methodNotAllowed()
            }
            "/oauth/token" ->
                if (session.method == NanoHTTPD.Method.POST) token(server, session)
                else methodNotAllowed()
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Metadata (RFC 8414 / RFC 9728) — URLs ecoam o Host de quem chamou,
    // então funcionam tanto via adb forward quanto no navegador do celular.
    // ------------------------------------------------------------------

    private fun base(server: IHTTPSession): String {
        val host = server.headers.entries
            .firstOrNull { it.key.equals("host", ignoreCase = true) }?.value
            ?.takeIf { it.isNotBlank() }
        return "http://$host"
    }

    private fun protectedResourceMeta(session: IHTTPSession): Response {
        val b = base(session)
        return json(
            Status.OK, JSONObject()
                .put("resource", "$b/mcp")
                .put("authorization_servers", JSONArray().put(b))
                .put("scopes_supported", JSONArray().put("mcp:tools"))
                .put("bearer_methods_supported", JSONArray().put("header"))
                .toString()
        )
    }

    private fun authServerMeta(session: IHTTPSession): Response {
        val b = base(session)
        return json(
            Status.OK, JSONObject()
                .put("issuer", b)
                .put("authorization_endpoint", "$b/oauth/authorize")
                .put("token_endpoint", "$b/oauth/token")
                .put("registration_endpoint", "$b/oauth/register")
                .put("response_types_supported", JSONArray().put("code"))
                .put("grant_types_supported", JSONArray()
                    .put("authorization_code").put("refresh_token"))
                .put("code_challenge_methods_supported", JSONArray().put("S256"))
                .put("token_endpoint_auth_methods_supported", JSONArray()
                    .put("none").put("client_secret_post"))
                .toString()
        )
    }

    // ------------------------------------------------------------------
    // Dynamic Client Registration (RFC 7591)
    // ------------------------------------------------------------------

    private fun register(server: McpServer, session: IHTTPSession): Response {
        val body = try {
            readJsonBody(session)
        } catch (e: Exception) {
            return oauthError("invalid_request", "Body must be JSON")
        }
        val redirectUris = body.optJSONArray("redirect_uris") ?: JSONArray()
        if (redirectUris.length() == 0) {
            return oauthError("invalid_redirect_uri", "redirect_uris is required")
        }
        val uris = (0 until redirectUris.length()).map { redirectUris.getString(it) }
        if (uris.any { !it.startsWith("http://localhost") && !it.startsWith("http://127.0.0.1") }) {
            return oauthError("invalid_redirect_uri", "Only localhost redirect URIs allowed")
        }
        val clientId = "eruda-" + randomToken(12)
        val clientSecret = randomToken(24)
        val clients = loadMap(K_CLIENTS)
        prune(clients)
        if (clients.length() > 50) return oauthError("server_error", "Too many clients")
        clients.put(clientId, JSONObject()
            .put("secret", clientSecret)
            .put("redirect_uris", JSONArray(uris))
            .put("name", body.optString("client_name", "MCP client"))
            .put("created", System.currentTimeMillis()))
        saveMap(K_CLIENTS, clients)
        McpServerManager.log("OAuth client registered: $clientId")
        return json(
            Status.CREATED, JSONObject()
                .put("client_id", clientId)
                .put("client_secret", clientSecret)
                .put("redirect_uris", JSONArray(uris))
                .put("token_endpoint_auth_method", "none")
                .put("grant_types", JSONArray()
                    .put("authorization_code").put("refresh_token"))
                .put("response_types", JSONArray().put("code"))
                .toString()
        )
    }

    // ------------------------------------------------------------------
    // Authorize (GET) → página com código de 2 dígitos + notificação
    // ------------------------------------------------------------------

    private fun authorize(server: McpServer, session: IHTTPSession): Response {
        val q = query(session)
        if (q["response_type"] != "code") {
            return oauthError("unsupported_response_type", "Only code is supported")
        }
        val clientId = q["client_id"] ?: return oauthError("invalid_request", "Missing client_id")
        val redirectUri = q["redirect_uri"] ?: return oauthError("invalid_request", "Missing redirect_uri")
        val challenge = q["code_challenge"]
        if (q["code_challenge_method"] != null && q["code_challenge_method"] != "S256") {
            return oauthError("invalid_request", "Only S256 PKCE is supported")
        }
        if (challenge.isNullOrBlank()) {
            return oauthError("invalid_request", "code_challenge (S256) is required")
        }
        val clients = loadMap(K_CLIENTS)
        val client = clients.optJSONObject(clientId)
            ?: return oauthError("unauthorized_client", "Unknown client")
        val urisJson = client.optJSONArray("redirect_uris") ?: JSONArray()
        val allowed = (0 until urisJson.length()).map { urisJson.getString(it) }
        if (redirectUri !in allowed) {
            return oauthError("invalid_request", "redirect_uri not registered")
        }
        val id: String
        val code2: Int
        synchronized(pendings) {
            // Dedupe: mesmo cliente pedindo de novo (refresh do link, retry
            // do opencode) REUSA o pedido vivo — sem notificações e códigos
            // duplicados.
            val live = pendings.values.firstOrNull {
                it.clientId == clientId && it.expiresAt > System.currentTimeMillis()
            }
            if (live != null) {
                pendings[live.id] = live.copy(expiresAt = System.currentTimeMillis() + PENDING_TTL_MS)
                id = live.id
                code2 = live.code2
            } else {
                id = randomToken(16)
                code2 = 10 + random.nextInt(90)
                pendings[id] = Pending(
                    id, code2, clientId, client.optString("name", "MCP client"),
                    redirectUri, q["state"] ?: "", challenge,
                    System.currentTimeMillis() + PENDING_TTL_MS
                )
            }
            // Limpa expirados (e suas notificações órfãs + popups).
            val expired = pendings.values
                .filter { it.expiresAt <= System.currentTimeMillis() }
            expired.forEach { pendings.remove(it.id) }
            expired.forEach { McpPairing.dismiss(it.id) }
        }
        val clientName = client.optString("name", "MCP client")
        McpServerManager.log("OAuth pairing request $clientName: code $code2")
        appContext?.let { McpPairing.present(it, id, code2, clientId, clientName) }
        return html(Status.OK, approvalHtml(server, id, code2, clientName))
    }

    /**
     * Aprovação pelo próprio aparelho: emite o code e TENTA ENTREGÁ-LO
     * ao cliente com um GET no redirect_uri (OkHttp). Funciona quando o
     * cliente alcança o loopback daqui (Termux/chroot no mesmo aparelho);
     * noutra máquina o callback não é alcançável — o pedido é REINSERIDO
     * (o usuário aprova na janela do navegador que o cliente abriu).
     */
    fun approveAndDeliver(id: String): ApproveOutcome {
        val pending = synchronized(pendings) {
            pendings.remove(id)?.takeIf { it.expiresAt > System.currentTimeMillis() }
        } ?: return ApproveOutcome.EXPIRED
        val code = mintCode(pending)
        val sep = if ("?" in pending.redirectUri) "&" else "?"
        val url = "${pending.redirectUri}${sep}code=$code&state=${urlEncode(pending.state)}"
        val reached = tryDeliver(url)
        if (!reached) {
            // Reinsere: a página do navegador (com ?id=) ainda resolve.
            synchronized(pendings) { pendings[pending.id] = pending }
            McpServerManager.log("OAuth approved locally, callback unreachable (${pending.clientName})")
            return ApproveOutcome.UNREACHABLE
        }
        markApproved(pending.clientId)
        McpPairing.dismiss(pending.id)
        McpServerManager.log("OAuth approved and delivered from the app (${pending.clientName})")
        return ApproveOutcome.DELIVERED
    }

    /** GET no redirect_uri; qualquer resposta HTTP conta como entrega. */
    private fun tryDeliver(url: String): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                McpServerManager.log("OAuth callback delivery: HTTP ${resp.code}")
                true
            }
        } catch (e: Exception) {
            McpServerManager.log("OAuth callback delivery failed: ${e.message}")
            false
        }
    }

    /** Resultado de uma aprovação local. */
    enum class ApproveOutcome { DELIVERED, UNREACHABLE, EXPIRED }

    /**
     * Decisão direta (sem navegador), usada pelos popups.
     * Só o DENY funciona por aqui: o Approve passa por approveAndDeliver.
     */
    fun decideDirect(id: String, allow: Boolean): Boolean {
        if (allow) return false
        val pending = synchronized(pendings) { pendings.remove(id) } ?: return false
        McpPairing.dismiss(id)
        McpServerManager.log("OAuth pairing denied via popup (${pending.clientName})")
        return true
    }

    private fun decisionPage(server: McpServer, session: IHTTPSession): Response {
        val params = query(session)
        val id = params["id"] ?: return html(Status.BAD_REQUEST, errorHtml("Missing request id"))
        // Atalho de negação (ex.: botão Deny da notificação): resolve sem
        // precisar do formulário nem do redirect do navegador.
        if (params["deny"] == "1") {
            val pending = synchronized(pendings) { pendings.remove(id) }
            if (pending != null) {
                McpPairing.dismiss(id)
                McpServerManager.log("OAuth pairing denied (${pending.clientName})")
            }
            return html(Status.OK, errorHtml("Access denied. You can close this page."))
        }
        val pending = synchronized(pendings) { pendings[id] }
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?: return html(Status.BAD_REQUEST, errorHtml("Request expired, start over in your MCP client"))
        return html(Status.OK, approvalHtml(server, id, pending.code2, pending.clientName))
    }

    // ------------------------------------------------------------------
    // Decision (POST do formulário) → 302 para o redirect_uri
    // ------------------------------------------------------------------

    private fun decide(server: McpServer, session: IHTTPSession): Response {
        val form = form(session)
        val id = form["id"] ?: return html(Status.BAD_REQUEST, errorHtml("Missing request id"))
        val pending = synchronized(pendings) { pendings.remove(id) }
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?: return html(Status.BAD_REQUEST, errorHtml("Request expired, start over in your MCP client"))
        McpPairing.dismiss(id)
        if (form["allow"] != "true") {
            McpServerManager.log("OAuth pairing denied (${pending.clientName})")
            return redirect(pending.redirectUri, "error=access_denied&state=${urlEncode(pending.state)}")
        }
        markApproved(pending.clientId)
        val code = mintCode(pending)
        McpServerManager.log("OAuth pairing approved (${pending.clientName})")
        return redirect(
            pending.redirectUri,
            "code=$code&state=${urlEncode(pending.state)}"
        )
    }

    /** Cria um auth code (uso único, 5min) para um pedido aprovado. */
    private fun mintCode(pending: Pending): String {
        val codes = loadMap(K_CODES)
        prune(codes)
        val code = randomToken(24)
        codes.put(code, JSONObject()
            .put("client", pending.clientId)
            .put("redirect", pending.redirectUri)
            .put("challenge", pending.challenge)
            .put("exp", System.currentTimeMillis() + CODE_TTL_MS))
        saveMap(K_CODES, codes)
        return code
    }

    /** Resumo de um pedido para a UI nativa (dialog do app). */
    data class PendingInfo(val id: String, val code2: Int, val clientName: String)

    fun getPending(id: String): PendingInfo? =
        synchronized(pendings) { pendings[id] }
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.let { PendingInfo(it.id, it.code2, it.clientName) }

    // ------------------------------------------------------------------
    // Token (authorization_code com PKCE + refresh_token)
    // ------------------------------------------------------------------

    private fun token(server: McpServer, session: IHTTPSession): Response {
        val form = form(session)
        return when (form["grant_type"]) {
            "authorization_code" -> tokenFromCode(form)
            "refresh_token" -> tokenFromRefresh(form)
            else -> oauthError("unsupported_grant_type", "Use authorization_code or refresh_token")
        }
    }

    private fun tokenFromCode(form: Map<String, String>): Response {
        val code = form["code"] ?: return oauthError("invalid_request", "Missing code")
        val clientId = form["client_id"] ?: return oauthError("invalid_request", "Missing client_id")
        val verifier = form["code_verifier"] ?: return oauthError("invalid_request", "Missing code_verifier")
        val codes = loadMap(K_CODES)
        val entry = codes.optJSONObject(code)
            ?.takeIf { it.optLong("exp") > System.currentTimeMillis() }
            ?: return oauthError("invalid_grant", "Code invalid or expired")
        codes.remove(code) // uso único
        saveMap(K_CODES, codes)
        if (entry.optString("client") != clientId) {
            return oauthError("invalid_grant", "Client mismatch")
        }
        val redirect = form["redirect_uri"] ?: ""
        if (redirect.isNotBlank() && redirect != entry.optString("redirect")) {
            return oauthError("invalid_grant", "redirect_uri mismatch")
        }
        if (!verifyPkce(verifier, entry.optString("challenge"))) {
            McpServerManager.log("OAuth PKCE mismatch")
            return oauthError("invalid_grant", "PKCE verification failed")
        }
        val clients = loadMap(K_CLIENTS)
        val secret = clients.optJSONObject(clientId)?.optString("secret")
        val presented = form["client_secret"]
        if (!presented.isNullOrBlank() && presented != secret) {
            return oauthError("invalid_client", "Bad client_secret")
        }
        return issueTokens(clientId)
    }

    private fun tokenFromRefresh(form: Map<String, String>): Response {
        val refresh = form["refresh_token"] ?: return oauthError("invalid_request", "Missing refresh_token")
        val tokens = loadMap(K_TOKENS)
        prune(tokens)
        val entry = tokens.optJSONObject("rt:$refresh")
            ?.takeIf { it.optLong("exp") > System.currentTimeMillis() }
            ?: return oauthError("invalid_grant", "Refresh token invalid or expired")
        saveMap(K_TOKENS, tokens)
        return issueTokens(entry.optString("client"))
    }

    private fun issueTokens(clientId: String): Response {
        val tokens = loadMap(K_TOKENS)
        prune(tokens)
        val access = randomToken(32)
        val refresh = randomToken(32)
        val now = System.currentTimeMillis()
        tokens.put("at:$access", JSONObject()
            .put("client", clientId).put("exp", now + ACCESS_TTL_MS))
        tokens.put("rt:$refresh", JSONObject()
            .put("client", clientId).put("exp", now + REFRESH_TTL_MS))
        saveMap(K_TOKENS, tokens)
        touchLastUsed(clientId)
        McpServerManager.log("OAuth tokens issued ($clientId)")
        return json(
            Status.OK, JSONObject()
                .put("access_token", access)
                .put("token_type", "Bearer")
                .put("expires_in", ACCESS_TTL_MS / 1000)
                .put("refresh_token", refresh)
                .put("scope", "mcp:tools")
                .toString()
        )
    }

    /** Marca o cliente como aprovado (aparece como autorizado na lista). */
    private fun markApproved(clientId: String) {
        val clients = loadMap(K_CLIENTS)
        clients.optJSONObject(clientId)?.put("approved", true)
        saveMap(K_CLIENTS, clients)
    }

    private fun touchLastUsed(clientId: String) {
        val clients = loadMap(K_CLIENTS)
        clients.optJSONObject(clientId)?.put("lastUsed", System.currentTimeMillis())
        saveMap(K_CLIENTS, clients)
    }

    /** Aceito no /mcp junto do bearer estático (dual-accept). */
    fun isAccessToken(token: String): Boolean {
        if (token.isBlank()) return false
        val tokens = loadMap(K_TOKENS)
        val ok = tokens.optJSONObject("at:$token")
            ?.optLong("exp", 0) ?: 0 > System.currentTimeMillis()
        return ok
    }

    // ------------------------------------------------------------------
    // Clientes autorizados (lista + revogação, usados pela tela de config)
    // ------------------------------------------------------------------

    data class OAuthClient(
        val id: String,
        val name: String,
        val createdAt: Long,
        val approved: Boolean = false,
        val lastUsed: Long = 0
    )

    @Synchronized
    fun listClients(): List<OAuthClient> {
        val map = loadMap(K_CLIENTS)
        saveMap(K_CLIENTS, map) // prune + persiste
        return map.keys().asSequence().map { id ->
            val o = map.optJSONObject(id) ?: JSONObject()
            val name = o.optString("name", "").ifBlank { "MCP client" }
            OAuthClient(
                id, name, o.optLong("created", 0),
                o.optBoolean("approved", false), o.optLong("lastUsed", 0)
            )
        }.sortedByDescending { it.createdAt }.toList()
    }

    /** Resumo dos pedidos pendentes (para exibir o código dentro do app). */
    data class PairingSummary(val id: String, val code2: Int, val clientName: String)

    fun pendingSummaries(): List<PairingSummary> {
        val now = System.currentTimeMillis()
        return synchronized(pendings) {
            pendings.values.filter { it.expiresAt > now }
                .map { PairingSummary(it.id, it.code2, it.clientName) }
        }
    }

    @Synchronized
    fun revokeClient(clientId: String): Boolean {
        val clients = loadMap(K_CLIENTS)
        if (clients.optJSONObject(clientId) == null) return false
        clients.remove(clientId)
        saveMap(K_CLIENTS, clients)
        val codes = loadMap(K_CODES)
        codes.keys().asSequence().toList()
            .filter { codes.optJSONObject(it)?.optString("client") == clientId }
            .forEach { codes.remove(it) }
        saveMap(K_CODES, codes)
        val tokens = loadMap(K_TOKENS)
        tokens.keys().asSequence().toList()
            .filter { tokens.optJSONObject(it)?.optString("client") == clientId }
            .forEach { tokens.remove(it) }
        saveMap(K_TOKENS, tokens)
        McpServerManager.log("OAuth client revoked: $clientId")
        return true
    }

    // ------------------------------------------------------------------
    // Utilidades HTTP / cripto / storage
    // ------------------------------------------------------------------

    private fun query(session: IHTTPSession): Map<String, String> =
        session.parameters.mapValues { it.value.firstOrNull() ?: "" }

    private fun form(session: IHTTPSession): Map<String, String> {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            session.parms.mapValues { it.value ?: "" }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
            files["postData"]?.takeIf { it.isNotEmpty() }?.let { return JSONObject(it) }
        } catch (e: Exception) {
            // cai no fallback abaixo
        }
        val length = session.headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value?.toIntOrNull() ?: 0
        if (length <= 0 || length > 64 * 1024) throw IllegalArgumentException("empty body")
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return JSONObject(String(buf, 0, read))
    }

    private fun verifyPkce(verifier: String, challenge: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            val computed = AndroidBase64.encodeToString(
                digest, AndroidBase64.URL_SAFE or AndroidBase64.NO_PADDING or AndroidBase64.NO_WRAP
            )
            MessageDigest.isEqual(
                computed.toByteArray(Charsets.US_ASCII),
                challenge.toByteArray(Charsets.US_ASCII)
            )
        } catch (e: Exception) {
            false
        }
    }

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return AndroidBase64.encodeToString(
            b, AndroidBase64.URL_SAFE or AndroidBase64.NO_PADDING or AndroidBase64.NO_WRAP
        )
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun loadMap(key: String): JSONObject {
        return try {
            JSONObject(prefs()?.getString(key, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun saveMap(key: String, map: JSONObject) {
        prune(map)
        try {
            prefs()?.edit()?.putString(key, map.toString())?.apply()
        } catch (e: Exception) {
            // sem contexto ainda: mantém só em memória nesta chamada
        }
    }

    private fun prune(map: JSONObject) {
        val now = System.currentTimeMillis()
        val it = map.keys()
        while (it.hasNext()) {
            val k = it.next()
            val exp = map.optJSONObject(k)?.optLong("exp", Long.MAX_VALUE) ?: Long.MAX_VALUE
            if (exp < now) it.remove()
        }
    }

    private fun json(status: Status, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body)

    private fun html(status: Status, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/html; charset=utf-8", body)

    private fun oauthError(code: String, description: String): Response =
        json(
            Status.BAD_REQUEST, JSONObject()
                .put("error", code)
                .put("error_description", description)
                .toString()
        )

    private fun redirect(location: String, query: String): Response {
        val sep = if ("?" in location) "&" else "?"
        val res = NanoHTTPD.newFixedLengthResponse(Status.FOUND, "text/plain", "Redirecting…")
        res.addHeader("Location", "$location$sep$query")
        return res
    }

    private fun methodNotAllowed(): Response =
        NanoHTTPD.newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")

    private fun urlEncode(s: String): String = try {
        java.net.URLEncoder.encode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun approvalHtml(server: McpServer, id: String, code2: Int, clientName: String): String {
        val esc = clientName.replace("&", "&amp;").replace("<", "&lt;")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Eruda MCP pairing</title>
<style>body{font-family:sans-serif;background:#141414;color:#eee;text-align:center;padding:32px 16px}
.code{font-size:72px;font-weight:bold;letter-spacing:8px;margin:16px 0;color:#7CFC9A}
.card{max-width:420px;margin:0 auto;background:#222;border-radius:16px;padding:24px}
button{font-size:18px;border:0;border-radius:12px;padding:14px 0;width:100%;margin-top:12px}
.ok{background:#2e7d32;color:#fff}.no{background:#555;color:#fff}
small{color:#999}</style></head><body><div class="card">
<h2>Eruda MCP pairing</h2>
<p><b>$esc</b> wants to control this device.</p>
<p>Check the code on your phone (popup or notification):</p>
<div class="code">$code2</div>
<small>Only approve if the codes match. Client running on this phone?
The app's popup completes the setup by itself. Client on a computer?
Approve with the buttons below, in this browser window.</small>
<form method="post" action="/oauth/decision">
<input type="hidden" name="id" value="$id">
<input type="hidden" name="allow" value="true">
<button class="ok" type="submit">Approve</button></form>
<form method="post" action="/oauth/decision">
<input type="hidden" name="id" value="$id">
<input type="hidden" name="allow" value="false">
<button class="no" type="submit">Deny</button></form>
</div></body></html>"""
    }

    private fun errorHtml(message: String): String {
        val esc = message.replace("&", "&amp;").replace("<", "&lt;")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Eruda MCP</title></head>
<body style="font-family:sans-serif;background:#141414;color:#eee;text-align:center;padding:48px 16px">
<h2>$esc</h2></body></html>"""
    }
}
