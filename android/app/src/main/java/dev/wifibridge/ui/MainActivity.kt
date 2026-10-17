package dev.wifibridge.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.wifibridge.WifiBridgeApp
import dev.wifibridge.ble.BlePermissions
import dev.wifibridge.ble.BridgeService
import dev.wifibridge.protocol.PairingQr
import dev.wifibridge.protocol.WifiNetwork
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WifiBridgeApp
        setContent { WbTheme { Surface(Modifier.fillMaxSize()) { MainScreen(app) } } }
    }
}

@Composable
private fun MainScreen(app: WifiBridgeApp) {
    val ctx = LocalContext.current
    val networks by app.vault.networks.collectAsState()
    val peers by app.peers.peers.collectAsState()
    val serviceEnabled by app.settings.serviceEnabled.collectAsState()
    val startOnBoot by app.settings.startOnBoot.collectAsState()
    val running by BridgeService.running.collectAsState()
    val status by BridgeService.statusFlow.collectAsState()
    var editing by remember { mutableStateOf<Pair<WifiNetwork?, NetworkDraft>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BlePermissions.hasRequired(ctx)) {
            app.settings.setServiceEnabled(true)
            BridgeService.start(ctx)
        } else {
            Toast.makeText(ctx, "Bluetooth permission is needed to talk to the Mac", Toast.LENGTH_LONG).show()
        }
    }
    fun enableService() {
        if (BlePermissions.hasRequired(ctx) && BlePermissions.canNotify(ctx)) {
            app.settings.setServiceEnabled(true)
            BridgeService.start(ctx)
        } else {
            permissionLauncher.launch(BlePermissions.toRequest())
        }
    }
    LaunchedEffect(serviceEnabled) {
        if (serviceEnabled && !BridgeService.running.value && BlePermissions.hasRequired(ctx)) BridgeService.start(ctx)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val qr = PairingQr.parse(text)
        if (qr == null) {
            Toast.makeText(ctx, "That is not a wifi-bridge pairing code", Toast.LENGTH_LONG).show()
        } else {
            app.peers.addPending(qr.toPending(System.currentTimeMillis()))
            Toast.makeText(ctx, "Ready to pair with ${qr.macName} for 10 minutes", Toast.LENGTH_LONG).show()
            if (!serviceEnabled) enableService()
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("wifi-bridge", style = MaterialTheme.typography.headlineSmall)

        SectionTitle("Bridge")
        LabeledSwitch("Answer nearby Macs over Bluetooth", serviceEnabled) { on ->
            if (on) enableService() else {
                app.settings.setServiceEnabled(false)
                BridgeService.stop(ctx)
            }
        }
        Text(
            if (running) "Running — $status" else "Stopped" + if (serviceEnabled) " ($status)" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        LabeledSwitch("Start when the phone boots", startOnBoot) { app.settings.setStartOnBoot(it) }

        SectionTitle("Paired Macs")
        for (p in peers.pending.filter { !it.isExpired(now) }) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.macName)
                        val mins = ((p.createdAtMillis + 600_000 - now) / 60_000).coerceAtLeast(0)
                        Text("Waiting for the Mac to connect (~$mins min left)", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { app.peers.cancelPending(p.macId) }) { Text("Cancel") }
                }
            }
        }
        if (peers.paired.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodyMedium)
        for (mac in peers.paired) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(mac.name, style = MaterialTheme.typography.titleSmall)
                    LabeledSwitch("Share automatically", mac.autoShare) { app.peers.setAutoShare(mac.macId, it) }
                    if (!mac.autoShare && !BlePermissions.canNotify(ctx)) {
                        Text(
                            "Notifications are off, so requests from this Mac will time out.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { app.peers.unpair(mac.macId) }) { Text("Unpair") }
                }
            }
        }
        OutlinedButton(onClick = {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the pairing code shown by the Mac")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN),
            )
        }) { Text("Pair a Mac") }

        SectionTitle("Networks")
        Text(
            "Add networks by sharing the QR image from Wi-Fi settings → network → Share, or add them here.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (networks.isEmpty()) Text("No networks yet.", style = MaterialTheme.typography.bodyMedium)
        for (n in networks) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.ssid)
                        Text(n.security + if (n.hidden) " · hidden" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { editing = n to NetworkDraft.of(n) }) { Text("Edit") }
                    TextButton(onClick = { app.vault.delete(n.ssid) }) { Text("Delete") }
                }
            }
        }
        Button(onClick = { editing = null to NetworkDraft() }) { Text("Add network") }
        Spacer(Modifier.width(1.dp).padding(bottom = 32.dp))
    }

    editing?.let { (original, draft) ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (original == null) "Add network" else "Edit network") },
            text = { NetworkFields(draft, onChange = { editing = original to it }) },
            confirmButton = {
                TextButton(enabled = draft.toNetwork() != null, onClick = {
                    draft.toNetwork()?.let { app.vault.upsert(it, replacingSsid = original?.ssid) }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
