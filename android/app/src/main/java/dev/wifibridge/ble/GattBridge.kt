package dev.wifibridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import dev.wifibridge.protocol.Outcome
import dev.wifibridge.protocol.PhoneEngine
import dev.wifibridge.protocol.Reassembler
import dev.wifibridge.protocol.TxQueue
import java.util.UUID

object GattUuids {
    val SERVICE: UUID = UUID.fromString("c86d1f45-41c6-43d2-a031-789f6b73e9d1")
    val INFO: UUID = UUID.fromString("1d4fe1b5-e225-4a93-9a96-7997402eec3d")
    val RX: UUID = UUID.fromString("222462df-2917-4ddb-97ac-5459a7f90a44")
    val TX: UUID = UUID.fromString("cc6d3b26-2fe0-4ed4-a3e2-fba6032b3ad6")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * Thin BLE adapter: GATT server + advertiser. All protocol decisions are made by [PhoneEngine];
 * this class only moves frames. Every piece of mutable state lives on [handler]'s thread.
 *
 * Runs only on a device; nothing here is covered by JVM unit tests.
 */
@SuppressLint("MissingPermission") // callers check BlePermissions.hasRequired() before start()
class GattBridge(
    private val context: Context,
    private val engineFactory: () -> PhoneEngine,
    private val onApprovalNeeded: (Outcome.NeedsApproval, reply: (ByteArray) -> Unit) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val thread = HandlerThread("wb-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var txChar: BluetoothGattCharacteristic? = null
    private lateinit var engine: PhoneEngine
    @Volatile private var info: ByteArray = ByteArray(0)

    private class Peer(val device: BluetoothDevice) {
        val reassembler = Reassembler()
        /** ATT MTU from onMtuChanged; 23 (the ATT default) when it never fires. */
        var mtu = DEFAULT_MTU
        var subscribed = false
        /** Queued prepared-write (long write) data for RX, committed on onExecuteWrite. */
        var prepared: java.io.ByteArrayOutputStream? = null
    }

    private val peers = HashMap<String, Peer>()
    private val tx = TxQueue<String> { address, frame -> notifyFrame(address, frame) }

    fun start(): Boolean {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off")
            return false
        }
        engine = engineFactory()
        info = engine.info()
        val s = manager.openGattServer(context, callback) ?: run {
            onStatus("Could not open GATT server")
            return false
        }
        server = s
        val service = BluetoothGattService(GattUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                GattUuids.INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                GattUuids.RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val tx = BluetoothGattCharacteristic(
            GattUuids.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                GattUuids.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(tx)
        txChar = tx
        s.addService(service) // advertising starts in onServiceAdded
        return true
    }

    fun stop() {
        handler.post {
            stopped = true
            handler.removeCallbacks(advertiseWatchdog)
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            runCatching { server?.close() }
            server = null
            peers.clear()
            tx.clear()
        }
        thread.quitSafely()
    }

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: run {
            onStatus("This phone cannot advertise BLE (peripheral mode unsupported)")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(GattUuids.SERVICE))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = onStatus("Advertising")
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) onStatus("Advertising")
            else onStatus("Advertising failed ($errorCode)")
        }
    }

    /** Handler thread only. Set once [stop] runs, so late callbacks don't revive advertising. */
    private var stopped = false

    /**
     * Many Android stacks stop legacy connectable advertising as soon as a central
     * connects and never resume it, which left the phone invisible to the Mac after
     * the first (pairing) connection. Restart it on every connection change, and
     * periodically as a backstop. Handler thread only.
     */
    private fun restartAdvertising() {
        if (stopped || server == null) return
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        startAdvertising()
    }

    private val advertiseWatchdog = object : Runnable {
        override fun run() {
            restartAdvertising()
            if (!stopped) handler.postDelayed(this, ADVERTISE_WATCHDOG_MS)
        }
    }

    // ---- outgoing ----

