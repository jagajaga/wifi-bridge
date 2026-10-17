package dev.wifibridge.protocol

import java.util.Base64

/** base64url without padding, as used everywhere on the wire (protocol.md "Notation"). */
object B64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Throws [IllegalArgumentException] on invalid input. Accepts unpadded input. */
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

internal fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
