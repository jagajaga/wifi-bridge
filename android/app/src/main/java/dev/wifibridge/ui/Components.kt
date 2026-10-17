package dev.wifibridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.wifibridge.protocol.Security
import dev.wifibridge.protocol.WifiNetwork

@Composable
fun WbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Editable draft of a network, shared by manual add/edit and the import confirmation screen. */
data class NetworkDraft(
    val ssid: String = "",
    val security: String = Security.WPA,
    val password: String = "",
    val hidden: Boolean = false,
) {
    fun toNetwork(): WifiNetwork? {
        val s = ssid
        if (s.isEmpty()) return null
        if (security != Security.NOPASS && password.isEmpty()) return null
        return WifiNetwork(s, security, if (security == Security.NOPASS) "" else password, hidden)
    }

    companion object {
        fun of(n: WifiNetwork) = NetworkDraft(n.ssid, n.security, n.password, n.hidden)
    }
}

@Composable
fun NetworkFields(draft: NetworkDraft, onChange: (NetworkDraft) -> Unit, modifier: Modifier = Modifier) {
    var reveal by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.ssid,
            onValueChange = { onChange(draft.copy(ssid = it)) },
            label = { Text("Network name (SSID)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (sec in Security.ALL) {
                FilterChip(
                    selected = draft.security == sec,
                    onClick = { onChange(draft.copy(security = sec)) },
                    label = { Text(sec) },
                )
            }
        }
        if (draft.security != Security.NOPASS) {
            OutlinedTextField(
                value = draft.password,
                onValueChange = { onChange(draft.copy(password = it)) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.hidden, onCheckedChange = { onChange(draft.copy(hidden = it)) })
            Text("Hidden network", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}
