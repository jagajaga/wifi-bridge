package dev.wifibridge.protocol

import java.io.ByteArrayOutputStream

/** protocol.md "Framing". */
object Framing {
    const val FIRST = 0x01
    const val LAST = 0x02
    const val MIN_CHUNK = 19
    const val MAX_MESSAGE = 65_536

    /** Negotiated ATT MTU -> chunk size: max value length (MTU - 3) minus the 1-byte header. */
    fun chunkSizeForMtu(mtu: Int): Int = maxOf(MIN_CHUNK, mtu - 3 - 1)

    fun split(message: ByteArray, chunk: Int): List<ByteArray> {
        require(chunk >= 1)
        if (message.isEmpty()) return listOf(byteArrayOf((FIRST or LAST).toByte()))
        val frames = ArrayList<ByteArray>((message.size + chunk - 1) / chunk)
        var offset = 0
        while (offset < message.size) {
            val end = minOf(message.size, offset + chunk)
            var header = 0
            if (offset == 0) header = header or FIRST
            if (end == message.size) header = header or LAST
            frames += byteArrayOf(header.toByte()) + message.copyOfRange(offset, end)
            offset = end
        }
        return frames
    }
}

/**
 * Reassembles frames into messages for one peer. Not thread-safe; keep one per connected device.
 */
class Reassembler(private val maxMessage: Int = Framing.MAX_MESSAGE) {
    private var buffer: ByteArrayOutputStream? = null

    /** Returns a complete message when [frame] carries LAST, else null. */
    fun accept(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) return null
        val header = frame[0].toInt() and 0xff
        if (header and Framing.FIRST != 0) {
            buffer = ByteArrayOutputStream()
        }
        val buf = buffer ?: return null // continuation without a FIRST: drop
        if (buf.size() + frame.size - 1 > maxMessage) {
            reset()
            return null
        }
        buf.write(frame, 1, frame.size - 1)
        if (header and Framing.LAST != 0) {
            buffer = null
            return buf.toByteArray()
        }
        return null
    }

    fun reset() {
        buffer = null
    }
}
