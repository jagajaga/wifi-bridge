package dev.wifibridge.ble

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.wifibridge.WifiBridgeApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Foreground service (type connectedDevice) hosting the GATT server while the bridge is on. */
class BridgeService : Service() {
    private var bridge: GattBridge? = null

    /**
     * Turning Bluetooth off tears down the GATT server and the advertiser, and nothing brings them back
     * when it comes on again. Follow the adapter: stop the bridge on off, start a fresh one on on.
     */
    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> if (!startBridge()) stopSelf()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    bridge?.stop()
                    bridge = null
                    setStatus(BLUETOOTH_OFF)
                }
            }
        }
    }
    private var receiverRegistered = false

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
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        if (!startBridge()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Starts the bridge if it is not running. False only on a failure that waiting for Bluetooth will not fix. */
    private fun startBridge(): Boolean {
        if (bridge != null) return true
        running.value = true
        if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) {
            setStatus(BLUETOOTH_OFF) // stay in the foreground; adapterState starts the bridge when it comes on
            return true
        }
        val app = application as WifiBridgeApp
        val b = GattBridge(
            context = this,
            engineFactory = app::newEngine,
            onApprovalNeeded = { outcome, reply -> ApprovalHub.request(this, outcome, reply) },
            onStatus = ::setStatus,
        )
        if (b.start()) {
            bridge = b
            return true
        }
        b.stop()
        running.value = false
        return false
    }

    private fun setStatus(text: String) {
        status.value = text
        if (bridge != null || running.value) {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.SERVICE_ID, Notifications.service(this, text))
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(adapterState)
            receiverRegistered = false
        }
        ApprovalHub.cancelAll(this)
        bridge?.stop()
        bridge = null
        running.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val BLUETOOTH_OFF = "Bluetooth is off — waiting for it to come back on"
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
