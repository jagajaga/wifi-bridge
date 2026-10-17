package dev.wifibridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramingTest {
    @Test fun splitMatchesEveryVector() {
        val cases = Vectors.framing
        assertEquals(4, cases.size)
        for ((i, c) in cases.withIndex()) {
            val frames = Framing.split(c.message, c.chunk)
            assertEquals("case $i", c.frames.map { it.toHex() }, frames.map { it.toHex() })
        }
    }

    @Test fun reassembleEveryVector() {
        for ((i, c) in Vectors.framing.withIndex()) {
            val r = Reassembler()
            val out = c.frames.mapNotNull { r.accept(it) }
            assertEquals("case $i", 1, out.size)
            assertArrayEquals("case $i", c.message, out.single())
        }
    }

    @Test fun chunkSizeFromMtu() {
        assertEquals(19, Framing.chunkSizeForMtu(23))
        assertEquals(19, Framing.chunkSizeForMtu(20)) // never below the minimum
        assertEquals(182, Framing.chunkSizeForMtu(186))
        assertEquals(508, Framing.chunkSizeForMtu(512))
    }

    @Test fun roundTripVariousSizes() {
        for (size in listOf(1, 18, 19, 20, 37, 38, 39, 1000, 65_536)) {
            val msg = ByteArray(size) { (it * 7).toByte() }
            for (chunk in listOf(19, 182, 508)) {
                val frames = Framing.split(msg, chunk)
                frames.forEach { assertEquals(true, it.size <= chunk + 1) }
                val r = Reassembler()
                val out = frames.mapNotNull { r.accept(it) }
                assertEquals(1, out.size)
                assertArrayEquals(msg, out.single())
            }
        }
    }

    @Test fun oversizedMessageDroppedAndReset() {
        val r = Reassembler()
        val frames = Framing.split(ByteArray(65_537), 508)
        assertEquals(emptyList<ByteArray>(), frames.mapNotNull { r.accept(it) })
        // receiver is usable again afterwards
        assertArrayEquals(byteArrayOf(4), r.accept(byteArrayOf(0x03, 0x04)))
    }

    @Test fun firstDiscardsPartialBuffer() {
        val r = Reassembler()
        assertNull(r.accept(byteArrayOf(0x01, 0x0a, 0x0b)))
        assertArrayEquals(byteArrayOf(0x0c), r.accept(byteArrayOf(0x03, 0x0c)))
    }

    @Test fun continuationWithoutFirstIsDropped() {
        val r = Reassembler()
        assertNull(r.accept(byteArrayOf(0x00, 0x01)))
        assertNull(r.accept(byteArrayOf(0x02, 0x02)))
        assertNull(r.accept(ByteArray(0)))
    }
}
