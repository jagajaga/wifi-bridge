package dev.wifibridge.data

import android.content.Context
import android.util.AtomicFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.io.FileNotFoundException

/**
 * One AEAD keyset, wrapped by an Android Keystore master key, encrypts every app data file.
 * Each file's name is bound in as associated data so blobs cannot be swapped between files.
 */
class SecureFiles(private val context: Context) {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun read(name: String): ByteArray? {
        val file = AtomicFile(File(context.filesDir, "$name.bin"))
        val blob = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            return null
        }
        return aead.decrypt(blob, name.toByteArray())
    }

    fun write(name: String, plaintext: ByteArray) {
        val blob = aead.encrypt(plaintext, name.toByteArray())
        val file = AtomicFile(File(context.filesDir, "$name.bin"))
        val out = file.startWrite()
        try {
            out.write(blob)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val KEYSET_NAME = "wb_data_keyset"
        const val KEYSET_PREFS = "wb_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://wb_master_key"
    }
}
