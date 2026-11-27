package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.SystemDiagnosticsSnapshot
import com.example.data.local.ProtocolEntity
import com.example.ui.theme.ArcAlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcGlassBorder
import com.example.ui.theme.ArcIonBlue
import com.example.ui.theme.ArcStandbyAmber
import com.example.ui.theme.ArcSuccessGreen
import com.example.ui.theme.ArcSurface
import com.example.ui.theme.ArcSurfaceElevated
import com.example.ui.theme.ArcTeal
import com.example.ui.theme.ArcTextMuted
import com.example.ui.theme.ArcTextPrimary
import com.example.ui.theme.ArcTextSecondary
import com.example.ui.theme.ArcVoid
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsHudSheet(
    snapshot: SystemDiagnosticsSnapshot,
    userName: String = "Sir",
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ArcSurface,
        contentColor = ArcTextPrimary,
        modifier = Modifier.testTag("diagnostics_hud_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = ArcCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "JARVIS SYSTEM DIAGNOSTICS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ArcCyan
                        )
                        Text(
                            text = "${snapshot.deviceModel} • ${snapshot.androidVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArcTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("refresh_diagnostics_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Diagnostics",
                        tint = ArcCyan
                    )
                }
            }

            // 2x2 Telemetry HUD Grid
            val batteryColor = when {
                snapshot.batteryPercent <= 15 -> ArcAlertCrimson
                snapshot.isCharging -> ArcSuccessGreen
                else -> ArcCyan
            }
            val ramColor = if (snapshot.ramUsedPercent >= 85) ArcStandbyAmber else ArcTeal
            val storageColor = if (snapshot.storageUsedPercent >= 88) ArcAlertCrimson else ArcIonBlue
            val thermalColor = when {
                snapshot.thermalStatus.contains("Critical", ignoreCase = true) ||
                    snapshot.thermalStatus.contains("Heavy", ignoreCase = true) -> ArcAlertCrimson
                snapshot.thermalStatus.contains("Moderate", ignoreCase = true) -> ArcStandbyAmber
                else -> ArcSuccessGreen
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryHudCard(
                    icon = if (snapshot.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    title = "Battery & Temp",
                    headline = "${snapshot.batteryPercent}%",
                    progress = snapshot.batteryPercent / 100f,
                    subtitle = "${String.format(Locale.US, "%.1f", snapshot.batteryTempC)}°C • " +
                        if (snapshot.isCharging) "Charging" else snapshot.batteryHealth,
                    accent = batteryColor,
                    modifier = Modifier.weight(1f)
                )
                TelemetryHudCard(
                    icon = Icons.Default.Memory,
                    title = "RAM Memory",
                    headline = "${snapshot.ramUsedPercent}% used",
                    progress = snapshot.ramUsedPercent / 100f,
                    subtitle = "${snapshot.ramAvailMb} MB free / ${snapshot.ramTotalMb} MB",
                    accent = ramColor,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryHudCard(
                    icon = Icons.Default.SdStorage,
                    title = "Internal Storage",
                    headline = "${snapshot.storageUsedPercent}% used",
                    progress = snapshot.storageUsedPercent / 100f,
                    subtitle = "${String.format(Locale.US, "%.1f", snapshot.storageFreeGb)} GB free / " +
                        "${String.format(Locale.US, "%.0f", snapshot.storageTotalGb)} GB",
                    accent = storageColor,
                    modifier = Modifier.weight(1f)
                )
                TelemetryHudCard(
                    icon = Icons.Default.Wifi,
                    title = "Network & Thermal",
                    headline = snapshot.networkLabel,
                    progress = null,
                    subtitle = buildString {
                        if (snapshot.linkDownMbps > 0) append("${snapshot.linkDownMbps} Mbps • ")
                        append("${snapshot.cpuCores} cores • ${snapshot.thermalStatus}")
                    },
                    accent = thermalColor,
                    modifier = Modifier.weight(1f)
                )
            }

            // Spoken Hinglish Report Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ArcIonBlue.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "SPOKEN REPORT",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = snapshot.toHinglishReport(userName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArcTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryHudCard(
    icon: ImageVector,
    title: String,
    headline: String,
    progress: Float?,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ArcSurfaceElevated),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = ArcTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = headline,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ArcTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = accent,
                    trackColor = ArcVoid.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ArcTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolsSheet(
    protocols: List<ProtocolEntity>,
    onRunProtocol: (ProtocolEntity) -> Unit,
    onDeleteProtocol: (ProtocolEntity) -> Unit,
    onCreateCustom: (
        name: String,
        description: String,
        brightnessPercent: Int,
        volumeMode: String,
        torchMode: String,
        openAppName: String,
        openBluetoothSettings: Boolean,
        openWifiPanel: Boolean,
        micMode: String,
        goStandby: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreatorDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ArcSurface,
        contentColor = ArcTextPrimary,
        modifier = Modifier.testTag("protocols_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = ArcCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "JARVIS PROTOCOLS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ArcCyan
                        )
                        Text(
                            text = "One-tap multi-action modes",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArcTextSecondary
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showCreatorDialog = true },
                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("create_custom_protocol_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Custom", color = ArcCyan)
                }
            }

            if (protocols.isEmpty()) {
                Text(
                    text = "No protocols configured yet. Tap Custom to create your first mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArcTextMuted,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(protocols, key = { it.id }) { protocol ->
                        ProtocolItemCard(
                            protocol = protocol,
                            onRun = {
                                onRunProtocol(protocol)
                                onDismiss()
                            },
                            onDelete = { onDeleteProtocol(protocol) }
                        )
                    }
                }
            }
        }
    }

    if (showCreatorDialog) {
        CreateProtocolDialog(
            onDismiss = { showCreatorDialog = false },
            onSave = { name, desc, bright, vol, torch, app, bt, wifi, mic, standby ->
                onCreateCustom(name, desc, bright, vol, torch, app, bt, wifi, mic, standby)
                showCreatorDialog = false
            }
        )
    }
}

