package dev.wifibridge.ble

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.wifibridge.WifiBridgeApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Foreground service (type connectedDevice) hosting the GATT server while the bridge is on. */
class BridgeService : Service() {
    private var bridge: GattBridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        try {
            startForeground(
                Notifications.SERVICE_ID,
                Notifications.service(this, "Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // SecurityException on 34+ when Bluetooth permissions were revoked, or FGS start not allowed.
            Log.e(TAG, "cannot start foreground", e)
            setStatus("Not running: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!BlePermissions.hasRequired(this)) {
            setStatus("Bluetooth permission missing")
            stopSelf()
            return START_NOT_STICKY
        }
        if (bridge == null) {
            val app = application as WifiBridgeApp
            val b = GattBridge(
                context = this,
                engineFactory = app::newEngine,
                onApprovalNeeded = { outcome, reply -> ApprovalHub.request(this, outcome, reply) },
                onStatus = ::setStatus,
            )
            if (b.start()) {
                bridge = b
                running.value = true
            } else {
                b.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun setStatus(text: String) {
        status.value = text
        if (bridge != null || running.value) {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.SERVICE_ID, Notifications.service(this, text))
        }
    }

    override fun onDestroy() {
        ApprovalHub.cancelAll(this)
        bridge?.stop()
        bridge = null
        running.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BridgeService"
        val running = MutableStateFlow(false)
        val status = MutableStateFlow("Stopped")
        val statusFlow: StateFlow<String> get() = status

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}

/** Restarts the bridge after reboot if the user turned on both the bridge and "Start on boot". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = (context.applicationContext as WifiBridgeApp).settings
        if (settings.serviceEnabled.value && settings.startOnBoot.value && BlePermissions.hasRequired(context)) {
            runCatching { BridgeService.start(context) }.onFailure { Log.e("BootReceiver", "start failed", it) }
        }
    }
}
