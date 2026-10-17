package dev.wifibridge.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.wifibridge.protocol.WifiNetwork
import dev.wifibridge.protocol.WifiQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthetic stand-in for a Samsung "Share network" PNG: a QR at EC level H on a white rounded card,
 * a filled round logo over the centre, a dark "network name" bar above, all on a grey page.
 */
class QrImageDecoderTest {
    private val payload = "WIFI:T:WPA;S:Casita_Guest;P:p@ss\\;word;H:false;;"

    private class Canvas(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in maxOf(0, y0) until minOf(h, y1)) for (x in maxOf(0, x0) until minOf(w, x1)) px[y * w + x] = c
        }
        fun circle(cx: Int, cy: Int, r: Int, c: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if (x in 0 until w && y in 0 until h && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = c
            }
        }
    }

    /** Returns the canvas and the QR side length in pixels. */
    private fun samsungLike(
        qrSide: Int, logoFraction: Double, scale: Int = 1,
        card: Int = 0xFFFFFFFF.toInt(), ink: Int = 0xFF000000.toInt(),
    ): Canvas {
        val matrix = QRCodeWriter().encode(
            payload, BarcodeFormat.QR_CODE, qrSide, qrSide,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0),
        )
        val margin = qrSide / 6
        val cardW = qrSide + 2 * margin
        val cardH = qrSide + 3 * margin
        val page = Canvas((cardW + 80) * scale, (cardH + 200) * scale, 0xFF3A3A3A.toInt())
        val cardX = 40; val cardY = 120
        page.rect(cardX * scale, cardY * scale, (cardX + cardW) * scale, (cardY + cardH) * scale, card)
        // "network name" text bar above the QR
        page.rect((cardX + margin) * scale, (cardY + margin / 3) * scale, (cardX + margin + qrSide * 2 / 3) * scale, (cardY + margin * 2 / 3) * scale, 0xFF111111.toInt())
        val qrX = cardX + margin
        val qrY = cardY + 2 * margin
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) page.rect((qrX + x) * scale, (qrY + y) * scale, (qrX + x + 1) * scale, (qrY + y + 1) * scale, ink)
        }
        // round Wi-Fi logo: teal disc with a white ring, covering the centre
        val r = (qrSide * logoFraction / 2).toInt() * scale
        val cx = (qrX + qrSide / 2) * scale; val cy = (qrY + qrSide / 2) * scale
        page.circle(cx, cy, r + 3 * scale, 0xFFFFFFFF.toInt())
        page.circle(cx, cy, r, 0xFF1F6FB2.toInt())
        page.circle(cx, cy - r / 4, r / 3, 0xFFFFFFFF.toInt())
        return page
    }

    @Test fun decodesSamsungLikeCardWithCentreLogo() {
        val c = samsungLike(qrSide = 600, logoFraction = 0.20)
        val text = QrImageDecoder.decode(c.px, c.w, c.h)
        assertEquals(payload, text)
        assertEquals(WifiNetwork("Casita_Guest", "WPA", "p@ss;word", false), WifiQr.parse(text!!))
    }

    @Test fun decodesWithLargerLogo() {
        val c = samsungLike(qrSide = 600, logoFraction = 0.26)
        assertEquals(payload, QrImageDecoder.decode(c.px, c.w, c.h))
    }

    @Test fun decodesVeryLargeImageViaDownscale() {
        val c = samsungLike(qrSide = 700, logoFraction = 0.20, scale = 4) // ~3500 px tall
        assertEquals(true, maxOf(c.w, c.h) > QrImageDecoder.MAX_SIDE)
        assertEquals(payload, QrImageDecoder.decode(c.px, c.w, c.h))
    }

    /**
     * A washed-out capture (grey ink on a grey card). HybridBinarizer alone fails on this input;
     * it decodes only through the GlobalHistogramBinarizer fallback, so it pins that fallback.
     */
    @Test fun decodesLowContrastImageViaGlobalHistogramFallback() {
        val c = samsungLike(qrSide = 600, logoFraction = 0.20, card = 0xFF969696.toInt(), ink = 0xFF6E6E6E.toInt())
        assertEquals(payload, QrImageDecoder.decode(c.px, c.w, c.h))
    }

    @Test fun decodesInvertedImage() {
        val c = samsungLike(qrSide = 500, logoFraction = 0.0)
        val inverted = IntArray(c.px.size) { (c.px[it] and 0xFF000000.toInt()) or (c.px[it].inv() and 0x00FFFFFF) }
        assertEquals(payload, QrImageDecoder.decode(inverted, c.w, c.h))
    }

    @Test fun decodesTransparentBackgroundPng() {
        val c = samsungLike(qrSide = 500, logoFraction = 0.2)
        // make the white card fully transparent black, as some PNG exporters do
        val px = IntArray(c.px.size) { if (c.px[it] == 0xFFFFFFFF.toInt()) 0x00000000 else c.px[it] }
        assertEquals(payload, QrImageDecoder.decode(px, c.w, c.h))
    }

    /** Forcing control: the synthetic logo really covers modules; an oversized one defeats EC level H. */
    @Test fun oversizedLogoDefeatsDecoding() {
        val c = samsungLike(qrSide = 600, logoFraction = 0.60)
        assertNull(QrImageDecoder.decode(c.px, c.w, c.h))
    }

    @Test fun blankImageIsNull() {
        val c = Canvas(400, 400, 0xFFFFFFFF.toInt())
        assertNull(QrImageDecoder.decode(c.px, c.w, c.h))
    }
}
