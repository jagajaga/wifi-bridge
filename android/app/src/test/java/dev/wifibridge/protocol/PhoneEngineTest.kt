package dev.wifibridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneEngineTest {
    private val v = Vectors.keys
    private val macIdB64 = B64Url.encode(v.macId)
    private val t0 = 1_700_000_000_000L
    private var now = t0
    private val store = InMemoryPeerStore()
    private var vault = listOf(
        WifiNetwork("Casita_Guest", "WPA", "p@ss;word", false),
        WifiNetwork("casita_guest", "WPA", "lowercase-twin", false),
        WifiNetwork("CASITA_GUEST", "WPA", "uppercase-twin", false),
        WifiNetwork("Neighbour", "SAE", "nope", true),
    )
    private val engine = PhoneEngine(Vectors.phoneIdentity(), store, { vault }, { now })

    private fun reply(raw: ByteArray): ByteArray = (engine.handle(raw) as Outcome.Reply).message

    private fun addPending() {
        store.pendings[macIdB64] = PairingQr.parse(Vectors.pairing.qr)!!.toPending(t0)
    }

    private fun pairDirectly(autoShare: Boolean = true) {
        store.paireds[macIdB64] = PairedMac(macIdB64, B64Url.encode(v.macPub), "Mac", autoShare)
    }

    private fun openResponse(bytes: ByteArray): SyncResponseBody {
        val msg = WbMessage.decode(bytes) as WbMessage.SyncResponse
        val json = WbCrypto.open(v.k, Vectors.literalAad("wb-resp"), msg.box).toString(Charsets.UTF_8)
        return WbJson.decodeFromString(SyncResponseBody.serializer(), json)
    }

    private fun sealedRequest(json: String): ByteArray =
        WbMessage.SyncRequest(v.macId, WbCrypto.seal(v.k, Vectors.literalAad("wb-req"), json.toByteArray())).encode()

    // ---- pairing ----

    @Test fun pairRequestWithoutPendingIsError3() {
        assertEquals("7f03", reply(Vectors.pairing.pairRequest).toHex())
    }

    @Test fun pairRequestFromOtherMacIsError3() {
        addPending()
        val other = WbMessage.PairRequest(ByteArray(16) { 9 }).encode()
        assertEquals("7f03", reply(other).toHex())
    }

    @Test fun pairRequestWithPendingAcceptsWithVectorProof() {
        addPending()
        now = t0 + PendingPairing.TTL_MILLIS - 1
        assertEquals(Vectors.pairing.pairAccept.toHex(), reply(Vectors.pairing.pairRequest).toHex())
    }

    @Test fun expiredPendingIsError3ForRequestAndConfirm() {
        addPending()
        now = t0 + PendingPairing.TTL_MILLIS
        assertEquals("7f03", reply(Vectors.pairing.pairRequest).toHex())
        assertEquals("7f03", reply(Vectors.pairing.pairConfirm).toHex())
        assertNull(store.paired(macIdB64))
    }

    @Test fun badConfirmIsError2AndNothingStored() {
        addPending()
        val bad = Vectors.pairing.pairConfirm.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals("7f02", reply(bad).toHex())
        assertNull(store.paired(macIdB64))
        assertNotNull(store.pending(macIdB64))
    }

    @Test fun confirmSealedWithWrongAadIsError2() {
        addPending()
        val box = WbCrypto.seal(v.k, Vectors.literalAad("wb-req"), "Jaga".toByteArray())
        assertEquals("7f02", reply(WbMessage.PairConfirm(v.macId, box).encode()).toHex())
    }

    @Test fun goodConfirmIsPairDoneAndStored() {
        addPending()
        assertEquals(Vectors.pairing.pairAccept.toHex(), reply(Vectors.pairing.pairRequest).toHex())
        assertEquals("04", reply(Vectors.pairing.pairConfirm).toHex())
        val mac = store.paired(macIdB64)!!
        assertEquals(Vectors.pairing.macName, mac.name)
        assertArrayEquals(v.macPub, B64Url.decode(mac.macPub))
        assertTrue(mac.autoShare)
        assertNull(store.pending(macIdB64))
        // pending is consumed: a replayed confirm cannot re-pair
        assertEquals("7f03", reply(Vectors.pairing.pairConfirm).toHex())
    }

    // ---- sync ----

    @Test fun syncFromUnknownMacIsError1() {
        addPending() // pending is not paired
        assertEquals("7f01", reply(Vectors.sync.syncRequest).toHex())
    }

    @Test fun syncVectorRequestReturnsExactSubsetAndEchoesNonce() {
        pairDirectly()
        val resp = openResponse(reply(Vectors.sync.syncRequest))
        assertEquals("AAECAwQFBgcICQoLDA0ODw", resp.n)
        assertEquals("ok", resp.status)
        assertEquals(listOf(WifiNetwork("Casita_Guest", "WPA", "p@ss;word", false)), resp.networks)
    }

    @Test fun syncVectorResponseJsonIsByteIdentical() {
        vault = listOf(WifiNetwork("Casita_Guest", "WPA", "p@ss;word", false), WifiNetwork("Other", "WPA", "x"))
        pairDirectly()
        val msg = WbMessage.decode(reply(Vectors.sync.syncRequest)) as WbMessage.SyncResponse
        val json = WbCrypto.open(v.k, Vectors.literalAad("wb-resp"), msg.box).toString(Charsets.UTF_8)
        assertEquals(Vectors.sync.responseJson, json)
    }

    @Test fun ssidMatchIsCaseSensitive() {
        pairDirectly()
        val resp = openResponse(reply(sealedRequest("""{"n":"bm9uY2U","ssids":["CASITA_GUEST"],"all":false}""")))
        assertEquals(listOf("CASITA_GUEST"), resp.networks.map { it.ssid })
        val none = openResponse(reply(sealedRequest("""{"n":"x","ssids":["Casita_guest"]}""")))
        assertEquals(emptyList<WifiNetwork>(), none.networks)
    }

    @Test fun allTrueReturnsEverythingAndIgnoresSsids() {
        pairDirectly()
        val resp = openResponse(reply(sealedRequest("""{"n":"YWxs","ssids":["Neighbour"],"all":true}""")))
        assertEquals("YWxs", resp.n)
        assertEquals(vault, resp.networks)
    }

    @Test fun syncWithBadBoxIsError2() {
        pairDirectly()
        val bad = Vectors.sync.syncRequest.copyOf().also { it[30] = (it[30].toInt() xor 0x40).toByte() }
        assertEquals("7f02", reply(bad).toHex())
    }

    @Test fun syncWithBadJsonIsError4() {
        pairDirectly()
        assertEquals("7f04", reply(sealedRequest("not json")).toHex())
    }

    @Test fun malformedMessagesAreError4() {
        assertEquals("7f04", reply(ByteArray(0)).toHex())
        assertEquals("7f04", reply(byteArrayOf(0x01, 0x02)).toHex())
        assertEquals("7f04", reply(byteArrayOf(0x55)).toHex())
        assertEquals("7f04", reply(WbMessage.PairDone.encode()).toHex())
    }

    @Test fun autoShareOffNeedsApprovalThenAnswers() {
        pairDirectly(autoShare = false)
        val outcome = engine.handle(Vectors.sync.syncRequest) as Outcome.NeedsApproval
        assertEquals(listOf("Casita_Guest", "Café ☕"), outcome.request.ssids)

        val ok = openResponse(outcome.complete(SyncStatus.OK))
        assertEquals("ok", ok.status)
        assertEquals(listOf("Casita_Guest"), ok.networks.map { it.ssid })

        val denied = openResponse(outcome.complete(SyncStatus.DENIED))
        assertEquals("AAECAwQFBgcICQoLDA0ODw", denied.n)
        assertEquals("denied", denied.status)
        assertEquals(emptyList<WifiNetwork>(), denied.networks)

        val timeout = openResponse(outcome.complete(SyncStatus.TIMEOUT))
        assertEquals("timeout", timeout.status)
        assertEquals(emptyList<WifiNetwork>(), timeout.networks)
    }

    @Test fun matcherDirect() {
        val req = SyncRequestBody(n = "q", ssids = listOf("Neighbour", "Missing"))
        assertEquals(listOf("Neighbour"), SyncMatcher.select(req, vault).map { it.ssid })
        assertEquals(vault, SyncMatcher.select(req.copy(all = true), vault))
        assertEquals("q", SyncMatcher.respond(req, vault, SyncStatus.DENIED).n)
    }
}
