# Bluetooth HID Device Profile

`BtHidManager` wraps `android.bluetooth.BluetoothHidDevice` (API 28+).
This is **not** BLE / HID-over-GATT — it's the same Classic-Bluetooth
profile a physical Bluetooth keyboard uses, negotiated over an SDP
record the app constructs itself.

## Prerequisite: pairing

The remote host (TV, PC, whatever accepts Bluetooth keyboards) must
already be **paired** via Android's own Bluetooth settings before this
app can do anything. Android will not silently pair a new device on an
app's behalf — that always requires the system pairing UI and explicit
user consent. What `BtHidManager` does is take an *already-bonded*
device and additionally negotiate the HID profile connection on top of
that existing bond.

## Connection flow

```
getProfileProxy(HID_DEVICE)
        │
        ▼
registerApp(sdpSettings, qos, executor, callback)
        │
        ▼
onAppStatusChanged(registered = true)
        │
        ▼
connect(bondedDevice)     ← tried for every bonded device (see below)
        │
        ▼
onConnectionStateChanged(STATE_CONNECTED)
        │
        ▼
sendReport(device, reportId, bytes)
```

`autoConnectBonded()` calls `connect()` on every device returned by
`bondedDevices()` once registration succeeds. There's no API to ask a
bonded device in advance whether it *supports* HID host, so this is
the closest thing to "just works once paired" the profile allows —
whichever host's stack accepts the request calls back via
`onConnectionStateChanged`; the rest are silently rejected or ignored
by their own OS.

## The report descriptor

`BtHidManager.REPORT_DESCRIPTOR` is a hand-assembled HID report
descriptor with two top-level `Application` collections, disambiguated
by Report ID so both share one SDP record:

| Report ID | Collection | Usage page | Purpose |
|---|---|---|---|
| 1 | Consumer Control | `0x0C` (Consumer) | Real dedicated usages: volume, mute, channel, power, menu, play/pause, AC Home, AC Back |
| 2 | Keyboard (boot-style) | `0x07` (Kbd/Keypad) | Arrow keys and Enter (real usages), plus F1–F12 used as generic "macro" slots |

Consumer Control usages exist for most of what a TV remote needs
directly (`KEY_VOL_UP`, `KEY_MUTE`, `KEY_HOME`, ...). Buttons with no
standard consumer usage — the TV/input toggle, the wrench, mic, gear,
options, and all six app-launch buttons — are instead mapped onto
F1–F12 keyboard usages. What F1–F12 *do* on the host is then the
host's own remap layer's job, exactly like a physical macro keypad:
HID has no "launch Netflix" usage to send.

Both report kinds are **array/level-triggered**, not one-shot: sending
a usage code holds that key "down" until a report with `0` overwrites
it. `sendConsumerKey` and `sendKeyboardUsage` both follow every "down"
report with a zeroed "up" report ~40ms later so a single tap on the
remote produces a single key press on the host rather than a held key.

## Remapping

`MappingStore` is the only place a logical button ID (`"POWER"`,
`"APP_NETFLIX"`, ...) is turned into a `HidTarget` (`kind` + `usage`).
Every physical control has a real factory-default entry — there is no
code path for a button ID with no target — and remaps persist to
`SharedPreferences` so they survive app restarts. See
[`ARCHITECTURE.md`](ARCHITECTURE.md#remapping-a-button) for how a
long-press reaches `MappingStore.set`.

## QoS

The registered QoS settings (`SERVICE_BEST_EFFORT`) are deliberately
conservative: this is a low-rate, human-triggered input device, not a
game controller, so there's no need to compete for low-latency channel
priority.
