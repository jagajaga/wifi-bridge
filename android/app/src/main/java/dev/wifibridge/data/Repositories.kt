package dev.wifibridge.data

import android.content.Context
import dev.wifibridge.protocol.PairedMac
import dev.wifibridge.protocol.PendingPairing
import dev.wifibridge.protocol.PeerStore
import dev.wifibridge.protocol.PhoneIdentity
import dev.wifibridge.protocol.WbJson
import dev.wifibridge.protocol.WifiNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

class IdentityRepository(private val files: SecureFiles) {
    val identity: PhoneIdentity by lazy {
        synchronized(this) {
            files.read(NAME)?.let { WbJson.decodeFromString(PhoneIdentity.serializer(), it.decodeToString()) }
                ?: PhoneIdentity.generate().also {
                    files.write(NAME, WbJson.encodeToString(PhoneIdentity.serializer(), it).toByteArray())
                }
        }
    }

    private companion object { const val NAME = "identity" }
}

/** The phone's own Wi-Fi vault, keyed by exact SSID. */
class VaultRepository(private val files: SecureFiles) {
    private val serializer = ListSerializer(WifiNetwork.serializer())
    private val state = MutableStateFlow(load())
    val networks: StateFlow<List<WifiNetwork>> = state

    private fun load(): List<WifiNetwork> =
        files.read(NAME)?.let { WbJson.decodeFromString(serializer, it.decodeToString()) } ?: emptyList()

    @Synchronized
    fun upsert(network: WifiNetwork, replacingSsid: String? = null) {
        val rest = state.value.filter { it.ssid != network.ssid && it.ssid != replacingSsid }
        save((rest + network).sortedBy { it.ssid.lowercase() })
    }

    @Synchronized
    fun delete(ssid: String) = save(state.value.filter { it.ssid != ssid })

    fun snapshot(): List<WifiNetwork> = state.value

    private fun save(list: List<WifiNetwork>) {
        files.write(NAME, WbJson.encodeToString(serializer, list).toByteArray())
        state.value = list
    }

    private companion object { const val NAME = "vault" }
}

@Serializable
data class PeerState(val pending: List<PendingPairing> = emptyList(), val paired: List<PairedMac> = emptyList())

class PeerRepository(private val files: SecureFiles, private val clock: () -> Long = System::currentTimeMillis) : PeerStore {
    private val state = MutableStateFlow(load())
    val peers: StateFlow<PeerState> = state

    private fun load(): PeerState =
        files.read(NAME)?.let { WbJson.decodeFromString(PeerState.serializer(), it.decodeToString()) } ?: PeerState()

    @Synchronized
    fun addPending(p: PendingPairing) {
        val now = clock()
        val kept = state.value.pending.filter { it.macId != p.macId && !it.isExpired(now) }
        save(state.value.copy(pending = kept + p))
    }

    @Synchronized
    fun setAutoShare(macId: String, enabled: Boolean) =
        save(state.value.copy(paired = state.value.paired.map { if (it.macId == macId) it.copy(autoShare = enabled) else it }))

    @Synchronized
    fun unpair(macId: String) = save(state.value.copy(paired = state.value.paired.filter { it.macId != macId }))

    @Synchronized
    fun cancelPending(macId: String) = removePending(macId)

    // PeerStore (called by the protocol engine from the GATT thread)
    @Synchronized
    override fun pending(macId: String): PendingPairing? = state.value.pending.firstOrNull { it.macId == macId }

    @Synchronized
    override fun removePending(macId: String) = save(state.value.copy(pending = state.value.pending.filter { it.macId != macId }))

    @Synchronized
    override fun paired(macId: String): PairedMac? = state.value.paired.firstOrNull { it.macId == macId }

    @Synchronized
    override fun savePaired(mac: PairedMac) =
        save(state.value.copy(paired = state.value.paired.filter { it.macId != mac.macId } + mac))

    private fun save(s: PeerState) {
        files.write(NAME, WbJson.encodeToString(PeerState.serializer(), s).toByteArray())
        state.value = s
    }

    private companion object { const val NAME = "peers" }
}

/** Non-secret switches. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _serviceEnabled = MutableStateFlow(prefs.getBoolean(KEY_SERVICE, false))
    private val _startOnBoot = MutableStateFlow(prefs.getBoolean(KEY_BOOT, false))
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled
    val startOnBoot: StateFlow<Boolean> = _startOnBoot

    fun setServiceEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_SERVICE, v).apply(); _serviceEnabled.value = v }
    fun setStartOnBoot(v: Boolean) { prefs.edit().putBoolean(KEY_BOOT, v).apply(); _startOnBoot.value = v }

    private companion object {
        const val KEY_SERVICE = "service_enabled"
        const val KEY_BOOT = "start_on_boot"
    }
}
