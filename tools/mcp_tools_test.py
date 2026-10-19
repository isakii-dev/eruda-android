#!/usr/bin/env python3
"""Exercise every MCP tool against a built-in local test page.

The test page is served from this directory on a random loopback port, so
nothing else needs to be running. The static bearer token is read from
``--token`` or the ``ERUDA_MCP_TOKEN`` environment variable.

Keep the app's browser screen (MainActivity) in front while it runs: if
another Eruda screen is on top, the paused WebView cannot receive the
synthetic key and touch events (JS-based tools still work).

Usage:
    ERUDA_MCP_TOKEN=<token> python3 tools/mcp_tools_test.py
    python3 tools/mcp_tools_test.py --token <token> --url http://127.0.0.1:18789/mcp
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

OK = "  \033[32m✔\033[0m"
BAD = "  \033[31m✘\033[0m"
INFO = "  \033[36m•\033[0m"

DEFAULT_TOKEN = os.environ.get("ERUDA_MCP_TOKEN", "")
PAGE_FILE = Path(__file__).with_name("test-page.html")


class PageHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = PAGE_FILE.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def start_page_server() -> str:
    server = ThreadingHTTPServer(("127.0.0.1", 0), PageHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{server.server_address[1]}/"


class Mcp:
    def __init__(self, url: str, token: str):
        self.url = url
        self.token = token
        self.seq = 0

    def rpc(self, method: str, params=None):
        self.seq += 1
        body = json.dumps({
            "jsonrpc": "2.0", "id": self.seq, "method": method, "params": params or {},
        }).encode()
        req = urllib.request.Request(self.url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Mcp-Protocol-Version", "2025-11-25")
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode())

    def call(self, name: str, **arguments):
        out = self.rpc("tools/call", {"name": name, "arguments": arguments})
        if "error" in out:
            raise RuntimeError(f"{name}: {out['error']}")
        result = out["result"]
        text = "".join(
            c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"
        )
        if result.get("isError"):
            raise RuntimeError(f"{name}: {text}")
        return text

    def js(self, script: str):
        raw = self.call("eruda_eval_js", script=script)
        try:
            return json.loads(raw)
        except Exception:
            return raw


def check(label: str, cond: bool, detail: str = "") -> bool:
    print(f"{OK if cond else BAD} {label}" + (f" — {detail}" if detail else ""), flush=True)
    return cond


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:18789/mcp")
    ap.add_argument("--token", default=DEFAULT_TOKEN)
    ap.add_argument("--page", default=None, help="custom test page URL (default: built-in page)")
    args = ap.parse_args()

    if not args.token:
        print(f"{BAD} missing token: pass --token or set ERUDA_MCP_TOKEN")
        return 2

    page = args.page or start_page_server()
    m = Mcp(args.url, args.token)
    failures = 0

    print("\n\033[1mEruda MCP tools test\033[0m\n")

    init = m.rpc("initialize", {
        "protocolVersion": "2025-11-25", "capabilities": {},
        "clientInfo": {"name": "tools-test", "version": "1.0"},
    })
    check("initialize", "result" in init)
    tools = [t["name"] for t in m.rpc("tools/list")["result"]["tools"]]
    check(f"tools/list ({len(tools)} tools)", len(tools) >= 24)

    expected = {
        "eruda_click", "eruda_dblclick", "eruda_hover", "eruda_fill",
        "eruda_press_key", "eruda_wait_for", "eruda_reload",
    }
    missing = expected - set(tools)
    failures += 0 if check("new tools registered", not missing, ",".join(sorted(missing))) else 1

    m.call("eruda_navigate", url=page)
    time.sleep(2.5)
    info = json.loads(m.call("eruda_page_info"))
    failures += 0 if check("navigate", (info.get("url") or "") == page, info.get("url")) else 1

    # --- fill (React-style native setter + input event) -------------------
    m.call("eruda_fill", selector="#name", text="hello eruda")
    echo = m.js("document.getElementById('echo').textContent")
    failures += 0 if check("eruda_fill fires input event", "hello eruda" in str(echo), str(echo)) else 1
    value = m.js("document.getElementById('name').value")
    failures += 0 if check("eruda_fill sets value", value == "hello eruda", str(value)) else 1

    # --- press_key (real KeyEvents) --------------------------------------
    m.call("eruda_click_selector", selector="#search")
    time.sleep(0.4)
    for ch in "abc":
        m.call("eruda_press_key", key=ch)
    time.sleep(0.3)
    keys = m.js("document.getElementById('keylog').textContent")
    typed = m.js("document.getElementById('search').value")
    failures += 0 if check("eruda_press_key reaches keydown", "keydown" in str(keys), str(keys)) else 1
    failures += 0 if check("eruda_press_key inserts text", typed == "abc", repr(typed)) else 1

    m.call("eruda_press_key", key="Backspace")
    time.sleep(0.2)
    typed = m.js("document.getElementById('search').value")
    failures += 0 if check("eruda_press_key Backspace", typed == "ab", repr(typed)) else 1

    # --- click by coordinates --------------------------------------------
    rect = m.js(
        "(function(){var r=document.getElementById('btn').getBoundingClientRect();var d=window.devicePixelRatio||1;"
        "return JSON.stringify({x:Math.round((r.x+r.width/2)*d),y:Math.round((r.y+r.height/2)*d)});})()"
    )
    rect = json.loads(rect) if isinstance(rect, str) else rect
    before = m.js("document.getElementById('count').textContent")
    m.call("eruda_click", x=rect["x"], y=rect["y"])
    time.sleep(0.5)
    after = m.js("document.getElementById('count').textContent")
    failures += 0 if check("eruda_click at coordinates", before != after, f"{before} -> {after}") else 1

    # --- dom_query exposes device-pixel rects ----------------------------
    q = m.call("eruda_dom_query", selector="#btn", limit=1)
    q = json.loads(q) if isinstance(q, str) else q
    has_device = bool(q) and "rectDevice" in q[0]
    failures += 0 if check("eruda_dom_query rectDevice", has_device, str(q[0].get("rectDevice") if q else q)) else 1

    # --- double click -----------------------------------------------------
    rect = m.js(
        "(function(){var r=document.getElementById('dbl').getBoundingClientRect();var d=window.devicePixelRatio||1;"
        "return JSON.stringify({x:Math.round((r.x+r.width/2)*d),y:Math.round((r.y+r.height/2)*d)});})()"
    )
    rect = json.loads(rect) if isinstance(rect, str) else rect
    m.call("eruda_dblclick", x=rect["x"], y=rect["y"])
    time.sleep(0.6)
    dbls = m.js("document.getElementById('dblcount').textContent")
    failures += 0 if check("eruda_dblclick", "1" in str(dbls), str(dbls)) else 1

    # --- hover ------------------------------------------------------------
    m.call("eruda_hover", selector="#hoverme")
    time.sleep(0.3)
    open_state = m.js("document.getElementById('menu').className")
    failures += 0 if check("eruda_hover opens menu", "open" in str(open_state), str(open_state)) else 1

    # --- wait_for (element appears after 1.2s) ---------------------------
    m.call("eruda_click_selector", selector="#reveal")
    t0 = time.time()
    try:
        state = m.call("eruda_wait_for", selector="#hidden", timeoutMs=6000, visible=True)
        took = time.time() - t0
        failures += 0 if check("eruda_wait_for visible", state == "visible", f"{state} after {took:.1f}s") else 1
    except RuntimeError as e:
        failures += 1
        check("eruda_wait_for visible", False, str(e))

    # --- reload -----------------------------------------------------------
    m.call("eruda_reload")
    time.sleep(2.5)
    gone = m.js("document.getElementById('echo').textContent")
    failures += 0 if check("eruda_reload resets page", "hello eruda" not in str(gone), str(gone)) else 1

    # --- screenshot still works ------------------------------------------
    shot = m.rpc("tools/call", {"name": "eruda_screenshot", "arguments": {}})
    content = shot["result"]["content"][0]
    failures += 0 if check(
        "eruda_screenshot returns PNG", content.get("type") == "image" and len(content.get("data", "")) > 1000,
        f"{len(content.get('data',''))} b64 chars",
    ) else 1

    print()
    if failures:
        print(f"\033[31m{failures} failure(s)\033[0m\n")
        return 1
    print("\033[1;32mall tool checks passed\033[0m\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.URLError as e:
        print(f"{BAD} cannot reach server: {e}")
        sys.exit(2)
