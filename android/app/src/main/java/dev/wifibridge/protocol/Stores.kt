package dev.wifibridge.protocol

import kotlinx.serialization.Serializable

/** Byte fields are b64url strings so the records serialize as-is. */
@Serializable
data class PhoneIdentity(val phoneId: String, val privateKey: String, val publicKey: String) {
    val phoneIdBytes: ByteArray get() = B64Url.decode(phoneId)
    val privateKeyBytes: ByteArray get() = B64Url.decode(privateKey)
    val publicKeyBytes: ByteArray get() = B64Url.decode(publicKey)

    companion object {
        fun generate(random: java.security.SecureRandom = java.security.SecureRandom()): PhoneIdentity {
            val id = ByteArray(ID_SIZE).also(random::nextBytes)
            val priv = WbCrypto.generatePrivateKey()
            return PhoneIdentity(B64Url.encode(id), B64Url.encode(priv), B64Url.encode(WbCrypto.publicFromPrivate(priv)))
        }
    }
}

@Serializable
data class PendingPairing(
    val macId: String,
    val macPub: String,
    val pairSecret: String,
    val macName: String,
    val createdAtMillis: Long,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= createdAtMillis + TTL_MILLIS

    companion object {
        const val TTL_MILLIS = 10 * 60 * 1000L
    }
}

@Serializable
data class PairedMac(
    val macId: String,
    val macPub: String,
    val name: String,
    val autoShare: Boolean = true,
    val pairedAtMillis: Long = 0,
)

/** Persistence the protocol engine needs. Keys are b64url macIds. */
interface PeerStore {
    fun pending(macId: String): PendingPairing?
    fun removePending(macId: String)
    fun paired(macId: String): PairedMac?
    fun savePaired(mac: PairedMac)
}

class InMemoryPeerStore : PeerStore {
    val pendings = LinkedHashMap<String, PendingPairing>()
    val paireds = LinkedHashMap<String, PairedMac>()
    override fun pending(macId: String) = pendings[macId]
    override fun removePending(macId: String) { pendings.remove(macId) }
    override fun paired(macId: String) = paireds[macId]
    override fun savePaired(mac: PairedMac) { paireds[mac.macId] = mac }
}
