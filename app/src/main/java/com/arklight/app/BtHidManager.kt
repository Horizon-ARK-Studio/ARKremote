package com.arklight.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * Wraps the classic-Bluetooth HID Device profile (android.bluetooth.BluetoothHidDevice,
 * API 28+). This is NOT BLE / HID-over-GATT -- it's the same profile a physical BT
 * keyboard uses, negotiated over an SDP record we construct byte-for-byte below.
 *
 * Flow: getProfileProxy(HID_DEVICE) -> registerApp(sdpSettings, ...) -> wait for
 * onAppStatusChanged(registered=true) -> connect(bondedDevice) -> wait for
 * onConnectionStateChanged(STATE_CONNECTED) -> sendReport(device, reportId, bytes).
 *
 * The remote host (TV/PC) must already be PAIRED via Android's Bluetooth settings.
 * Android will not silently pair a new device on an app's behalf -- that always
 * requires the system pairing UI and explicit user consent. What this class does
 * is take an *already-bonded* device and additionally negotiate the HID profile
 * connection on top of that bond.
 */
class BtHidManager(private val context: Context) {

    companion object {
        private const val TAG = "BtHidManager"

        // --- Report IDs (must match the descriptor below) ---
        private const val REPORT_ID_CONSUMER = 1
        private const val REPORT_ID_KEYBOARD = 2

        // --- Consumer Control usages (HID Usage Page 0x0C), 16-bit each ---
        const val KEY_POWER = 0x0030
        const val KEY_MENU = 0x0040
        const val KEY_VOL_UP = 0x00E9
        const val KEY_VOL_DOWN = 0x00EA
        const val KEY_MUTE = 0x00E2
        const val KEY_PLAY_PAUSE = 0x00CD
        const val KEY_CH_UP = 0x009C
        const val KEY_CH_DOWN = 0x009D
        const val KEY_HOME = 0x0223 // AC Home
        const val KEY_BACK = 0x0224 // AC Back

        /**
         * Raw HID report descriptor, hand-assembled from short items
         * (prefix byte = (tag<<4)|(type<<2)|size). Two top-level Application
         * collections, disambiguated by Report ID so both share one SDP record.
         */
        val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
            // ---- Collection 1: Consumer Control (report ID 1) ----
            0x05, 0x0C,                     // Usage Page (Consumer)
            0x09, 0x01,                     // Usage (Consumer Control)
            0xA1.toByte(), 0x01,             // Collection (Application)
            0x85.toByte(), REPORT_ID_CONSUMER.toByte(), //   Report ID (1)
            0x19, 0x00,                     //   Usage Minimum (0)
            0x2A, 0x3C, 0x02,                //   Usage Maximum (0x023C)
            0x15, 0x00,                     //   Logical Minimum (0)
            0x26, 0x3C, 0x02,                //   Logical Maximum (0x023C)
            0x75, 0x10,                     //   Report Size (16)
            0x95.toByte(), 0x01,             //   Report Count (1)
            0x81.toByte(), 0x00,             //   Input (Data,Array,Abs) -- current usage code, 0 = none
            0xC0.toByte(),                   // End Collection

            // ---- Collection 2: Keyboard boot-style report (report ID 2) ----
            0x05, 0x01,                     // Usage Page (Generic Desktop)
            0x09, 0x06,                     // Usage (Keyboard)
            0xA1.toByte(), 0x01,             // Collection (Application)
            0x85.toByte(), REPORT_ID_KEYBOARD.toByte(), //   Report ID (2)
            0x05, 0x07,                     //   Usage Page (Kbd/Keypad)
            0x19, 0xE0.toByte(),             //   Usage Minimum (224, LeftCtrl)
            0x29, 0xE7.toByte(),             //   Usage Maximum (231, RightGUI)
            0x15, 0x00,                     //   Logical Minimum (0)
            0x25, 0x01,                     //   Logical Maximum (1)
            0x75, 0x01,                     //   Report Size (1)
            0x95.toByte(), 0x08,             //   Report Count (8)
            0x81.toByte(), 0x02,             //   Input (Data,Var,Abs) -- modifier byte, unused but present
            0x95.toByte(), 0x01,             //   Report Count (1)
            0x75, 0x08,                     //   Report Size (8)
            0x81.toByte(), 0x01,             //   Input (Const) -- reserved byte
            0x95.toByte(), 0x06,             //   Report Count (6)
            0x75, 0x08,                     //   Report Size (8)
            0x15, 0x00,                     //   Logical Minimum (0)
            0x25, 0x65,                     //   Logical Maximum (101)
            0x05, 0x07,                     //   Usage Page (Kbd/Keypad)
            0x19, 0x00,                     //   Usage Minimum (0)
            0x29, 0x65,                     //   Usage Maximum (101)
            0x81.toByte(), 0x00,             //   Input (Data,Array) -- 6-byte keycode array
            0xC0.toByte(),                   // End Collection
        )
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    var onStateChanged: ((String) -> Unit)? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "HID app registered=$registered")
            onStateChanged?.invoke(if (registered) "registered" else "unregistered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.i(TAG, "connectionState device=${device?.address} state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    onStateChanged?.invoke("connected:${device?.name ?: device?.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == connectedDevice) connectedDevice = null
                    onStateChanged?.invoke("disconnected")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            // Host is polling us (rare for array/array reports); ack with an empty report
            // of the right shape so the connection doesn't stall.
            val proxy = hidDevice ?: return
            val dev = device ?: return
            when (id.toInt()) {
                REPORT_ID_CONSUMER -> proxy.replyReport(dev, type, id, byteArrayOf(0, 0))
                REPORT_ID_KEYBOARD -> proxy.replyReport(dev, type, id, ByteArray(8))
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            // No writable output/feature reports on this device; nothing to persist.
        }
    }

    @SuppressLint("MissingPermission") // caller ensures BLUETOOTH_CONNECT is granted first
    fun start() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: run { Log.w(TAG, "No Bluetooth adapter on this device"); return }
        if (!adapter.isEnabled) {
            onStateChanged?.invoke("bluetooth_off")
            return
        }
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }

            override fun onServiceDisconnected(profile: Int) {
                hidDevice = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "ARKlight Remote",
            "Virtual TV remote (Consumer Control + directional keys)",
            "ARKlight",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            REPORT_DESCRIPTOR,
        )
        // Conservative QoS: this is a low-rate, human-triggered input device, not a
        // game controller -- no need to fight for low-latency channel priority.
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, 11250,
        )
        val executor: Executor = Executor { it.run() } // callbacks are lightweight; run inline
        hidDevice?.registerApp(sdp, null, qos, executor, callback)
    }

    /** Bonded (paired) devices the user has already set up in Android's Bluetooth settings. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    /** Request the HID profile connection to an already-bonded device. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    /**
     * Called once on registration success: try every bonded device. Whichever one's
     * host stack accepts the HID connection request calls back via
     * onConnectionStateChanged; the rest are silently rejected/ignored by their OS.
     * This is the closest thing to "just works once paired" that the profile allows --
     * there's no API to ask a bonded device in advance whether it *supports* HID host.
     */
    @SuppressLint("MissingPermission")
    fun autoConnectBonded() {
        bondedDevices().forEach { connect(it) }
    }

    /** Send one key "down" report, then a zero/"up" report ~40ms later (array-style reports
     *  are level-triggered per byte-value, so we must explicitly clear back to 0). */
    @SuppressLint("MissingPermission")
    fun sendConsumerKey(usage: Int) {
        val device = connectedDevice ?: run { Log.w(TAG, "sendConsumerKey: not connected"); return }
        val proxy = hidDevice ?: return
        val down = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())
        proxy.sendReport(device, REPORT_ID_CONSUMER, down)
        mainHandler.postDelayed({
            proxy.sendReport(device, REPORT_ID_CONSUMER, byteArrayOf(0, 0))
        }, 40)
    }

    /** usage: any Keyboard/Keypad-page (0x07) usage code in [0, 101] -- arrows, Enter,
     *  F1-F12, or in principle any letter/number key the descriptor's range allows. */
    @SuppressLint("MissingPermission")
    fun sendKeyboardUsage(usage: Int) {
        val device = connectedDevice ?: run { Log.w(TAG, "sendKeyboardUsage: not connected"); return }
        val proxy = hidDevice ?: return
        val down = ByteArray(8) // [modifier, reserved, k1..k6]
        down[2] = (usage and 0xFF).toByte()
        proxy.sendReport(device, REPORT_ID_KEYBOARD, down)
        mainHandler.postDelayed({
            proxy.sendReport(device, REPORT_ID_KEYBOARD, ByteArray(8))
        }, 40)
    }

    fun isConnected(): Boolean = connectedDevice != null
}
