package dev.wifibridge.protocol

/** `WBPAIR1;<b64url macId>;<b64url macPub>;<b64url pairSecret>;<b64url utf8 macName>`. */
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
        const val SECRET_SIZE = 16

        fun parse(text: String): PairingQr? {
            val parts = text.trim().split(';')
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
