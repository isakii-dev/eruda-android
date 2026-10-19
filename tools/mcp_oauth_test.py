#!/usr/bin/env python3
"""End-to-end OAuth 2.1 test for the Eruda MCP server.

This script does exactly what ``opencode mcp auth <name>`` does when it
connects to an OAuth-protected MCP server:

  1. POST /mcp initialize                     -> 401 + WWW-Authenticate
  2. GET  /.well-known/oauth-protected-resource          (RFC 9728)
  3. GET  /.well-known/oauth-authorization-server        (RFC 8414)
  4. POST /oauth/register                     -> DCR       (RFC 7591)
  5. PKCE S256 + state + local loopback callback server
  6. GET  /oauth/authorize?...                (the "browser" step)
  7. approval:  --approve auto   -> POST /oauth/decision (simulated browser)
                --approve manual -> you approve on the phone / in the browser
  8. POST /oauth/token                        -> access + refresh tokens
  9. tools/list (+ a light tool call) with the access token
 10. refresh_token grant

Use ``--approve manual`` to *reproduce* the pairing UX: the app must show
exactly one approval popup carrying the 2-digit code printed below.

Only the Python standard library is required.

Examples::

    python3 tools/mcp_oauth_test.py                  # default: manual, one popup
    python3 tools/mcp_oauth_test.py --approve auto   # fully automated
    python3 tools/mcp_oauth_test.py --url http://127.0.0.1:18789/mcp
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

OK = "  \033[32m✔\033[0m"
BAD = "  \033[31m✘\033[0m"
INFO = "  \033[36m•\033[0m"


def log(msg: str) -> None:
    print(msg, flush=True)


def fail(msg: str) -> None:
    log(f"{BAD} {msg}")
    sys.exit(1)


def http(
    method: str,
    url: str,
    *,
    data: bytes | None = None,
    headers: dict | None = None,
    follow: bool = False,
    timeout: float = 15.0,
):
    """Minimal HTTP helper; returns (status, headers, body_bytes)."""
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    handlers = []
    if not follow:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **kw):  # noqa: D102
                return None

        handlers.append(NoRedirect)
    opener = urllib.request.build_opener(*handlers)
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()
    except urllib.error.URLError as e:
        fail(f"Cannot reach {url}: {e.reason}")


def json_http(method: str, url: str, payload=None, **kw) -> tuple[int, dict, object]:
    body = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    headers.update(kw.pop("headers", {}) or {})
    status, hdrs, raw = http(method, url, data=body, headers=headers, **kw)
    try:
        parsed = json.loads(raw.decode("utf-8", "replace") or "{}")
    except json.JSONDecodeError:
        parsed = raw.decode("utf-8", "replace")
    return status, hdrs, parsed


def form(values: dict) -> bytes:
    return urllib.parse.urlencode(values).encode()


class Callback:
    """One-shot loopback HTTP server that captures ?code=..&state=.."""

    def __init__(self, path: str = "/callback"):
        self.path = path
        self.port = 0
        self.code: str | None = None
        self.state: str | None = None
        self.error: str | None = None
        self.raw_query: str | None = None
        self._done = threading.Event()
        self._server = HTTPServer(("127.0.0.1", 0), self._handler())
        self.port = self._server.server_address[1]
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    def _handler(self):
        cb = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                parsed = urllib.parse.urlparse(self.path)
                if parsed.path != cb.path:
                    self.send_response(404)
                    self.end_headers()
                    return
                qs = urllib.parse.parse_qs(parsed.query)
                cb.raw_query = parsed.query
                cb.code = qs.get("code", [None])[0]
                cb.state = qs.get("state", [None])[0]
                cb.error = qs.get("error", [None])[0]
                cb._done.set()
                body = (
                    b"<html><body style='font-family:sans-serif'>"
                    b"<h2>Authorization received</h2><p>You can close this tab.</p>"
                    b"</body></html>"
                )
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):  # silence
                pass

        return Handler

    def wait(self, timeout: float) -> bool:
        return self._done.wait(timeout)

    def close(self):
        self._server.shutdown()
        self._server.server_close()


def pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
    return verifier, challenge


def extract_code_from_html(html: str) -> int | None:
    m = re.search(r'<div class="code">(\d+)</div>', html)
    return int(m.group(1)) if m else None


def extract_pending_id(html: str) -> str | None:
    m = re.search(r'name="id" value="([^"]+)"', html)
    return m.group(1) if m else None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://127.0.0.1:18789/mcp", help="MCP endpoint")
    ap.add_argument("--approve", choices=["manual", "auto"], default="manual",
                    help="manual: approve on the phone (reproduces the popup UX); auto: approve via decision endpoint")
    ap.add_argument("--name", default="mcp-oauth-test", help="client_name sent in DCR")
    ap.add_argument("--timeout", type=float, default=300.0, help="seconds to wait for manual approval")
    ap.add_argument("--no-tools", action="store_true", help="skip tools/list after token exchange")
    args = ap.parse_args()

    base = args.url[: -len("/mcp")] if args.url.endswith("/mcp") else args.url
    log(f"\n\033[1mEruda MCP OAuth test\033[0m  ->  {args.url}\n")

    # 1. unauthenticated request must yield 401 + resource_metadata -------
    log("[1/9] Unauthenticated /mcp initialize (expect 401)")
    status, hdrs, body = json_http("POST", args.url, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "oauth-test", "version": "1.0"}},
    })
    www = hdrs.get("WWW-Authenticate", "")
    if status != 401:
        fail(f"expected 401, got {status}: {body}")
    log(f"{OK} 401 as expected")
    log(f"{INFO} WWW-Authenticate: {www}")
    m = re.search(r'resource_metadata="([^"]+)"', www)
    if not m:
        fail("WWW-Authenticate has no resource_metadata parameter")
    resource_metadata_url = m.group(1)

    # 2. protected resource metadata --------------------------------------
    log("[2/9] Protected resource metadata (RFC 9728)")
    status, _, prm = json_http("GET", resource_metadata_url)
    if status != 200 or not isinstance(prm, dict):
        fail(f"bad protected resource metadata: {status} {prm}")
    log(f"{OK} resource={prm.get('resource')}")
    servers = prm.get("authorization_servers") or []
    if not servers:
        fail("no authorization_servers")
    issuer = servers[0]

    # 3. authorization server metadata ------------------------------------
    log("[3/9] Authorization server metadata (RFC 8414)")
    status, _, asm = json_http("GET", issuer.rstrip("/") + "/.well-known/oauth-authorization-server")
    if status != 200 or not isinstance(asm, dict):
        fail(f"bad AS metadata: {status} {asm}")
    authorize_ep = asm["authorization_endpoint"]
    token_ep = asm["token_endpoint"]
    register_ep = asm.get("registration_endpoint")
    log(f"{OK} issuer={asm.get('issuer')}")
    log(f"{INFO} authorize={authorize_ep}")
    log(f"{INFO} token={token_ep}")
    log(f"{INFO} register={register_ep}")
    if not register_ep:
        fail("server does not advertise a registration_endpoint (DCR)")

    # 4. dynamic client registration --------------------------------------
    log("[4/9] Dynamic Client Registration (RFC 7591)")
    callback = Callback()
    redirect_uri = f"http://127.0.0.1:{callback.port}/callback"
    status, _, reg = json_http("POST", register_ep, {
        "client_name": args.name,
        "redirect_uris": [redirect_uri],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
    })
    if status not in (200, 201) or not isinstance(reg, dict) or "client_id" not in reg:
        fail(f"DCR failed: {status} {reg}")
    client_id = reg["client_id"]
    client_secret = reg.get("client_secret", "")
    log(f"{OK} client_id={client_id}")
    log(f"{INFO} redirect_uri={redirect_uri}")

    # 5. PKCE + authorize URL ---------------------------------------------
    log("[5/9] Building authorization request (PKCE S256)")
    verifier, challenge = pkce()
    state = secrets.token_urlsafe(32)
    params = {
        "response_type": "code",
        "client_id": client_id,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "redirect_uri": redirect_uri,
        "state": state,
        "scope": "mcp:tools",
        "resource": args.url,  # RFC 8707, required by MCP 2025-06-18
    }
    auth_url = authorize_ep + "?" + urllib.parse.urlencode(params)
    log(f"{INFO} authorize URL:\n\n    {auth_url}\n")

    # 6. "browser" fetch of the authorize page ----------------------------
    log("[6/9] GET /oauth/authorize (this is when the app learns of the request)")
    if args.approve == "manual":
        log(f"{INFO} \033[1mOpen the URL above, or approve the popup on the phone.\033[0m")
        log(f"{INFO} Reproduction: the phone must show ONE popup with a 2-digit code.")
    status, _, raw = http("GET", auth_url)
    if status != 200:
        fail(f"authorize page failed: {status} {raw[:200]}")
    page = raw.decode("utf-8", "replace")
    code2 = extract_code_from_html(page)
    pending_id = extract_pending_id(page)
    log(f"{OK} pairing page received"
        + (f" (code on page: \033[1m{code2}\033[0m)" if code2 else ""))
    if args.approve == "manual":
        log(f"{INFO} waiting up to {int(args.timeout)}s for approval…")
        if not callback.wait(args.timeout):
            fail("timed out waiting for the loopback callback")
    else:
        if not pending_id:
            fail(f"could not find the pending id in the approve page:\n{page[:400]}")
        log(f"{INFO} approving via POST /oauth/decision (simulated browser, id={pending_id})")
        # follow=True replays the 302 redirect straight into the loopback callback
        status, _, raw = http(
            "POST", base + "/oauth/decision",
            data=form({"id": pending_id, "allow": "true"}),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            follow=True,
        )
        if not callback.wait(10):
            fail(f"callback not reached after decision (status {status})")

    callback.close()
    if callback.error:
        fail(f"authorization error: {callback.error}")
    if not callback.code:
        fail("no code received on the loopback callback")
    if callback.state != state:
        fail("state mismatch (possible CSRF)")
    log(f"{OK} authorization code received: {callback.code[:16]}…")

    # 7. token exchange ----------------------------------------------------
    log("[7/9] POST /oauth/token (authorization_code + PKCE)")
    status, hdrs, raw = http(
        "POST", token_ep,
        data=form({
            "grant_type": "authorization_code",
            "code": callback.code,
            "redirect_uri": redirect_uri,
            "client_id": client_id,
            "code_verifier": verifier,
            "resource": args.url,
        }),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        tok = json.loads(raw.decode())
    except Exception:
        fail(f"token endpoint returned non-JSON: {status} {raw[:200]}")
    if status != 200 or "access_token" not in tok:
        fail(f"token exchange failed: {status} {tok}")
    access = tok["access_token"]
    refresh = tok.get("refresh_token")
    log(f"{OK} access_token={access[:12]}… type={tok.get('token_type')} expires_in={tok.get('expires_in')}")
    if refresh:
        log(f"{OK} refresh_token={refresh[:12]}…")

    # 8. authenticated tool call ------------------------------------------
    if not args.no_tools:
        log("[8/9] Authenticated /mcp: initialize + tools/list + eruda_page_info")
        status, _, init = json_http("POST", args.url, {
            "jsonrpc": "2.0", "id": 3, "method": "initialize",
            "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                       "clientInfo": {"name": "oauth-test", "version": "1.0"}},
        }, headers={"Authorization": f"Bearer {access}"})
        if status != 200 or not isinstance(init, dict) or "result" not in init:
            fail(f"initialize with token failed: {status} {init}")
        protocol = init["result"].get("protocolVersion")
        server = init["result"].get("serverInfo", {})
        log(f"{OK} connected to {server.get('name')} v{server.get('version')} (protocol {protocol})")
        status, _, tools = json_http("POST", args.url, {
            "jsonrpc": "2.0", "id": 4, "method": "tools/list", "params": {},
        }, headers={"Authorization": f"Bearer {access}"})
        names = [t["name"] for t in tools.get("result", {}).get("tools", [])]
        log(f"{OK} {len(names)} tools: {', '.join(names[:6])}…")
        status, _, page = json_http("POST", args.url, {
            "jsonrpc": "2.0", "id": 5, "method": "tools/call",
            "params": {"name": "eruda_page_info", "arguments": {}},
        }, headers={"Authorization": f"Bearer {access}"})
        text = page.get("result", {}).get("content", [{}])[0].get("text", "")
        log(f"{OK} eruda_page_info -> {text[:120]}")
    else:
        log("[8/9] skipped (--no-tools)")

    # 9. refresh grant -----------------------------------------------------
    if refresh:
        log("[9/9] POST /oauth/token (refresh_token)")
        status, _, raw = http(
            "POST", token_ep,
            data=form({"grant_type": "refresh_token", "refresh_token": refresh,
                       "client_id": client_id, "resource": args.url}),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        try:
            refreshed = json.loads(raw.decode())
        except Exception:
            fail(f"refresh returned non-JSON: {status} {raw[:200]}")
        if status != 200 or "access_token" not in refreshed:
            fail(f"refresh failed: {status} {refreshed}")
        log(f"{OK} new access_token={refreshed['access_token'][:12]}…")
    else:
        log("[9/9] skipped (no refresh token)")

    log(f"\n{OK} \033[1;32mOAuth flow completed successfully\033[0m\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log("\ninterrupted")
        sys.exit(130)