@Composable
private fun ProtocolItemCard(
    protocol: ProtocolEntity,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val chips = remember(protocol) {
        buildList {
            if (protocol.brightnessPercent in 5..100) add("Brightness ${protocol.brightnessPercent}%")
            if (protocol.volumeMode != "UNCHANGED") add("Vol ${protocol.volumeMode}")
            if (protocol.torchMode != "UNCHANGED") add("Torch ${protocol.torchMode}")
            if (protocol.openAppName.isNotBlank()) add("Open ${protocol.openAppName}")
            if (protocol.openBluetoothSettings) add("Bluetooth")
            if (protocol.openWifiPanel) add("Wi-Fi")
            if (protocol.micMode != "UNCHANGED") add("Mic ${protocol.micMode}")
            if (protocol.goStandby) add("Standby")
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ArcSurfaceElevated),
        border = BorderStroke(1.dp, ArcGlassBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = protocol.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ArcTextPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (protocol.isBuiltIn) ArcCyan.copy(alpha = 0.16f) else ArcTeal.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = if (protocol.isBuiltIn) "PRESET" else "CUSTOM",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (protocol.isBuiltIn) ArcCyan else ArcTeal,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (protocol.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = protocol.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = ArcTextSecondary
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!protocol.isBuiltIn) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("delete_protocol_${protocol.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete ${protocol.name}",
                                tint = ArcAlertCrimson
                            )
                        }
                    }
                    Button(
                        onClick = onRun,
                        colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("run_protocol_${protocol.name.lowercase(Locale.ROOT).replace(" ", "_")}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Run", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (chips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    chips.forEach { chipLabel ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ArcVoid.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, ArcGlassBorder)
                        ) {
                            Text(
                                text = chipLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = ArcTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateProtocolDialog(
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        description: String,
        brightnessPercent: Int,
        volumeMode: String,
        torchMode: String,
        openAppName: String,
        openBluetoothSettings: Boolean,
        openWifiPanel: Boolean,
        micMode: String,
        goStandby: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var brightness by remember { mutableIntStateOf(-1) }
    var volumeMode by remember { mutableStateOf("UNCHANGED") }
    var torchMode by remember { mutableStateOf("UNCHANGED") }
    var openAppName by remember { mutableStateOf("") }
    var openBluetooth by remember { mutableStateOf(false) }
    var openWifi by remember { mutableStateOf(false) }
    var micMode by remember { mutableStateOf("UNCHANGED") }
    var goStandby by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ArcCyan,
        unfocusedBorderColor = ArcGlassBorder,
        focusedLabelColor = ArcCyan,
        unfocusedLabelColor = ArcTextSecondary,
        focusedTextColor = ArcTextPrimary,
        unfocusedTextColor = ArcTextPrimary
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArcSurface,
        title = { Text("Create Custom Protocol", color = ArcCyan, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Protocol Name (e.g. Study Mode)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("protocol_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short Description (optional)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("protocol_desc_input")
                )
                OutlinedTextField(
                    value = openAppName,
                    onValueChange = { openAppName = it },
                    label = { Text("Open App (optional, e.g. Spotify, Maps)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Brightness", style = MaterialTheme.typography.labelMedium, color = ArcTextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-1 to "Keep", 15 to "15%", 55 to "55%", 85 to "85%").forEach { (value, label) ->
                        FilterChip(
                            selected = brightness == value,
                            onClick = { brightness = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan,
                                selectedLabelColor = ArcVoid
                            )
                        )
                    }
                }

                Text("Media Volume", style = MaterialTheme.typography.labelMedium, color = ArcTextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("UNCHANGED" to "Keep", "MUTE" to "Mute", "LOW" to "Low", "MEDIUM" to "Med", "HIGH" to "High")
                        .forEach { (mode, label) ->
                            FilterChip(
                                selected = volumeMode == mode,
                                onClick = { volumeMode = mode },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ArcCyan,
                                    selectedLabelColor = ArcVoid
                                )
                            )
                        }
                }

                Text("Flashlight", style = MaterialTheme.typography.labelMedium, color = ArcTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("UNCHANGED" to "Keep", "ON" to "ON", "OFF" to "OFF").forEach { (mode, label) ->
                        FilterChip(
                            selected = torchMode == mode,
                            onClick = { torchMode = mode },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan,
                                selectedLabelColor = ArcVoid
                            )
                        )
                    }
                }

                Text("ARC Mic", style = MaterialTheme.typography.labelMedium, color = ArcTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("UNCHANGED" to "Keep", "ON" to "ON", "OFF" to "OFF").forEach { (mode, label) ->
                        FilterChip(
                            selected = micMode == mode,
                            onClick = { micMode = mode },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan,
                                selectedLabelColor = ArcVoid
                            )
                        )
                    }
                }

                ProtocolToggleRow("Open Wi-Fi Panel", openWifi) { openWifi = it }
                ProtocolToggleRow("Open Bluetooth Settings", openBluetooth) { openBluetooth = it }
                ProtocolToggleRow("Put ARC in Standby", goStandby) { goStandby = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        description.trim(),
                        brightness,
                        volumeMode,
                        torchMode,
                        openAppName.trim(),
                        openBluetooth,
                        openWifi,
                        micMode,
                        goStandby
                    )
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid),
                modifier = Modifier.testTag("save_protocol_button")
            ) {
                Text("Save Protocol", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ArcTextSecondary)
            }
        }
    )
}

@Composable
private fun ProtocolToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ArcTextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ArcVoid,
                checkedTrackColor = ArcCyan
            )
        )
    }
}
