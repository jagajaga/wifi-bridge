package dev.wifibridge.protocol

import java.util.ArrayDeque

/**
 * Outgoing notification flow control (protocol.md "Framing"): at most one notification in flight;
 * the next frame goes only after the stack reports onNotificationSent. Not thread-safe.
 *
 * @param notify tries to send one frame to a device; returns false if the stack refused it.
 */
class TxQueue<D : Any>(private val notify: (device: D, frame: ByteArray) -> Boolean) {
    private val queue = ArrayDeque<Pair<D, ByteArray>>()
    /** Device whose frame is awaiting onNotificationSent, if any. */
    private var inFlight: D? = null

    val idle: Boolean get() = inFlight == null && queue.isEmpty()

    fun enqueue(device: D, message: ByteArray, mtu: Int) {
        for (frame in Framing.split(message, Framing.chunkSizeForMtu(mtu))) queue.addLast(device to frame)
        pump()
    }

    /** Call from onNotificationSent. A failed send drops the rest of that device's frames. */
    fun onSent(device: D, success: Boolean) {
        if (inFlight != device) return // late callback for a device already dropped
        inFlight = null
        if (!success) queue.removeAll { it.first == device }
        pump()
    }

    /**
     * Device disconnected: forget its frames. Its in-flight frame may never get onNotificationSent,
     * so stop waiting for it rather than stalling every other device.
     */
    fun drop(device: D) {
        queue.removeAll { it.first == device }
        if (inFlight == device) {
            inFlight = null
            pump()
        }
    }

    fun clear() {
        queue.clear()
        inFlight = null
    }

    private fun pump() {
        while (inFlight == null && queue.isNotEmpty()) {
            val (device, frame) = queue.pollFirst()!!
            if (notify(device, frame)) inFlight = device else queue.removeAll { it.first == device }
        }
    }
}
