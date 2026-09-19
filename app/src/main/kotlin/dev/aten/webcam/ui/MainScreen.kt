package dev.aten.webcam.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aten.webcam.auth.AuditEntry
import dev.aten.webcam.auth.Passwords
import dev.aten.webcam.data.AppState
import dev.aten.webcam.data.ServiceStatus
import dev.aten.webcam.data.Settings
import dev.aten.webcam.data.StandbyMode
import dev.aten.webcam.media.QualityPreset
import dev.aten.webcam.service.NetworkInfo
import dev.aten.webcam.service.StreamService
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun MainScreen(settings: Settings) {
    val context = LocalContext.current
    val status by AppState.status.collectAsStateWithLifecycle()
    val audit by AppState.audit.collectAsStateWithLifecycle()
    var permissions by remember { mutableStateOf(PermissionState.read(context)) }
    var cameraDenied by remember { mutableStateOf(false) }

    // Permissions and the battery exemption are changed in system screens, so re-read them on return.
    LifecycleResumeEffect(Unit) {
        permissions = PermissionState.read(context)
        onPauseOrDispose {}
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissions = PermissionState.read(context)
        cameraDenied = !permissions.camera
        if (permissions.camera) StreamService.start(context)
    }

    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(
            status = status,
            onStart = {
                if (permissions.missing.isEmpty()) StreamService.start(context)
                else permissionLauncher.launch(permissions.missing.toTypedArray())
            },
            onStop = { stopService(context) },
        )
        if (cameraDenied) {
            Notice(
                text = "The camera permission is required. If Android no longer asks, grant it in the app's system settings.",
                action = "Open settings",
                onAction = { SystemScreens.openAppSettings(context) },
            )
        }
        if (!permissions.batteryExempt) {
            Notice(
                text = "Battery optimization can stop Android from delivering connections while the phone sleeps. " +
                    "Exempting this app keeps standby reliable; it still uses no camera or CPU until someone connects.",
                action = "Allow background activity",
                onAction = { SystemScreens.requestBatteryExemption(context) },
            )
        }
        if (status.running) AccessCard(status)
        PasswordCard(settings, context)
        SettingsCard(settings, status.running, context)
        if (audit.isNotEmpty()) AuditCard(audit)
    }
}

