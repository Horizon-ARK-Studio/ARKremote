package com.arklight.app

import android.annotation.SuppressLint
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exposed to the page as `window.AndroidBridge`. Every method here runs on the
 * WebView's JS-bridge thread, NOT the main thread -- so anything touching a
 * platform API that expects the main thread (or that we just want serialized)
 * hops back via mainHandler/hidManager's own internal handler.
 */
class WebAppBridge(
    private val activity: MainActivity,
    private val hid: BtHidManager,
) {
    @JavascriptInterface
    fun vibrate(ms: Long) {
        activity.runOnUiThread {
            val duration = ms.coerceIn(1, 200) // guard against a misbehaving page
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = activity.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = activity.getSystemService(Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(duration)
                }
            }
        }
    }

    /** buttonId is a logical control ID (POWER, VOL_UP, APP_NETFLIX, ...) -- every
     *  button MappingStore knows about is wired here, so every button on the remote
     *  produces a real HID report, not just the ones with an obvious usage code. */
    @JavascriptInterface
    fun sendKey(buttonId: String) {
        val target = MappingStore.get(activity, buttonId)
        when (target.kind) {
            HidKind.CONSUMER -> hid.sendConsumerKey(target.usage)
            HidKind.KEYBOARD -> hid.sendKeyboardUsage(target.usage)
        }
    }

    /** Opens the native remap picker for this button (long-press from the SPA). */
    @JavascriptInterface
    fun openMapper(buttonId: String) {
        activity.runOnUiThread { activity.showMapper(buttonId) }
    }

    /** JSON: { buttonId: { kind, usage, label }, ... } for every known button. */
    @JavascriptInterface
    fun currentMapping(): String = MappingStore.allAsJson(activity)

    @JavascriptInterface
    fun resetMapping(buttonId: String) {
        MappingStore.resetOne(activity, buttonId)
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun pairedDevices(): String {
        val arr = JSONArray()
        hid.bondedDevices().forEach { d ->
            val o = JSONObject()
            o.put("name", d.name ?: d.address)
            o.put("address", d.address)
            arr.put(o)
        }
        return arr.toString()
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun connectDevice(address: String) {
        val device = hid.bondedDevices().firstOrNull { it.address == address } ?: return
        hid.connect(device)
    }

    @JavascriptInterface
    fun connectionStatus(): String = if (hid.isConnected()) "connected" else "disconnected"
}
