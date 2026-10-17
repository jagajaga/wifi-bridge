package dev.wifibridge.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * Offline QR decoding over raw ARGB pixels (no Android types, so it runs in JVM unit tests).
 *
 * Tries, in order: HybridBinarizer, GlobalHistogramBinarizer, then both again on inverted luminance.
 * Very large images are box-downscaled first; if that fails a second, smaller scale is tried.
 */
object QrImageDecoder {
    const val MAX_SIDE = 2048
    private const val RETRY_SIDE = 800

    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    class Pixels(val argb: IntArray, val width: Int, val height: Int)

    fun decode(argb: IntArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0 && argb.size >= width * height)
        val base = flattenOnWhite(downscaleTo(Pixels(argb, width, height), MAX_SIDE))
        attempt(base)?.let { return it }
        if (maxOf(base.width, base.height) > RETRY_SIDE * 3 / 2) {
            attempt(downscaleTo(base, RETRY_SIDE))?.let { return it }
        }
        return null
    }

    private fun attempt(p: Pixels): String? {
        val source: LuminanceSource = RGBLuminanceSource(p.width, p.height, p.argb)
        for (s in listOf(source, source.invert())) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(s)), BinaryBitmap(GlobalHistogramBinarizer(s)))) {
                val reader = MultiFormatReader()
                try {
                    return reader.decode(bitmap, hints).text
                } catch (e: ReaderException) {
                    // try the next strategy
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }

    /** Transparent PNG pixels would otherwise read as black (RGB 0); composite them over white. */
    fun flattenOnWhite(p: Pixels): Pixels {
        if (p.argb.all { (it ushr 24) == 0xff }) return p
        val out = IntArray(p.width * p.height) { i ->
            val c = p.argb[i]
            val a = c ushr 24
            fun ch(shift: Int) = (((c shr shift) and 0xff) * a + 255 * (255 - a)) / 255
            (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return Pixels(out, p.width, p.height)
    }

    /** Integer-factor box filter so the longest side is at most [maxSide]. */
    fun downscaleTo(p: Pixels, maxSide: Int): Pixels {
        val longest = maxOf(p.width, p.height)
        if (longest <= maxSide) return p
        val f = (longest + maxSide - 1) / maxSide
        val w = p.width / f
        val h = p.height / f
        val out = IntArray(w * h)
        val area = f * f
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until f) {
                    val row = (y * f + dy) * p.width + x * f
                    for (dx in 0 until f) {
                        val c = p.argb[row + dx]
                        val a = c ushr 24
                        r += (((c shr 16) and 0xff) * a + 255 * (255 - a)) / 255
                        g += (((c shr 8) and 0xff) * a + 255 * (255 - a)) / 255
                        b += ((c and 0xff) * a + 255 * (255 - a)) / 255
                    }
                }
                out[y * w + x] = (0xff shl 24) or ((r / area) shl 16) or ((g / area) shl 8) or (b / area)
            }
        }
        return Pixels(out, w, h)
    }
}
