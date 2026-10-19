package dev.wifibridge.protocol

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoVectorsTest {
    private val v = Vectors.keys

    /** Deterministic seal with an explicit nonce, in the protocol layout, for byte-exact comparisons. */
    private fun sealWithNonce(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray) =
        nonce + InsecureNonceChaCha20Poly1305(key).encrypt(nonce, pt, aad)

    private fun assertOpenFails(key: ByteArray, aad: ByteArray, box: ByteArray) {
        try {
            WbCrypto.open(key, aad, box)
            fail("open should have failed")
        } catch (e: AuthenticationException) {
            // expected
        }
    }

    @Test fun publicKeysFromPrivate() {
        assertArrayEquals(v.macPub, WbCrypto.publicFromPrivate(v.macPriv))
        assertArrayEquals(v.phonePub, WbCrypto.publicFromPrivate(v.phonePriv))
    }

    @Test fun hkdfInfoIsMacFirst() {
        assertArrayEquals(v.macPub + v.phonePub, WbCrypto.hkdfInfo(v.macPub, v.phonePub))
    }

    @Test fun sessionKeyFromPhoneSide() {
        assertEquals(v.k.toHex(), WbCrypto.phoneSessionKey(v.phonePriv, v.phonePub, v.macPub).toHex())
    }

    @Test fun sessionKeyFromMacSide() {
        assertEquals(v.k.toHex(), WbCrypto.macSessionKey(v.macPriv, v.macPub, v.phonePub).toHex())
    }

    @Test fun infoBytes() {
        assertEquals(49, Vectors.info.size)
        assertEquals(Vectors.info.toHex(), infoValue(v.phoneId, v.phonePub).toHex())
        val engine = PhoneEngine(Vectors.phoneIdentity(), InMemoryPeerStore(), { emptyList() })
        assertEquals(Vectors.info.toHex(), engine.info().toHex())
    }

    @Test fun aadLabelsMatchSpec() {
        assertArrayEquals(Vectors.literalAad("wb-pair-confirm"), Aad.pairConfirm(v.macId, v.phoneId))
        assertArrayEquals(Vectors.literalAad("wb-req"), Aad.syncRequest(v.macId, v.phoneId))
        assertArrayEquals(Vectors.literalAad("wb-resp"), Aad.syncResponse(v.macId, v.phoneId))
    }

    @Test fun pairingQrParses() {
        val qr = assertNotNullAndGet(PairingQr.parse(Vectors.pairing.qr))
        assertArrayEquals(v.macId, qr.macId)
        assertArrayEquals(v.macPub, qr.macPub)
        assertArrayEquals(Vectors.pairing.pairSecret, qr.pairSecret)
        assertEquals(Vectors.pairing.macName, qr.macName)
    }

    @Test fun pairingUriParsesToTheSameCode() {
        val plain = assertNotNullAndGet(PairingQr.parse(Vectors.pairing.qr))
        for (form in listOf(
            Vectors.pairing.qrUri,
            Vectors.pairing.qrUri.replace(";", "%3B"),              // percent-encoded by a scanner
            Vectors.pairing.qrUri.replace("wifibridge://pair", "WIFIBRIDGE://PAIR"),
            "wifibridge://pair?x=1&p=" + Vectors.pairing.qr + "#frag",
            "  " + Vectors.pairing.qrUri + "\n",
        )) {
            val qr = assertNotNullAndGet(PairingQr.parse(form))
            assertArrayEquals(form, plain.macId, qr.macId)
            assertArrayEquals(form, plain.macPub, qr.macPub)
            assertArrayEquals(form, plain.pairSecret, qr.pairSecret)
            assertEquals(form, plain.macName, qr.macName)
        }
    }

    @Test fun pairingUriRejectsJunk() {
        assertEquals(null, PairingQr.parse("wifibridge://pair"))
        assertEquals(null, PairingQr.parse("wifibridge://pair?q=" + Vectors.pairing.qr))
        assertEquals(null, PairingQr.parse("wifibridge://pair?p=%ZZ"))
        assertEquals(null, PairingQr.parse("https://example.com/pair?p=" + Vectors.pairing.qr))
    }

    @Test fun pairingQrRejectsJunk() {
        assertEquals(null, PairingQr.parse("WBPAIR2;a;b;c;d"))
        assertEquals(null, PairingQr.parse("WBPAIR1;AAAA;AAAA;AAAA;AAAA"))
        assertEquals(null, PairingQr.parse("WBPAIR1;!!;b;c;d"))
        assertEquals(null, PairingQr.parse(Vectors.pairing.qr + ";extra"))
    }

    @Test fun proofMatches() {
        assertEquals(
            Vectors.pairing.proof.toHex(),
            WbCrypto.pairProof(Vectors.pairing.pairSecret, v.macId, v.phoneId, v.phonePub).toHex(),
        )
    }

    @Test fun pairRequestAndAcceptBytes() {
        assertEquals(Vectors.pairing.pairRequest.toHex(), WbMessage.PairRequest(v.macId).encode().toHex())
        assertEquals(Vectors.pairing.pairAccept.toHex(), WbMessage.PairAccept(Vectors.pairing.proof).encode().toHex())
        val decoded = WbMessage.decode(Vectors.pairing.pairRequest) as WbMessage.PairRequest
        assertArrayEquals(v.macId, decoded.macId)
    }

    @Test fun pairConfirmOpensWithKAndAad() {
        val msg = WbMessage.decode(Vectors.pairing.pairConfirm) as WbMessage.PairConfirm
        assertArrayEquals(v.macId, msg.macId)
        val name = WbCrypto.open(v.k, Vectors.literalAad("wb-pair-confirm"), msg.box).toString(Charsets.UTF_8)
        assertEquals(Vectors.pairing.macName, name)
    }

    @Test fun pairConfirmReproducedByteForByte() {
        val box = sealWithNonce(
            v.k, Vectors.pairing.confirmNonce, Aad.pairConfirm(v.macId, v.phoneId), Vectors.pairing.macName.toByteArray(Charsets.UTF_8),
        )
        assertEquals(Vectors.pairing.pairConfirm.toHex(), WbMessage.PairConfirm(v.macId, box).encode().toHex())
    }

    @Test fun syncRequestOpensAndParses() {
        val msg = WbMessage.decode(Vectors.sync.syncRequest) as WbMessage.SyncRequest
        assertArrayEquals(v.macId, msg.macId)
        val json = WbCrypto.open(v.k, Vectors.literalAad("wb-req"), msg.box).toString(Charsets.UTF_8)
        assertEquals(Vectors.sync.requestJson, json)
        val body = WbJson.decodeFromString(SyncRequestBody.serializer(), json)
        assertEquals(listOf("Casita_Guest", "Café ☕"), body.ssids)
        assertEquals(false, body.all)
        assertEquals("AAECAwQFBgcICQoLDA0ODw", body.n)
    }

    @Test fun syncRequestReproducedByteForByte() {
        val box = sealWithNonce(v.k, Vectors.sync.requestNonce, Aad.syncRequest(v.macId, v.phoneId), Vectors.sync.requestJson.toByteArray())
        assertEquals(Vectors.sync.syncRequest.toHex(), WbMessage.SyncRequest(v.macId, box).encode().toHex())
        // and our JSON encoder writes the same request bytes as the reference implementation
        val body = WbJson.decodeFromString(SyncRequestBody.serializer(), Vectors.sync.requestJson)
        assertEquals(Vectors.sync.requestJson, WbJson.encodeToString(SyncRequestBody.serializer(), body))
    }

    @Test fun syncResponseOpens() {
        val msg = WbMessage.decode(Vectors.sync.syncResponse) as WbMessage.SyncResponse
        val json = WbCrypto.open(v.k, Vectors.literalAad("wb-resp"), msg.box).toString(Charsets.UTF_8)
        assertEquals(Vectors.sync.responseJson, json)
    }

    @Test fun syncResponseJsonAndBytesReproduced() {
        val body = SyncResponseBody(
            n = "AAECAwQFBgcICQoLDA0ODw",
            status = "ok",
            networks = listOf(WifiNetwork("Casita_Guest", "WPA", "p@ss;word", false)),
        )
        val json = WbJson.encodeToString(SyncResponseBody.serializer(), body)
        assertEquals(Vectors.sync.responseJson, json)
        val box = sealWithNonce(v.k, Vectors.sync.responseNonce, Aad.syncResponse(v.macId, v.phoneId), json.toByteArray())
        assertEquals(Vectors.sync.syncResponse.toHex(), WbMessage.SyncResponse(box).encode().toHex())
    }

    @Test fun tamperedSyncResponseRejected() {
        val msg = WbMessage.decode(Vectors.sync.tamperedSyncResponse) as WbMessage.SyncResponse
        assertOpenFails(v.k, Vectors.literalAad("wb-resp"), msg.box)
    }

    @Test fun wrongAadRejected() {
        val resp = (WbMessage.decode(Vectors.sync.syncResponse) as WbMessage.SyncResponse).box
        assertOpenFails(v.k, Vectors.literalAad("wb-req"), resp)
        assertOpenFails(v.k, ByteArray(0), resp)
        assertOpenFails(v.k, "wb-resp".toByteArray() + v.phoneId + v.macId, resp)
        val req = (WbMessage.decode(Vectors.sync.syncRequest) as WbMessage.SyncRequest).box
        assertOpenFails(v.k, Vectors.literalAad("wb-resp"), req)
        val confirm = (WbMessage.decode(Vectors.pairing.pairConfirm) as WbMessage.PairConfirm).box
        assertOpenFails(v.k, ByteArray(0), confirm)
    }

    @Test fun wrongKeyRejected() {
        val resp = (WbMessage.decode(Vectors.sync.syncResponse) as WbMessage.SyncResponse).box
        val other = v.k.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertOpenFails(other, Vectors.literalAad("wb-resp"), resp)
    }

    @Test fun randomSealHasNonceCiphertextTagLayout() {
        val pt = "hello".toByteArray()
        val aad = Vectors.literalAad("wb-resp")
        val box = WbCrypto.seal(v.k, aad, pt)
        assertEquals(12 + pt.size + 16, box.size)
        // nonce is the first 12 bytes: re-sealing with that nonce explicitly gives the same box
        assertEquals(box.toHex(), sealWithNonce(v.k, box.copyOf(12), aad, pt).toHex())
        assertArrayEquals(pt, WbCrypto.open(v.k, aad, box))
        assertTrue(!WbCrypto.seal(v.k, aad, pt).contentEquals(box)) // fresh nonce each time
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
