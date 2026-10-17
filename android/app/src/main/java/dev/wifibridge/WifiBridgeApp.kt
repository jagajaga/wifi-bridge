package dev.wifibridge

import android.app.Application
import dev.wifibridge.data.IdentityRepository
import dev.wifibridge.data.PeerRepository
import dev.wifibridge.data.SecureFiles
import dev.wifibridge.data.Settings
import dev.wifibridge.data.VaultRepository
import dev.wifibridge.protocol.PhoneEngine

class WifiBridgeApp : Application() {
    val secureFiles by lazy { SecureFiles(this) }
    val identity by lazy { IdentityRepository(secureFiles) }
    val vault by lazy { VaultRepository(secureFiles) }
    val peers by lazy { PeerRepository(secureFiles) }
    val settings by lazy { Settings(this) }

    fun newEngine(): PhoneEngine = PhoneEngine(identity.identity, peers, vault::snapshot)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: WifiBridgeApp
            private set
    }
}
