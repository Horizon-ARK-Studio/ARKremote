package com.arklight.app

import android.content.Context
import org.json.JSONObject

enum class HidKind { CONSUMER, KEYBOARD }

data class HidTarget(val kind: HidKind, val usage: Int, val label: String)

/**
 * Every physical button on the remote has a stable logical ID (e.g. "APP_NETFLIX",
 * "VOL_UP"). This object is the ONLY place that decides which HID report a button ID
 * produces, persisted in SharedPreferences so remaps survive restarts.
 *
 * Two report kinds are wired (see BtHidManager.REPORT_DESCRIPTOR):
 *  - CONSUMER: report ID 1, 16-bit Consumer-Control usage -- used where a real,
 *    dedicated usage exists (volume, mute, channel, power, menu, play/pause,
 *    AC Home, AC Back).
 *  - KEYBOARD: report ID 2, 8-bit Keyboard/Keypad usage -- used for the D-pad/Enter
 *    (real Arrow-key usages) AND as generic F1-F12 "macro" slots for buttons with no
 *    standard consumer usage (TV/input toggle, wrench, mic, gear, options, all 6 app
 *    launchers). What F1-F12 *do* on the host is then the host's own remap layer's
 *    job -- exactly like a physical macro keypad; HID has no "launch Netflix" usage.
 */
object MappingStore {
    private const val PREFS = "hid_mapping"

    val ASSIGNABLE: List<HidTarget> = buildList {
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_POWER, "Power"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_MENU, "Menu"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_VOL_UP, "Volume Up"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_VOL_DOWN, "Volume Down"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_MUTE, "Mute"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_PLAY_PAUSE, "Play / Pause"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_CH_UP, "Channel Up"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_CH_DOWN, "Channel Down"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_HOME, "AC Home"))
        add(HidTarget(HidKind.CONSUMER, BtHidManager.KEY_BACK, "AC Back"))
        add(HidTarget(HidKind.KEYBOARD, 0x52, "Arrow Up"))
        add(HidTarget(HidKind.KEYBOARD, 0x51, "Arrow Down"))
        add(HidTarget(HidKind.KEYBOARD, 0x50, "Arrow Left"))
        add(HidTarget(HidKind.KEYBOARD, 0x4F, "Arrow Right"))
        add(HidTarget(HidKind.KEYBOARD, 0x28, "Enter"))
        for (i in 0..11) add(HidTarget(HidKind.KEYBOARD, 0x3A + i, "F${i + 1}"))
    }

    private fun find(kind: HidKind, usage: Int): HidTarget = ASSIGNABLE.first { it.kind == kind && it.usage == usage }

    // buttonId -> factory default. Every physical control gets a real entry here --
    // that's the "all buttons accessible" requirement enforced at the type level:
    // there is no code path for a button ID with no HidTarget.
    private val DEFAULTS: Map<String, HidTarget> = linkedMapOf(
        "POWER" to find(HidKind.CONSUMER, BtHidManager.KEY_POWER),
        "TV" to find(HidKind.KEYBOARD, 0x3A),          // F1
        "INPUT" to find(HidKind.KEYBOARD, 0x3B),       // F2
        "WRENCH" to find(HidKind.KEYBOARD, 0x3C),      // F3
        "MENU" to find(HidKind.CONSUMER, BtHidManager.KEY_MENU),
        "UP" to find(HidKind.KEYBOARD, 0x52),
        "DOWN" to find(HidKind.KEYBOARD, 0x51),
        "LEFT" to find(HidKind.KEYBOARD, 0x50),
        "RIGHT" to find(HidKind.KEYBOARD, 0x4F),
        "OK" to find(HidKind.KEYBOARD, 0x28),
        "BACK" to find(HidKind.CONSUMER, BtHidManager.KEY_BACK),
        "HOME" to find(HidKind.CONSUMER, BtHidManager.KEY_HOME),
        "VOL_UP" to find(HidKind.CONSUMER, BtHidManager.KEY_VOL_UP),
        "VOL_DOWN" to find(HidKind.CONSUMER, BtHidManager.KEY_VOL_DOWN),
        "MIC" to find(HidKind.KEYBOARD, 0x3D),         // F4
        "MUTE" to find(HidKind.CONSUMER, BtHidManager.KEY_MUTE),
        "CH_UP" to find(HidKind.CONSUMER, BtHidManager.KEY_CH_UP),
        "CH_DOWN" to find(HidKind.CONSUMER, BtHidManager.KEY_CH_DOWN),
        "GEAR" to find(HidKind.KEYBOARD, 0x3E),        // F5
        "PLAY_PAUSE" to find(HidKind.CONSUMER, BtHidManager.KEY_PLAY_PAUSE),
        "OPTIONS" to find(HidKind.KEYBOARD, 0x3F),     // F6
        // -- the 6 preset app-launch buttons --
        "APP_SONYLIV" to find(HidKind.KEYBOARD, 0x40), // F7
        "APP_NETFLIX" to find(HidKind.KEYBOARD, 0x41), // F8
        "APP_DISNEY" to find(HidKind.KEYBOARD, 0x42),  // F9
        "APP_PRIME" to find(HidKind.KEYBOARD, 0x43),   // F10
        "APP_YOUTUBE" to find(HidKind.KEYBOARD, 0x44), // F11
        "APP_MUSIC" to find(HidKind.KEYBOARD, 0x45),   // F12
    )

    val BUTTON_IDS: List<String> = DEFAULTS.keys.toList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, buttonId: String): HidTarget {
        val raw = prefs(context).getString(buttonId, null)
        if (raw != null) {
            val o = JSONObject(raw)
            val kind = if (o.getString("kind") == "CONSUMER") HidKind.CONSUMER else HidKind.KEYBOARD
            runCatching { return find(kind, o.getInt("usage")) }
        }
        return DEFAULTS[buttonId] ?: DEFAULTS.getValue("OK")
    }

    fun set(context: Context, buttonId: String, target: HidTarget) {
        val o = JSONObject().put("kind", target.kind.name).put("usage", target.usage)
        prefs(context).edit().putString(buttonId, o.toString()).apply()
    }

    fun resetOne(context: Context, buttonId: String) {
        prefs(context).edit().remove(buttonId).apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun allAsJson(context: Context): String {
        val o = JSONObject()
        BUTTON_IDS.forEach { id ->
            val t = get(context, id)
            o.put(id, JSONObject().put("kind", t.kind.name).put("usage", t.usage).put("label", t.label))
        }
        return o.toString()
    }
}