    /** Queue a whole message for [device]; frames go out one per onNotificationSent. Handler thread only. */
    private fun send(device: BluetoothDevice, message: ByteArray) {
        val peer = peers[device.address] ?: return
        if (!peer.subscribed) Log.w(TAG, "sending to ${device.address} before it subscribed to TX")
        tx.enqueue(device.address, message, peer.mtu)
    }

    private fun notifyFrame(address: String, frame: ByteArray): Boolean {
        val s = server ?: return false
        val characteristic = txChar ?: return false
        val device = peers[address]?.device ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(device, characteristic, false, frame) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    /** One RX frame from [device]. Handler thread only. */
    private fun onFrame(device: BluetoothDevice, frame: ByteArray) {
        val peer = peers.getOrPut(device.address) { Peer(device) }
        val message = peer.reassembler.accept(frame) ?: return
        dispatch(device, message)
    }

    private fun dispatch(device: BluetoothDevice, message: ByteArray) {
        when (val outcome = engine.handle(message)) {
            is Outcome.Reply -> send(device, outcome.message)
            is Outcome.NeedsApproval -> onApprovalNeeded(outcome) { bytes -> handler.post { send(device, bytes) } }
        }
    }

    // ---- GATT callbacks (binder thread -> handler thread) ----

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post {
                startAdvertising()
                handler.removeCallbacks(advertiseWatchdog)
                handler.postDelayed(advertiseWatchdog, ADVERTISE_WATCHDOG_MS)
            }
            else onStatus("Adding GATT service failed ($status)")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    peers.getOrPut(device.address) { Peer(device) }
                    restartAdvertising()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    peers.remove(device.address)
                    tx.drop(device.address)
                    restartAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { peers.getOrPut(device.address) { Peer(device) }.mtu = mtu }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val s = server ?: return
            if (characteristic.uuid != GattUuids.INFO) {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            if (offset > info.size) {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            // Long reads (49 bytes > MTU-1 at the default MTU) arrive as several reads with increasing offset.
            s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, info.copyOfRange(offset, info.size))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val s = server ?: return
            if (characteristic.uuid != GattUuids.RX || value == null) {
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            val bytes = value.copyOf()
            if (preparedWrite) {
                // Long write: each part is queued at its offset; the whole value is one frame, applied on execute.
                handler.post {
                    val peer = peers.getOrPut(device.address) { Peer(device) }
                    if (offset == 0) peer.prepared = java.io.ByteArrayOutputStream()
                    val buf = peer.prepared
                    val status = when {
                        buf == null || offset != buf.size() -> BluetoothGatt.GATT_INVALID_OFFSET
                        buf.size() + bytes.size > MAX_ATTRIBUTE_VALUE -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                        else -> { buf.write(bytes); BluetoothGatt.GATT_SUCCESS }
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) peer.prepared = null
                    // A prepare-write response echoes the value so the client can verify it.
                    if (responseNeeded) server?.sendResponse(device, requestId, status, offset, bytes)
                }
                return
            }
            if (offset != 0) {
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            handler.post { onFrame(device, bytes) }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            handler.post {
                val s = server ?: return@post
                val value = if (peers[device.address]?.subscribed == true) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val s = server ?: return
            val ok = descriptor.uuid == GattUuids.CCCD && value != null && value.size == 2
            if (responseNeeded) {
                s.sendResponse(
                    device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null,
                )
            }
            if (!ok) return
            val enabled = value!![0].toInt() and 0x01 != 0
            handler.post { peers.getOrPut(device.address) { Peer(device) }.subscribed = enabled }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post {
                val peer = peers[device.address]
                val frame = peer?.prepared?.toByteArray()
                peer?.prepared = null
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (execute && frame != null) onFrame(device, frame)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification to ${device.address} failed ($status)")
                tx.onSent(device.address, status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    private companion object {
        const val TAG = "GattBridge"
        const val DEFAULT_MTU = 23
        const val MAX_ATTRIBUTE_VALUE = 512
        const val ADVERTISE_WATCHDOG_MS = 60_000L
    }
}
