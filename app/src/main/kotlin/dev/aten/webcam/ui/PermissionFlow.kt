package dev.aten.webcam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings

data class PermissionState(
    val camera: Boolean,
    val microphone: Boolean,
    val notifications: Boolean,
    val batteryExempt: Boolean,
) {
    /** Everything worth asking for before the listener starts; only the camera is mandatory. */
    val missing: List<String>
        get() = buildList {
            if (!camera) add(Manifest.permission.CAMERA)
            if (!microphone) add(Manifest.permission.RECORD_AUDIO)
            if (!notifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }

    companion object {
        fun read(context: Context): PermissionState {
            fun granted(permission: String) =
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            return PermissionState(
                camera = granted(Manifest.permission.CAMERA),
                microphone = granted(Manifest.permission.RECORD_AUDIO),
                notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
                batteryExempt = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}

object SystemScreens {
    // A listener that must answer from Doze is the exemption's intended use; without it, idle
    // devices stop delivering inbound connections to the app.
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) {
        context.startActivity(
            Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }
}
