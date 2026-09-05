# Architecture: How the App Initializes

## Key files

* **`app/src/main/java/com/arklight/app/MainActivity.kt`** — the whole
  app's entry point: creates the `WebView`, wires the asset loader,
  requests the Bluetooth permission, and owns the native remap dialog.
* **`app/src/main/java/com/arklight/app/WebAppBridge.kt`** — the
  `@JavascriptInterface` surface exposed to the page as
  `window.AndroidBridge`. The only way JS on the page talks to
  anything native.
* **`app/src/main/java/com/arklight/app/BtHidManager.kt`** — owns the
  Classic-Bluetooth HID Device profile connection and report sending.
  See [`BLUETOOTH-HID.md`](BLUETOOTH-HID.md) for the protocol-level
  detail.
* **`app/src/main/java/com/arklight/app/MappingStore.kt`** — the only
  place that decides which HID report a logical button ID (`POWER`,
  `APP_NETFLIX`, ...) produces, persisted in `SharedPreferences` so
  remaps survive restarts.
* **`app/src/main/assets/index.html`** / **`app.js`** / **`styles.css`**
  — the remote's own UI: an inline SVG remote face, served from
  `app/src/main/assets/` (this is `arklight build`'s output, not the
  scaffold-generated Kotlin above).

## Initialization flow

1. `MainActivity.onCreate` creates a `WebView`, enables JavaScript and
   DOM storage, and calls `setContentView(webView)` — the WebView
   fills the entire window; there's no separate native UI around it.
2. A `WebAppBridge` is attached as `window.AndroidBridge` via
   `addJavascriptInterface`, giving the page a narrow, explicit set of
   native calls (`sendKey`, `openMapper`, `currentMapping`, `vibrate`,
   ...) rather than open native access.
3. A `WebViewAssetLoader` is set up to serve `app/src/main/assets/`
   over `https://appassets.androidplatform.net/assets/`, so the page's
   own `fetch()`/`localStorage` behave the same way they would over
   plain HTTP — not like a `file://` page, which browsers sandbox
   differently.
4. `webView.loadUrl(SITE_URL)` loads that asset-loader URL.
5. `ensureBtPermissionThenStart()` checks for `BLUETOOTH_CONNECT`
   (API 31+ only — below that, `BLUETOOTH`/`BLUETOOTH_ADMIN` are
   install-time permissions and need no runtime prompt) and then
   starts `BtHidManager`.
6. Once the HID profile reports `"registered"`, `autoConnectBonded()`
   tries every already-bonded device — see
   [`BLUETOOTH-HID.md`](BLUETOOTH-HID.md) for why that's a "try them
   all" approach rather than picking exactly one.
7. Connection-state changes (`registered`, `connected:<name>`,
   `disconnected`, `bluetooth_off`, ...) are pushed back into the page
   via `window.onHidState`, evaluated on the WebView's main thread.

## From a tap to an HID report

1. The page calls `AndroidBridge.sendKey("VOL_UP")` (or whichever
   `data-hid` the tapped element carries).
2. `WebAppBridge.sendKey` asks `MappingStore.get(activity, buttonId)`
   for that button's current `HidTarget` (a `kind` + numeric `usage`
   pair — every button has one; there's no "unmapped" state).
3. Depending on `target.kind`, `BtHidManager.sendConsumerKey` or
   `sendKeyboardUsage` sends the report, immediately followed by a
   zeroed "key up" report ~40ms later.

## Remapping a button

1. Long-pressing a button on the page calls
   `AndroidBridge.openMapper("WRENCH")`.
2. `WebAppBridge.openMapper` hops to the UI thread and calls
   `MainActivity.showMapper`, which pops a native
   `AlertDialog.Builder(...).setSingleChoiceItems(...)` listing every
   entry in `MappingStore.ASSIGNABLE`, with the button's current
   assignment pre-selected.
3. Picking an entry calls `MappingStore.set`, which persists it to
   `SharedPreferences`, then `notifyRemap` evaluates
   `window.onRemapped(buttonId, label)` on the page so its UI can
   reflect the change immediately.

## Why the JS bridge is narrow

Everything `WebAppBridge` exposes takes a plain string or long and
returns a plain string — no objects, no callbacks passed across the
bridge. Every method runs on the WebView's own JS-bridge thread, not
the main thread, so anything that needs the main thread (dialogs,
`Vibrator`, `evaluateJavascript`) explicitly hops over via
`runOnUiThread`. Keeping the bridge to primitives-in, primitives-out
means the page never holds a live reference to a native object it
could call unexpected methods on.
