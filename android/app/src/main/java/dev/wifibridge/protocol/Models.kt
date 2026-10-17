package dev.wifibridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Security {
    const val WPA = "WPA"
    const val WEP = "WEP"
    const val SAE = "SAE"
    const val NOPASS = "nopass"
    val ALL = listOf(WPA, SAE, WEP, NOPASS)
}

/** One vault entry; also the wire shape of a `networks[]` item. */
@Serializable
data class WifiNetwork(
    val ssid: String,
    val security: String,
    val password: String,
    val hidden: Boolean = false,
)

@Serializable
data class SyncRequestBody(
    val n: String,
    val ssids: List<String> = emptyList(),
    val all: Boolean = false,
)

@Serializable
data class SyncResponseBody(
    val n: String,
    val status: String,
    val networks: List<WifiNetwork> = emptyList(),
)

enum class SyncStatus(val wire: String) { OK("ok"), DENIED("denied"), TIMEOUT("timeout") }

/** Field names and order match protocol.md; defaults are emitted so `hidden:false` is on the wire. */
val WbJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
