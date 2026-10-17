package dev.wifibridge.protocol

import kotlinx.serialization.SerializationException

/** Chooses which vault networks answer a request (protocol.md "Sync"). */
object SyncMatcher {
    fun select(request: SyncRequestBody, vault: List<WifiNetwork>): List<WifiNetwork> {
        if (request.all) return vault
        val wanted = request.ssids.toHashSet()
        return vault.filter { it.ssid in wanted } // exact, case-sensitive
    }

    fun respond(request: SyncRequestBody, vault: List<WifiNetwork>, status: SyncStatus) = SyncResponseBody(
        n = request.n,
        status = status.wire,
        networks = if (status == SyncStatus.OK) select(request, vault) else emptyList(),
    )
}

sealed interface Outcome {
    /** Send [message] back to the Mac now. */
    class Reply(val message: ByteArray) : Outcome

    /** A paired Mac with auto-share off asked for networks; call [complete] once the user decides. */
    class NeedsApproval(
        val mac: PairedMac,
        val request: SyncRequestBody,
        private val finish: (SyncStatus) -> ByteArray,
    ) : Outcome {
        fun complete(status: SyncStatus): ByteArray = finish(status)
    }
}

/**
 * Phone-side protocol state machine: pairing and sync. No Android dependencies; the BLE service feeds it
 * reassembled messages and sends whatever bytes it returns.
 */
class PhoneEngine(
    private val identity: PhoneIdentity,
    private val peers: PeerStore,
    private val vault: () -> List<WifiNetwork>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPaired: (PairedMac) -> Unit = {},
) {
    private val phoneId = identity.phoneIdBytes
    private val phonePriv = identity.privateKeyBytes
    private val phonePub = identity.publicKeyBytes

    fun info(): ByteArray = infoValue(phoneId, phonePub)

    fun handle(raw: ByteArray): Outcome {
        val msg = try {
            WbMessage.decode(raw)
        } catch (e: MalformedMessageException) {
            return error(ErrorCode.MALFORMED)
        }
        return when (msg) {
            is WbMessage.PairRequest -> onPairRequest(msg)
            is WbMessage.PairConfirm -> onPairConfirm(msg)
            is WbMessage.SyncRequest -> onSyncRequest(msg)
            else -> error(ErrorCode.MALFORMED) // phone->Mac types are not valid here
        }
    }

    private fun livePending(macId: ByteArray): PendingPairing? {
        val p = peers.pending(B64Url.encode(macId)) ?: return null
        return if (p.isExpired(clock())) null else p
    }

    private fun onPairRequest(msg: WbMessage.PairRequest): Outcome {
        val pending = livePending(msg.macId) ?: return error(ErrorCode.NO_PENDING_PAIRING)
        val proof = WbCrypto.pairProof(B64Url.decode(pending.pairSecret), msg.macId, phoneId, phonePub)
        return Outcome.Reply(WbMessage.PairAccept(proof).encode())
    }

    private fun onPairConfirm(msg: WbMessage.PairConfirm): Outcome {
        val pending = livePending(msg.macId) ?: return error(ErrorCode.NO_PENDING_PAIRING)
        val macPub = B64Url.decode(pending.macPub)
        val k = WbCrypto.phoneSessionKey(phonePriv, phonePub, macPub)
        val name = try {
            WbCrypto.open(k, Aad.pairConfirm(msg.macId, phoneId), msg.box).toString(Charsets.UTF_8)
        } catch (e: AuthenticationException) {
            return error(ErrorCode.AUTH_FAILURE)
        }
        val mac = PairedMac(
            macId = pending.macId,
            macPub = pending.macPub,
            name = name.ifBlank { pending.macName },
            autoShare = true,
            pairedAtMillis = clock(),
        )
        peers.savePaired(mac)
        peers.removePending(pending.macId)
        onPaired(mac)
        return Outcome.Reply(WbMessage.PairDone.encode())
    }

    private fun onSyncRequest(msg: WbMessage.SyncRequest): Outcome {
        val mac = peers.paired(B64Url.encode(msg.macId)) ?: return error(ErrorCode.UNKNOWN_MAC)
        val k = WbCrypto.phoneSessionKey(phonePriv, phonePub, B64Url.decode(mac.macPub))
        val json = try {
            WbCrypto.open(k, Aad.syncRequest(msg.macId, phoneId), msg.box)
        } catch (e: AuthenticationException) {
            return error(ErrorCode.AUTH_FAILURE)
        }
        val request = try {
            WbJson.decodeFromString(SyncRequestBody.serializer(), json.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            return error(ErrorCode.MALFORMED)
        } catch (e: IllegalArgumentException) {
            return error(ErrorCode.MALFORMED)
        }
        val respond = { status: SyncStatus -> sealResponse(k, msg.macId, SyncMatcher.respond(request, vault(), status)) }
        return if (mac.autoShare) Outcome.Reply(respond(SyncStatus.OK))
        else Outcome.NeedsApproval(mac, request, respond)
    }

    private fun sealResponse(k: ByteArray, macId: ByteArray, body: SyncResponseBody): ByteArray {
        val json = WbJson.encodeToString(SyncResponseBody.serializer(), body).utf8()
        return WbMessage.SyncResponse(WbCrypto.seal(k, Aad.syncResponse(macId, phoneId), json)).encode()
    }

    private fun error(code: Int) = Outcome.Reply(WbMessage.Error(code).encode())
}
