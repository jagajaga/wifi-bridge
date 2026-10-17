package dev.wifibridge.protocol

class MalformedMessageException(message: String) : Exception(message)

object MsgType {
    const val PAIR_REQUEST = 0x01
    const val PAIR_ACCEPT = 0x02
    const val PAIR_CONFIRM = 0x03
    const val PAIR_DONE = 0x04
    const val SYNC_REQUEST = 0x10
    const val SYNC_RESPONSE = 0x11
    const val ERROR = 0x7F
}

object ErrorCode {
    const val UNKNOWN_MAC = 1
    const val AUTH_FAILURE = 2
    const val NO_PENDING_PAIRING = 3
    const val MALFORMED = 4
}

const val ID_SIZE = 16
const val PUB_SIZE = 32
const val PROOF_SIZE = 32
const val PROTOCOL_VERSION: Byte = 0x01

/** INFO characteristic value: 0x01 || phoneId(16) || phonePub(32). */
fun infoValue(phoneId: ByteArray, phonePub: ByteArray): ByteArray {
    require(phoneId.size == ID_SIZE && phonePub.size == PUB_SIZE)
    return byteArrayOf(PROTOCOL_VERSION) + phoneId + phonePub
}

/** One application message (after reassembly). First byte is the type. */
sealed class WbMessage {
    abstract fun encode(): ByteArray

    class PairRequest(val macId: ByteArray) : WbMessage() {
        override fun encode() = byteArrayOf(MsgType.PAIR_REQUEST.toByte()) + macId
    }

    class PairAccept(val proof: ByteArray) : WbMessage() {
        override fun encode() = byteArrayOf(MsgType.PAIR_ACCEPT.toByte()) + proof
    }

    class PairConfirm(val macId: ByteArray, val box: ByteArray) : WbMessage() {
        override fun encode() = byteArrayOf(MsgType.PAIR_CONFIRM.toByte()) + macId + box
    }

    data object PairDone : WbMessage() {
        override fun encode() = byteArrayOf(MsgType.PAIR_DONE.toByte())
    }

    class SyncRequest(val macId: ByteArray, val box: ByteArray) : WbMessage() {
        override fun encode() = byteArrayOf(MsgType.SYNC_REQUEST.toByte()) + macId + box
    }

    class SyncResponse(val box: ByteArray) : WbMessage() {
        override fun encode() = byteArrayOf(MsgType.SYNC_RESPONSE.toByte()) + box
    }

    class Error(val code: Int) : WbMessage() {
        override fun encode() = byteArrayOf(MsgType.ERROR.toByte(), code.toByte())
    }

    companion object {
        fun decode(bytes: ByteArray): WbMessage {
            if (bytes.isEmpty()) throw MalformedMessageException("empty")
            val body = bytes.copyOfRange(1, bytes.size)
            fun need(cond: Boolean, what: String) {
                if (!cond) throw MalformedMessageException("bad length for $what: ${bytes.size}")
            }
            return when (bytes[0].toInt() and 0xff) {
                MsgType.PAIR_REQUEST -> {
                    need(body.size == ID_SIZE, "PAIR_REQUEST"); PairRequest(body)
                }
                MsgType.PAIR_ACCEPT -> {
                    need(body.size == PROOF_SIZE, "PAIR_ACCEPT"); PairAccept(body)
                }
                MsgType.PAIR_CONFIRM -> {
                    need(body.size >= ID_SIZE + WbCrypto.SEAL_OVERHEAD, "PAIR_CONFIRM")
                    PairConfirm(body.copyOfRange(0, ID_SIZE), body.copyOfRange(ID_SIZE, body.size))
                }
                MsgType.PAIR_DONE -> {
                    need(body.isEmpty(), "PAIR_DONE"); PairDone
                }
                MsgType.SYNC_REQUEST -> {
                    need(body.size >= ID_SIZE + WbCrypto.SEAL_OVERHEAD, "SYNC_REQUEST")
                    SyncRequest(body.copyOfRange(0, ID_SIZE), body.copyOfRange(ID_SIZE, body.size))
                }
                MsgType.SYNC_RESPONSE -> {
                    need(body.size >= WbCrypto.SEAL_OVERHEAD, "SYNC_RESPONSE"); SyncResponse(body)
                }
                MsgType.ERROR -> {
                    need(body.size == 1, "ERROR"); Error(body[0].toInt() and 0xff)
                }
                else -> throw MalformedMessageException("unknown type ${bytes[0]}")
            }
        }
    }
}
