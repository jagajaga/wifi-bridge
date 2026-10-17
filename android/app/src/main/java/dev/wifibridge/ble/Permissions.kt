package dev.wifibridge.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object BlePermissions {
    /** Runtime permissions the GATT server + advertiser need on this API level. */
    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // API 29-30: BLUETOOTH / BLUETOOTH_ADMIN are install-time; location is the runtime one.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Required plus nice-to-have: notifications for approval prompts on 33+. FINE must be requested
     * together with COARSE (the user may grant only COARSE, which then fails [hasRequired] on 29-30).
     */
    fun toRequest(): Array<String> {
        val base = required().toMutableList()
        if (Manifest.permission.ACCESS_FINE_LOCATION in base) base += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) base += Manifest.permission.POST_NOTIFICATIONS
        return base.toTypedArray()
    }

    fun hasRequired(context: Context): Boolean =
        required().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
