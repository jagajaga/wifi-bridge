package dev.wifibridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxQueueTest {
    private val sent = mutableListOf<Pair<String, String>>()
    private var accept = true
    private val q = TxQueue<String> { d, f -> if (accept) { sent += d to f.toHex(); true } else false }

    @Test fun oneFrameInFlightUntilOnSent() {
        val msg = ByteArray(40) { it.toByte() } // 3 frames at MTU 23
        q.enqueue("mac", msg, mtu = 23)
        assertEquals(1, sent.size)
        q.onSent("mac", true)
        assertEquals(2, sent.size)
        q.onSent("mac", true)
        assertEquals(3, sent.size)
        q.onSent("mac", true)
        assertTrue(q.idle)
        assertEquals(Framing.split(msg, 19).map { it.toHex() }, sent.map { it.second })
    }

    @Test fun usesPerDeviceMtu() {
        q.enqueue("big", ByteArray(100), mtu = 185)
        assertEquals(1, sent.size)
        assertEquals("03", sent[0].second.take(2)) // whole message in one frame
        q.onSent("big", true)
        q.enqueue("small", ByteArray(100), mtu = 23)
        assertEquals("01", sent[1].second.take(2))
    }

    @Test fun failedSendDropsThatDevicesRemainingFrames() {
        q.enqueue("a", ByteArray(40), 23)
        q.enqueue("b", byteArrayOf(9), 23)
        q.onSent("a", false)
        assertEquals(listOf("a", "b"), sent.map { it.first })
    }

    @Test fun refusedNotifyDropsDeviceAndMovesOn() {
        accept = false
        q.enqueue("a", ByteArray(40), 23)
        accept = true
        q.enqueue("b", byteArrayOf(9), 23)
        assertEquals(listOf("b" to "0309"), sent)
    }

    @Test fun disconnectDropsQueuedFrames() {
        q.enqueue("a", ByteArray(40), 23)
        q.drop("a")
        assertTrue(q.idle) // not waiting on an onNotificationSent that may never come
        q.onSent("a", true)
        assertEquals(1, sent.size)
    }

    @Test fun disconnectOfInFlightDeviceUnblocksOthers() {
        q.enqueue("a", ByteArray(40), 23)
        q.enqueue("b", byteArrayOf(9), 23)
        assertEquals(1, sent.size)
        q.drop("a")
        assertEquals(listOf("a", "b"), sent.map { it.first })
        q.onSent("a", true) // late callback for the dropped device must not release b's slot
        q.enqueue("c", byteArrayOf(1), 23)
        assertEquals(2, sent.size)
        q.onSent("b", true)
        assertEquals(listOf("a", "b", "c"), sent.map { it.first })
    }
}
