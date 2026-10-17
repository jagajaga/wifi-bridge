package dev.wifibridge.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.wifibridge.WifiBridgeApp
import dev.wifibridge.protocol.Security
import dev.wifibridge.protocol.WifiQr
import dev.wifibridge.qr.QrImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share target for images (Samsung/Pixel "Share network" QR) and text. Nothing is saved until the
 * user confirms on the review screen.
 */
class ImportActivity : ComponentActivity() {
    private class Item(draft: NetworkDraft, val source: String) {
        val draft = mutableStateOf(draft)
        val include = mutableStateOf(true)
    }

    private val loading = mutableStateOf(true)
    private val items = mutableStateListOf<Item>()
    private val problems = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WifiBridgeApp
        setContent { WbTheme { Surface(Modifier.fillMaxSize()) { ImportScreen(app) } } }
        // Always (re)read the share: the results live in this instance's fields, so after a
        // configuration change (rotation) skipping this would leave "Reading…" up forever.
        lifecycleScope.launch { load(intent) }
    }

    private suspend fun load(intent: Intent) {
        val results = withContext(Dispatchers.Default) { readShare(intent) }
        for (r in results) {
            when (r) {
                is Found.Network -> items += Item(r.draft, r.source)
                is Found.Problem -> problems += r.message
            }
        }
        loading.value = false
    }

    private sealed interface Found {
        class Network(val draft: NetworkDraft, val source: String) : Found
        class Problem(val message: String) : Found
    }

    private fun readShare(intent: Intent): List<Found> {
        val type = intent.type ?: ""
        return when {
            type.startsWith("image/") -> imageUris(intent).map(::decodeImage)
                .ifEmpty { listOf(Found.Problem("No image was shared.")) }
            type.startsWith("text/") -> listOf(readText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()))
            else -> listOf(Found.Problem("Unsupported share type: $type"))
        }
    }

    private fun imageUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }

    private fun decodeImage(uri: Uri): Found {
        val bitmap = loadBitmap(uri) ?: return Found.Problem("Could not open the shared image.")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val text = QrImageDecoder.decode(pixels, bitmap.width, bitmap.height)
            ?: return Found.Problem("No QR code found in the shared image.")
        val net = WifiQr.parse(text) ?: return Found.Problem("The QR code is not a Wi-Fi network code.")
        return Found.Network(NetworkDraft.of(net), "QR image")
    }

    /** Decodes with a power-of-two subsample so huge screenshots do not exhaust memory. */
    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    private fun readText(text: String): Found {
        WifiQr.parse(text)?.let { return Found.Network(NetworkDraft.of(it), "shared text") }
        val password = text.trim()
        if (password.isEmpty()) return Found.Problem("The shared text was empty.")
        // Plain text: treat it as a password and let the user supply the network name.
        return Found.Network(NetworkDraft(ssid = currentSsid(this).orEmpty(), security = Security.WPA, password = password), "shared text")
    }

    @Composable
    private fun ImportScreen(app: WifiBridgeApp) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add to wifi-bridge", style = MaterialTheme.typography.headlineSmall)
            if (loading.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Reading…")
                }
                return@Column
            }
            for (p in problems) Text(p, color = MaterialTheme.colorScheme.error)
            for (item in items) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.include.value, onCheckedChange = { item.include.value = it })
                            Text("From ${item.source}", style = MaterialTheme.typography.labelMedium)
                        }
                        if (item.draft.value.ssid.isEmpty()) {
                            Text("Enter the network name for this password.", style = MaterialTheme.typography.bodySmall)
                        }
                        NetworkFields(item.draft.value, onChange = { item.draft.value = it })
                    }
                }
            }
            val chosen = items.filter { it.include.value }
            val ready = chosen.isNotEmpty() && chosen.all { it.draft.value.toNetwork() != null }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = ready, onClick = {
                    chosen.mapNotNull { it.draft.value.toNetwork() }.forEach { app.vault.upsert(it) }
                    Toast.makeText(this@ImportActivity, "Saved ${chosen.size} network(s)", Toast.LENGTH_SHORT).show()
                    finish()
                }) { Text("Save") }
                OutlinedButton(onClick = { finish() }) { Text("Cancel") }
            }
        }
    }
}

/** Current SSID, or null when unknown (needs location permission + location on). */
fun currentSsid(context: Context): String? {
    val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    val raw = wm.connectionInfo?.ssid ?: return null
    if (raw == WifiManager.UNKNOWN_SSID || raw.isBlank()) return null
    return raw.removeSurrounding("\"")
}
