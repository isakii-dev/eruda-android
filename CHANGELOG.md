## Unreleased

* feat: one approval popup per pairing request — in-app dialog when the app is in front, system overlay when it is behind, notification with Approve/Deny as fallback (no more overlay + heads-up at the same time)
* feat: approving on the phone now delivers the code to the client directly (GET on the redirect URI); unreachable callbacks keep the request pending with a clear message
* feat: notification actions Approve/Deny, working with the app closed
* feat: 7 new tools — `eruda_click`, `eruda_dblclick`, `eruda_hover`, `eruda_fill`, `eruda_press_key`, `eruda_wait_for`, `eruda_reload` (24 total)
* feat: `eruda_type`/`eruda_fill` use the native value setter, so React/Vue controlled inputs update
* feat: `eruda_dom_query` returns `rectDevice` in screenshot pixels (tap/click compatible)
* feat: `eruda_click_selector` focuses the element, making `eruda_press_key` land on the right field
* feat: headless WebView in the service — tools keep working with the app closed, same page, dropped when the app reopens
* fix: server no longer restarts when the activity is recreated; the bridge is resolved per call
* feat: `tools/mcp_oauth_test.py` and `tools/mcp_tools_test.py` for reproducible auth/tool tests
* docs: README with the full 24-tool table and OAuth troubleshooting (*Waiting approval* before the request, unreachable callback, stale clients)

## 2.0.0 (03 Oct 2026)

* feat: on-device MCP server (Streamable HTTP, 17 WebView tools)
* feat: self-contained OAuth 2.1 (DCR, PKCE S256, 2-digit on-device approval)
* feat: authorized-apps screen with revoke
* feat: foreground service keep-alive (WakeLock, status notification, boot auto-start)
* feat: pairing overlay popup, native loopback /oauth link handling
* feat: history/favorites redesign, settings screen, site info panel, clear cache
* feat: rotation-safe WebView (no reload) with FAB repositioning

## 1.2.0 (21 Dec 2023)

* feat: auto prepend http protocol
* feat: access localhost over http 

## 1.1.0 (30 Mar 2023)

* feat: dark mode
* feat: splashscreen
* fix: unable to login