private fun stopService(context: Context) {
    context.startService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_STOP))
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Section("Attention") {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun StatusCard(status: ServiceStatus, onStart: () -> Unit, onStop: () -> Unit) {
    Section("IP Webcam") {
        val headline = when {
            status.streaming -> "Streaming to ${status.viewers.distinct().joinToString(", ")}"
            status.running -> "Standby · camera and microphone are off"
            else -> "Stopped"
        }
        Text(headline, style = MaterialTheme.typography.bodyLarge)
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (status.running) {
            OutlinedButton(onClick = onStop) { Text("Stop listening") }
        } else {
            Button(onClick = onStart) { Text("Start listening") }
        }
    }
}

@Composable
private fun AccessCard(status: ServiceStatus) {
    Section("Open in a browser") {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status.addresses.isEmpty()) Text("No network connection")
                status.addresses.forEach { Text(NetworkInfo.urlFor(it, status.port), fontFamily = FontFamily.Monospace) }
            }
        }
        Hint(
            "The browser will warn about the self-signed certificate. Before accepting it, compare its " +
                "SHA-256 fingerprint with the one below.",
        )
        SelectionContainer {
            Text(status.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PasswordCard(settings: Settings, context: Context) {
    var password by remember { mutableStateOf(settings.password) }
    var visible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Section("Password") {
        SelectionContainer {
            Text(if (visible) password else "•".repeat(password.length), fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") }
            OutlinedButton(onClick = { editing = true }) { Text("Change") }
        }
        Hint("Changing the password signs out every browser and disconnects current viewers.")
    }

    if (editing) {
        var draft by remember { mutableStateOf(password) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it.trim() }, singleLine = true)
                    TextButton(onClick = { draft = Passwords.generate() }) { Text("Generate a strong one") }
                    Hint("At least ${Passwords.MIN_LENGTH} characters. This is all that protects your camera.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.length >= Passwords.MIN_LENGTH,
                    onClick = {
                        settings.password = draft
                        password = draft
                        editing = false
                        StreamService.notify(context, StreamService.ACTION_PASSWORD_CHANGED)
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(settings: Settings, running: Boolean, context: Context) {
    var port by remember { mutableStateOf(settings.port.toString()) }
    var hostnames by remember { mutableStateOf(settings.hostnames.joinToString(", ")) }
    var standbyMode by remember { mutableStateOf(settings.standbyMode) }
    var quality by remember { mutableStateOf(settings.quality) }
    var trustProxy by remember { mutableStateOf(settings.trustProxyHeaders) }
    var minBattery by remember { mutableFloatStateOf(settings.minBatteryPercent.toFloat()) }

    Section("Settings") {
        OutlinedTextField(
            value = port,
            onValueChange = { text ->
                port = text.filter(Char::isDigit).take(5)
                port.toIntOrNull()?.takeIf { it in Settings.PORT_RANGE }?.let { settings.port = it }
            },
            label = { Text("Port") },
            enabled = !running,
            singleLine = true,
            isError = port.toIntOrNull() !in Settings.PORT_RANGE,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = hostnames,
            onValueChange = { text ->
                hostnames = text
                settings.hostnames = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            },
            label = { Text("Extra certificate hostnames") },
            enabled = !running,
            singleLine = true,
        )
        Hint("Comma-separated DNS names you reach the phone by, such as a dynamic-DNS name. Editable while stopped.")

        Text("Default quality", style = MaterialTheme.typography.titleSmall)
        QualityPreset.ALL.forEach { preset ->
            Choice(
                selected = quality == preset.name,
                label = "${preset.name.replaceFirstChar(Char::uppercase)} · ${preset.width}×${preset.height}, " +
                    "${preset.bitrate / 1000} kbit/s",
                onSelect = {
                    quality = preset.name
                    settings.quality = preset.name
                },
            )
        }

        Text("Standby", style = MaterialTheme.typography.titleSmall)
        StandbyMode.entries.forEach { mode ->
            Choice(
                selected = standbyMode == mode,
                label = when (mode) {
                    StandbyMode.AUTO -> "Automatic · stay awake only while charging"
                    StandbyMode.MAX_BATTERY -> "Maximum battery · let the phone sleep fully"
                    StandbyMode.ALWAYS_RELIABLE -> "Always reliable · never sleep (uses more battery)"
                },
                onSelect = {
                    standbyMode = mode
                    settings.standbyMode = mode
                    StreamService.notify(context, StreamService.ACTION_SETTINGS_CHANGED)
                },
            )
        }
        Hint("If connections time out while the screen is off and the phone is unplugged, choose Always reliable.")

        Text(
            if (minBattery < 1f) "Stop streaming on low battery: off"
            else "Stop streaming below ${minBattery.roundToInt()}% battery",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = minBattery,
            onValueChange = { minBattery = it },
            onValueChangeFinished = { settings.minBatteryPercent = minBattery.roundToInt() },
            valueRange = 0f..50f,
            steps = 9,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Behind a reverse proxy or tunnel")
                Hint("Trust CF-Connecting-IP / X-Forwarded-For for viewer addresses. Leave off when exposed directly.")
            }
            Switch(
                checked = trustProxy,
                onCheckedChange = {
                    trustProxy = it
                    settings.trustProxyHeaders = it
                },
            )
        }
    }
}

@Composable
private fun Choice(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun AuditCard(entries: List<AuditEntry>) {
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    Section("Recent connections") {
        entries.take(15).forEach { entry ->
            Column {
                Text("${entry.event} · ${entry.address}", style = MaterialTheme.typography.bodyMedium)
                Hint("${timeFormat.format(Date(entry.timeMillis))} · ${entry.userAgent.ifEmpty { "unknown client" }}")
            }
        }
    }
}
