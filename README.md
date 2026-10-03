# eruda-android

A full-screen WebView browser that loads [eruda](https://github.com/liriliri/eruda) automatically on every page — now with an **on-device MCP server** so AI agents can drive the browser for mobile web testing.

Forked from [liriliri/eruda-android](https://github.com/liriliri/eruda-android). Current version: **2.0.0**.

## Browser features

- Eruda console auto-injected on every page (offline, served from assets)
- Floating action button + bottom dock (back, forward, refresh, bookmark, history, favorites, settings)
- History & Favorites with search, Site Info panel (ⓘ), clear cache / site data
- System / Light / Dark theme, rotation-safe (no page reload, FAB follows orientation)

## MCP server

Exposes the in-app WebView over Streamable HTTP (`POST /mcp`, JSON-RPC 2.0) with **17 tools**:

| Tool | What it does |
|---|---|
| `eruda_screenshot` | Page as PNG (MCP image block) |
| `eruda_tap` / `eruda_press` | Tap / long-press at screenshot pixels |
| `eruda_swipe` / `eruda_scroll` / `eruda_pinch` | Gestures, incl. two-finger zoom |
| `eruda_type` / `eruda_click_selector` / `eruda_dom_query` | Type, click and query via CSS selectors |
| `eruda_eval_js` | Evaluate JavaScript, return the value |
| `eruda_navigate` / `eruda_back` / `eruda_forward` | Navigation |
| `eruda_page_info` | URL, title, HTTPS status |
| `eruda_console_logs` / `eruda_network_requests` | Recent console messages / requests |
| `eruda_clear_site_data` | Clear cache, cookies, storage + reload |

### Authentication

Dual-accept, configured in **Settings → MCP Server**:

1. **Static bearer token** (copy / regenerate in the app).
2. **Self-contained OAuth 2.1** — Dynamic Client Registration + PKCE S256 + approval page with a **2-digit code**, confirmed via heads-up notification, system overlay popup, or natively in-app. Manage clients under *Authorized apps* (revoke anytime).

### Keeping it running

- Foreground service + WakeLock + persistent notification (with Stop action), so the server and WebView keep working with the screen off or the app swiped away.
- Optional **Start on device boot** toggle.
- Battery-optimization and overlay-permission shortcuts built into the MCP screen.
- Debug log at every request stage: `cache/mcp-debug.log` (readable via `run-as`).

### Connecting (opencode)

```bash
adb forward tcp:18790 tcp:18789   # host port can differ
```

Bearer (static) — `opencode.json`:

```json
{
  "mcp": {
    "eruda": {
      "type": "remote",
      "url": "http://localhost:18790/mcp",
      "headers": { "Authorization": "Bearer <token>" },
      "oauth": false
    }
  }
}
```

OAuth (2-digit approval) — with the forward active:

```bash
opencode mcp auth eruda
```

Approve in the browser window it opens, matching the code shown on the phone. If your client runs on the phone itself (e.g. Termux), the app can open the pairing link natively.

## Permissions

`INTERNET` · `WAKE_LOCK` · `FOREGROUND_SERVICE(_SPECIAL_USE)` · `POST_NOTIFICATIONS` · `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` · `SYSTEM_ALERT_WINDOW` (optional popup) · `RECEIVE_BOOT_COMPLETED` (optional auto-start).

## Build

```bash
sh ./gradlew :app:assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/eruda-v2.0.0-debug.apk
```

Requires Android SDK (compile/target 34, min 21), JDK 17+. See [CHANGELOG.md](CHANGELOG.md) for version history.

## Credits

- Original browser by [liriliri](https://github.com/liriliri) ([eruda-android](https://github.com/liriliri/eruda-android)).
- This fork is maintained by [isakii-dev](https://github.com/isakii-dev), developed on-device (Android phone) with [opencode](https://opencode.ai) (Muse Spark).
