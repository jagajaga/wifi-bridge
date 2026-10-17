package dev.wifibridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** docs/test-vectors.json, produced by the independent Python implementation. */
object Vectors {
    val file: File by lazy {
        val prop = System.getProperty("wb.vectors")
        val candidates = listOfNotNull(
            prop?.let(::File),
            File("../docs/test-vectors.json"),
            File("../../docs/test-vectors.json"),
        )
        candidates.firstOrNull { it.isFile }
            ?: error("test-vectors.json not found; tried ${candidates.map { it.absolutePath }}")
    }

    val root: JsonObject by lazy { Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject }

    private fun obj(name: String) = root.getValue(name).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.hex(k: String) = str(k).hexToBytes()

    object keys {
        private val o get() = obj("keys")
        val macPriv get() = o.hex("macPriv")
        val macPub get() = o.hex("macPub")
        val phonePriv get() = o.hex("phonePriv")
        val phonePub get() = o.hex("phonePub")
        val macId get() = o.hex("macId")
        val phoneId get() = o.hex("phoneId")
        val shared get() = o.hex("shared")
        val k get() = o.hex("K")
    }

    val info: ByteArray get() = root.getValue("info").jsonPrimitive.content.hexToBytes()

    object pairing {
        private val o get() = obj("pairing")
        val pairSecret get() = o.hex("pairSecret")
        val macName get() = o.str("macName")
        val qr get() = o.str("qr")
        val proof get() = o.hex("proof")
        val pairRequest get() = o.hex("pairRequest")
        val pairAccept get() = o.hex("pairAccept")
        val confirmNonce get() = o.hex("confirmNonce")
        val pairConfirm get() = o.hex("pairConfirm")
    }

    object sync {
        private val o get() = obj("sync")
        val requestJson get() = o.str("requestJson")
        val requestNonce get() = o.hex("requestNonce")
        val syncRequest get() = o.hex("syncRequest")
        val responseJson get() = o.str("responseJson")
        val responseNonce get() = o.hex("responseNonce")
        val syncResponse get() = o.hex("syncResponse")
        val tamperedSyncResponse get() = o.hex("tamperedSyncResponse")
    }

    class FramingCase(val message: ByteArray, val chunk: Int, val frames: List<ByteArray>)

    val framing: List<FramingCase>
        get() = root.getValue("framing").jsonArray.map { e ->
            val o = e.jsonObject
            FramingCase(o.hex("message"), o.str("chunk").toInt(), o.getValue("frames").jsonArray.map { it.jsonPrimitive.content.hexToBytes() })
        }

    class WifiCase(val input: String, val out: WifiNetwork?)

    val wifiQr: List<WifiCase>
        get() = root.getValue("wifiQr").jsonArray.map { e ->
            val o = e.jsonObject
            val out: JsonElement = o.getValue("out")
            WifiCase(
                o.str("in"),
                if (out is JsonNull) null else Json.decodeFromJsonElement(WifiNetwork.serializer(), out),
            )
        }

    fun phoneIdentity() = PhoneIdentity(
        phoneId = B64Url.encode(keys.phoneId),
        privateKey = B64Url.encode(keys.phonePriv),
        publicKey = B64Url.encode(keys.phonePub),
    )

    /** AADs spelled out from protocol.md, independent of [Aad], so a broken [Aad] cannot agree with itself. */
    fun literalAad(label: String): ByteArray = label.toByteArray(Charsets.UTF_8) + keys.macId + keys.phoneId
}
