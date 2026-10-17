package dev.wifibridge.protocol

import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Any failure to open a sealed box. Maps to ERROR 2 on the wire. */
class AuthenticationException(cause: Throwable? = null) : Exception("authentication failure", cause)

/** Crypto primitives from protocol.md, "Identities and keys" and "Sealing". */
object WbCrypto {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    const val SEAL_OVERHEAD = NONCE_SIZE + TAG_SIZE

    val HKDF_SALT: ByteArray get() = "wifi-bridge/v1".utf8()
    private val PROOF_LABEL: ByteArray get() = "wb-pair-phone".utf8()

    fun generatePrivateKey(): ByteArray = X25519.generatePrivateKey()

    fun publicFromPrivate(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    /** HKDF info: always Mac first, whichever side is computing. */
    fun hkdfInfo(macPub: ByteArray, phonePub: ByteArray): ByteArray = macPub + phonePub

    /** K = HKDF-SHA256(X25519(myPriv, peerPub), salt, macPub || phonePub, 32). */
    fun sessionKey(myPriv: ByteArray, peerPub: ByteArray, macPub: ByteArray, phonePub: ByteArray): ByteArray {
        val shared = X25519.computeSharedSecret(myPriv, peerPub)
        return Hkdf.computeHkdf("HMACSHA256", shared, HKDF_SALT, hkdfInfo(macPub, phonePub), KEY_SIZE)
    }

    fun phoneSessionKey(phonePriv: ByteArray, phonePub: ByteArray, macPub: ByteArray): ByteArray =
        sessionKey(phonePriv, macPub, macPub, phonePub)

    fun macSessionKey(macPriv: ByteArray, macPub: ByteArray, phonePub: ByteArray): ByteArray =
        sessionKey(macPriv, phonePub, macPub, phonePub)

    /** nonce(12, random) || ciphertext || tag(16). Tink's subtle ChaCha20Poly1305 emits exactly this layout. */
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        ChaCha20Poly1305(key).encrypt(plaintext, aad)

    fun open(key: ByteArray, aad: ByteArray, box: ByteArray): ByteArray {
        if (box.size < SEAL_OVERHEAD) throw AuthenticationException()
        try {
            return ChaCha20Poly1305(key).decrypt(box, aad)
        } catch (e: GeneralSecurityException) {
            throw AuthenticationException(e)
        }
    }

    /** proof = HMAC-SHA256(pairSecret, "wb-pair-phone" || macId || phoneId || phonePub). */
    fun pairProof(pairSecret: ByteArray, macId: ByteArray, phoneId: ByteArray, phonePub: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairSecret, "HmacSHA256"))
        return mac.doFinal(PROOF_LABEL + macId + phoneId + phonePub)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** Associated data for each sealed message (protocol.md "Messages"). */
object Aad {
    fun pairConfirm(macId: ByteArray, phoneId: ByteArray): ByteArray = "wb-pair-confirm".utf8() + macId + phoneId
    fun syncRequest(macId: ByteArray, phoneId: ByteArray): ByteArray = "wb-req".utf8() + macId + phoneId
    fun syncResponse(macId: ByteArray, phoneId: ByteArray): ByteArray = "wb-resp".utf8() + macId + phoneId
}
