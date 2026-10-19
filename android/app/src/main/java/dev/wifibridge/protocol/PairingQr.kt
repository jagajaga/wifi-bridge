package dev.wifibridge.protocol

/**
 * Pairing code shown by the Mac. The QR holds `wifibridge://pair?p=<payload>` so
 * the system camera can hand it to this app; the payload is
 * `WBPAIR1;<b64url macId>;<b64url macPub>;<b64url pairSecret>;<b64url utf8 macName>`.
 * The bare payload (v0.1/v0.2 Macs) is accepted too.
 */
class PairingQr(val macId: ByteArray, val macPub: ByteArray, val pairSecret: ByteArray, val macName: String) {
    fun toPending(nowMillis: Long) = PendingPairing(
        macId = B64Url.encode(macId),
        macPub = B64Url.encode(macPub),
        pairSecret = B64Url.encode(pairSecret),
        macName = macName,
        createdAtMillis = nowMillis,
    )

    companion object {
        const val PREFIX = "WBPAIR1"
        const val URI_PREFIX = "wifibridge://pair?"
        const val SECRET_SIZE = 16

        /** Extracts the payload from a `wifibridge://pair?...p=<payload>` URI, else returns [text]. */
        fun payloadOf(text: String): String {
            val t = text.trim()
            if (!t.regionMatches(0, URI_PREFIX, 0, URI_PREFIX.length, ignoreCase = true)) return t
            val query = t.substring(URI_PREFIX.length).substringBefore('#')
            val raw = query.split('&').firstOrNull { it.startsWith("p=") }?.removePrefix("p=") ?: return ""
            return try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: IllegalArgumentException) {
                ""
            }
        }

        fun parse(text: String): PairingQr? {
            val parts = payloadOf(text).split(';')
            if (parts.size != 5 || parts[0] != PREFIX) return null
            return try {
                val macId = B64Url.decode(parts[1])
                val macPub = B64Url.decode(parts[2])
                val secret = B64Url.decode(parts[3])
                val name = B64Url.decode(parts[4]).toString(Charsets.UTF_8)
                if (macId.size != ID_SIZE || macPub.size != PUB_SIZE || secret.size != SECRET_SIZE) null
                else PairingQr(macId, macPub, secret, name)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
