# ARKremote — Docs

**Branch:** `main`
**Scope:** the Android app itself — the WebView-based remote UI, the
Bluetooth HID Device peripheral it drives, and how the two are wired
together.

## Read this first

The repo root [`README.md`](../README.md) is the primary reference for
this project. It covers what `arklight android scaffold` generated,
how to build the app (with or without a local JDK), and what's safe to
hand-edit. Start there before these docs.

## What lives here

| Doc | Covers |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | How the app initializes: the WebView/asset-loader setup in `MainActivity`, the `AndroidBridge` JS↔native seam, and how a tap on the remote's SVG UI turns into an HID report |
| [`BLUETOOTH-HID.md`](BLUETOOTH-HID.md) | The Classic-Bluetooth HID Device profile this app implements: the hand-assembled report descriptor, the register → connect → send-report flow, and why the host must already be paired via Android's own Bluetooth settings |

## Non-goals of this docs tree

* Documenting `arklight build`'s own web/site pipeline — this app only
  consumes that pipeline's output (`app/src/main/assets/`); the
  pipeline itself is [ARKlight](https://github.com/Rae-ARK/ARKlight)'s
  concern, not this scaffold's.
* Re-explaining the CI workflow step by step — see the root
  `README.md`'s "Building without a local JDK" section for that; these
  docs cover the app's own runtime architecture instead.
