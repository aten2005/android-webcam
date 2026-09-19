package dev.aten.webcam.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dev.aten.webcam.data.StandbyMode

/**
 * In standby nothing is held by default: the Wi-Fi chip wakes the CPU for an inbound connection,
 * and a short timed lock then keeps it awake through the handshake. Full locks exist only while
 * someone is actually watching.
 */
class PowerLocks(private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    private val acceptLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webcam:accept")
        .apply { setReferenceCounted(false) }
    private val streamLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webcam:stream")
        .apply { setReferenceCounted(false) }
    private val standbyLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webcam:standby")
        .apply { setReferenceCounted(false) }

    // HIGH_PERF was deprecated in API 34 in favour of LOW_LATENCY; both keep Wi-Fi out of power-save while streaming.
    @Suppress("DEPRECATION")
    private val wifiLock = wifiManager.createWifiLock(
        if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "webcam:stream",
    ).apply { setReferenceCounted(false) }

    private var standbyMode = StandbyMode.AUTO
    private var receiverRegistered = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = applyStandbyLock()
    }

    /** Called for every accepted connection so the CPU cannot re-suspend mid-handshake. */
    fun onConnectionAccepted() = acceptLock.acquire(ACCEPT_LOCK_MS)

    @SuppressLint("WakelockTimeout") // Held exactly as long as a viewer is connected; released in setStreaming(false).
    fun setStreaming(streaming: Boolean) {
        if (streaming) {
            streamLock.acquire()
            wifiLock.acquire()
        } else {
            if (streamLock.isHeld) streamLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    fun setStandbyMode(mode: StandbyMode) {
        standbyMode = mode
        val needsReceiver = mode == StandbyMode.AUTO
        if (needsReceiver && !receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(powerReceiver, filter)
            receiverRegistered = true
        } else if (!needsReceiver && receiverRegistered) {
            context.unregisterReceiver(powerReceiver)
            receiverRegistered = false
        }
        applyStandbyLock()
    }

    fun releaseAll() {
        if (receiverRegistered) {
            context.unregisterReceiver(powerReceiver)
            receiverRegistered = false
        }
        setStreaming(false)
        if (standbyLock.isHeld) standbyLock.release()
        if (acceptLock.isHeld) acceptLock.release()
    }

    @SuppressLint("WakelockTimeout") // Deliberately indefinite: this is the user's "reliable standby" choice.
    private fun applyStandbyLock() {
        val hold = when (standbyMode) {
            StandbyMode.ALWAYS_RELIABLE -> true
            StandbyMode.MAX_BATTERY -> false
            StandbyMode.AUTO -> context.getSystemService(BatteryManager::class.java).isCharging
        }
        if (hold) standbyLock.acquire() else if (standbyLock.isHeld) standbyLock.release()
    }

    private companion object {
        const val ACCEPT_LOCK_MS = 30_000L
    }
}
