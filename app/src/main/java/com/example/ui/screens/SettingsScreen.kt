package com.example.ui.screens

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.prefs.MaleVoicePreset
import com.example.data.prefs.PersonalInfo
import com.example.data.prefs.VoiceGender
import com.example.data.prefs.VoicePathStatus
import com.example.service.ArcAccessibilityService
import com.example.service.ArcDeviceAdminReceiver
import com.example.service.ArcNotificationListenerService
import com.example.ui.ArcViewModel
import com.example.ui.components.ArcBackgroundGrid
import com.example.ui.theme.ArcAlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcGlassBorder
import com.example.ui.theme.ArcStandbyAmber
import com.example.ui.theme.ArcSuccessGreen
import com.example.ui.theme.ArcSurface
import com.example.ui.theme.ArcSurfaceElevated
import com.example.ui.theme.ArcTeal
import com.example.ui.theme.ArcTextMuted
import com.example.ui.theme.ArcTextPrimary
import com.example.ui.theme.ArcTextSecondary
import com.example.ui.theme.ArcVoid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ArcViewModel,
    onBack: () -> Unit,
    onOpenPermissionsReview: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voicePathStatus by viewModel.voicePathStatus.collectAsStateWithLifecycle()
    val ttsDiagnostic by viewModel.ttsDiagnostic.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    var fullName by remember(settings.personalInfo) { mutableStateOf(settings.personalInfo.fullName) }
    var email by remember(settings.personalInfo) { mutableStateOf(settings.personalInfo.email) }
    var phone by remember(settings.personalInfo) { mutableStateOf(settings.personalInfo.phone) }
    var address by remember(settings.personalInfo) { mutableStateOf(settings.personalInfo.address) }
    var city by remember(settings.personalInfo) { mutableStateOf(settings.personalInfo.city) }

    var newMemoryText by remember { mutableStateOf("") }
    var showAddReminderDialog by remember { mutableStateOf(false) }

    val monitoredApps = remember {
        listOf(
            "com.whatsapp" to "WhatsApp",
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.messaging" to "Messages",
            "com.instagram.android" to "Instagram"
        )
    }

    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    val adminComponent = remember { ComponentName(context, ArcDeviceAdminReceiver::class.java) }

    Box(modifier = Modifier.fillMaxSize()) {
        ArcBackgroundGrid()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("ARC Settings", color = ArcCyan, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = ArcCyan
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.statusBarsPadding()
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1) VOICE SETTINGS
                item {
                    val elevenConfigured = remember {
                        val k = BuildConfig.ELEVENLABS_API_KEY.trim()
                        k.isNotBlank() && !k.startsWith("MY_")
                    }
                    SettingsSectionCard(icon = Icons.Default.RecordVoiceOver, title = "Voice & Cadence") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = settings.activeVoiceDisplayLabel(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ArcTextPrimary
                                )
                                Text(
                                    text = "Wideband speaker/headset audio routing, natural breath pauses & ElevenLabs 44.1kHz HD synthesis.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ArcTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.voiceGender == VoiceGender.FEMALE,
                                onCheckedChange = { isFemale ->
                                    viewModel.setVoiceGender(if (isFemale) VoiceGender.FEMALE else VoiceGender.MALE)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ArcVoid,
                                    checkedTrackColor = ArcCyan,
                                    uncheckedThumbColor = ArcCyan,
                                    uncheckedTrackColor = ArcSurfaceElevated
                                ),
                                modifier = Modifier.testTag("voice_gender_switch")
                            )
                        }

                        AnimatedVisibility(visible = settings.voiceGender == VoiceGender.MALE) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Deep Male JARVIS Character",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ArcCyan
                                )
                                MaleVoicePreset.entries.forEach { preset ->
                                    val selected = settings.maleVoicePreset == preset
                                    Surface(
                                        onClick = { viewModel.setMaleVoicePreset(preset) },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) ArcCyan.copy(alpha = 0.14f) else ArcSurfaceElevated,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) ArcCyan else ArcGlassBorder
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 52.dp)
                                            .testTag("male_preset_${preset.name.lowercase()}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = preset.label,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = if (selected) ArcCyan else ArcTextPrimary
                                                )
                                                Text(
                                                    text = preset.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = ArcTextSecondary
                                                )
                                            }
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Selected",
                                                    tint = ArcCyan,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (elevenConfigured) ArcTeal.copy(alpha = 0.10f) else ArcStandbyAmber.copy(alpha = 0.12f),
                            border = BorderStroke(
                                1.dp,
                                if (elevenConfigured) ArcTeal.copy(alpha = 0.4f) else ArcStandbyAmber.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (elevenConfigured) ArcTeal else ArcStandbyAmber
                                )
                                Column {
                                    Text(
                                        text = when {
                                            voicePathStatus == VoicePathStatus.LIVE_HD -> "Active Path: Gemini Live HD (Wideband)"
                                            elevenConfigured -> "Text & Vision Voice: ElevenLabs HD Ready (44.1kHz)"
                                            else -> "Text & Vision Voice: Device Fallback (Add ELEVENLABS_API_KEY in Secrets)"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (elevenConfigured || voicePathStatus == VoicePathStatus.LIVE_HD) ArcTeal else ArcStandbyAmber
                                    )
                                    ttsDiagnostic?.let { diag ->
                                        Text(
                                            text = diag,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ArcTextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "ARC sirf tab sunta hai jab aap mic ON karte ho — 'Hey ARC' bolne ki zarurat nahi, bas mic-button dabao aur seedha baat karo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArcCyan
                        )
                    }
                }

                // 2) PROACTIVE ALERTS (BATTERY & DATA SAVER)
                item {
                    SettingsSectionCard(icon = Icons.Default.BatteryAlert, title = "Proactive Battery & Data Alerts") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Proactive Alerts: ${if (settings.proactiveAlertsEnabled) "ON" else "OFF"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (settings.proactiveAlertsEnabled) ArcCyan else ArcTextSecondary
                                )
                                Text(
                                    text = "One-time spoken warnings at 20%, 15% & 10% battery, charging start confirmation, and Wi-Fi reminders on heavy mobile-data actions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ArcTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.proactiveAlertsEnabled,
                                onCheckedChange = { viewModel.setProactiveAlertsEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ArcVoid,
                                    checkedTrackColor = ArcCyan,
                                    uncheckedThumbColor = ArcTextSecondary,
                                    uncheckedTrackColor = ArcSurfaceElevated
                                ),
                                modifier = Modifier.testTag("proactive_alerts_switch")
                            )
                        }
                    }
                }

                // 3) NOTIFICATIONS READ-ALOUD CONTROLS
                item {
                    SettingsSectionCard(icon = Icons.Default.Notifications, title = "Notifications Read-Aloud") {
                        val listenerGranted = ArcNotificationListenerService.isPermissionGranted(context)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (listenerGranted) "Notification Listener: Active" else "Notification Access needed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (listenerGranted) ArcSuccessGreen else ArcAlertCrimson
                            )
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) {
                                Text("System Access", color = ArcCyan)
                            }
                        }
                        HorizontalDivider(color = ArcGlassBorder, modifier = Modifier.padding(vertical = 6.dp))
                        monitoredApps.forEach { (pkg, label) ->
                            val enabled = settings.enabledNotificationApps.contains(pkg)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = ArcTextPrimary, style = MaterialTheme.typography.bodyLarge)
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { viewModel.toggleNotificationApp(pkg, it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = ArcVoid, checkedTrackColor = ArcCyan)
                                )
                            }
                        }
                    }
                }

                // 3) REMINDERS & RECURRING ALARMS
                item {
                    SettingsSectionCard(icon = Icons.Default.Schedule, title = "Reminders & Recurring Alarms") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${reminders.size} scheduled",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextSecondary
                            )
                            OutlinedButton(
                                onClick = { showAddReminderDialog = true },
                                border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = ArcCyan)
                                Spacer(Modifier.width(4.dp))
                                Text("Add Alarm", color = ArcCyan)
                            }
                        }

                        if (reminders.isEmpty()) {
                            Text(
                                text = "No reminders or recurring alarms yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextMuted,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                reminders.forEach { item ->
                                    val timeFmt = Instant.ofEpochMilli(item.triggerAtMillis)
                                        .atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title, color = ArcTextPrimary, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = "$timeFmt • ${item.recurrence}",
                                                color = ArcTextSecondary,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        Switch(
                                            checked = item.isEnabled,
                                            onCheckedChange = { viewModel.toggleReminder(item) },
                                            colors = SwitchDefaults.colors(checkedThumbColor = ArcVoid, checkedTrackColor = ArcTeal)
                                        )
                                        IconButton(onClick = { viewModel.deleteReminder(item.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete reminder", tint = ArcAlertCrimson)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4) LONG-TERM MEMORY
                item {
                    SettingsSectionCard(icon = Icons.Default.Psychology, title = "Long-Term Memory") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newMemoryText,
                                onValueChange = { newMemoryText = it },
                                placeholder = { Text("Add non-sensitive fact or preference...") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ArcCyan,
                                    unfocusedBorderColor = ArcGlassBorder,
                                    focusedTextColor = ArcTextPrimary,
                                    unfocusedTextColor = ArcTextPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newMemoryText.isNotBlank()) {
                                        viewModel.addMemoryFact(newMemoryText)
                                        newMemoryText = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid)
                            ) {
                                Text("Save")
                            }
                        }

                        if (memories.isEmpty()) {
                            Text(
                                text = "No memory facts saved yet. Tell ARC what you'd like remembered.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextMuted,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 10.dp)
                            ) {
                                memories.forEach { fact ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "• ${fact.factText}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = ArcTextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { viewModel.deleteMemoryFact(fact.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete fact", tint = ArcTextSecondary)
                                        }
                                    }
                                }
                                TextButton(
                                    onClick = { viewModel.wipeAllMemories() },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Wipe All Memory", color = ArcAlertCrimson)
                                }
                            }
                        }
                    }
                }

                // 5) PERSONAL INFO FOR FORMS AUTO-FILL
                item {
                    SettingsSectionCard(icon = Icons.Default.Person, title = "Personal Info (Forms Auto-Fill)") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ArcField("Full Name", fullName) { fullName = it }
                            ArcField("Email Address", email) { email = it }
                            ArcField("Phone Number", phone) { phone = it }
                            ArcField("Street Address", address) { address = it }
                            ArcField("City", city) { city = it }
                            Button(
                                onClick = {
                                    viewModel.savePersonalInfo(PersonalInfo(fullName, email, phone, address, city))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid)
                            ) {
                                Text("Save Auto-Fill Profile", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // 6) PERMISSIONS OVERVIEW
                item {
                    SettingsSectionCard(icon = Icons.Default.Security, title = "Permissions & System Services") {
                        val accOn = ArcAccessibilityService.isEnabled(context)
                        val notifOn = ArcNotificationListenerService.isPermissionGranted(context)
                        val adminOn = dpm.isAdminActive(adminComponent)
                        val overlayOn = Settings.canDrawOverlays(context)
                        val smsOn = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

                        PermissionStatusLine("SMS Read & Send (Two-Way)", smsOn)
                        PermissionStatusLine("Accessibility Screen Vision", accOn)
                        PermissionStatusLine("Notification Read-Aloud", notifOn)
                        PermissionStatusLine("Device Admin (Lock Screen Only)", adminOn)
                        PermissionStatusLine("Overlay Edge Glow & Stop Pill", overlayOn)

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onOpenPermissionsReview,
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f))
                            ) {
                                Text("Guided Permissions", color = ArcCyan)
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, ArcGlassBorder)
                            ) {
                                Text("OS App Info", color = ArcTextSecondary)
                            }
                        }
                    }
                }

                // 7) ABOUT & PRIVACY TRANSPARENCY
                item {
                    SettingsSectionCard(icon = Icons.Default.PrivacyTip, title = "About ARC & Privacy") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "• Cloud vs On-Device: Voice conversations and multimodal vision use Firebase AI Logic (Gemini Live / Gemini 3.8 Flash), with optional Anthropic Claude reasoning and ElevenLabs TTS fallback. Screen OCR, language detection, translation, reminders, and long-term memory are stored/processed locally on your device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextSecondary
                            )
                            Text(
                                text = "• Manual Mic Control (Zero Background Wake-Word): ARC sirf tab sunta hai jab aap mic ON karte ho — 'Hey ARC' bolne ki zarurat nahi, bas mic-button dabao aur seedha baat karo. Mic ON hone par \"ARC mic is active\" notification dikhta hai; Mic OFF karte hi audio processing aur background mic service turant band ho jaati hai.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextSecondary
                            )
                            Text(
                                text = "• Transparent Anti-Theft Guard: When armed, motion or charger unplugging triggers a loud alarm siren at full volume and opens a visible front-camera alert prompt.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextSecondary
                            )
                            Text(
                                text = "• Platform Honesty: WhatsApp auto-calling, third-party app force-closing, automated ride booking, and automated shopping checkout are blocked by Android/Meta/vendor rules — ARC opens the screen and leaves final confirmation to you.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddReminderDialog) {
        AddReminderDialog(
            onDismiss = { showAddReminderDialog = false },
            onSave = { title, mins, recurrence ->
                viewModel.addReminder(
                    title = title,
                    notes = "",
                    triggerAtMillis = System.currentTimeMillis() + mins * 60_000L,
                    recurrence = recurrence
                )
                showAddReminderDialog = false
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ArcSurface.copy(alpha = 0.88f)),
        border = BorderStroke(1.dp, ArcGlassBorder)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = ArcCyan)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, color = ArcTextPrimary)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun ArcField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ArcCyan,
            unfocusedBorderColor = ArcGlassBorder,
            focusedLabelColor = ArcCyan,
            unfocusedLabelColor = ArcTextSecondary,
            focusedTextColor = ArcTextPrimary,
            unfocusedTextColor = ArcTextPrimary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PermissionStatusLine(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ArcTextPrimary)
        Text(
            text = if (enabled) "ACTIVE" else "INACTIVE",
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) ArcSuccessGreen else ArcAlertCrimson
        )
    }
}

@Composable
private fun AddReminderDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, minutesFromNow: Long, recurrence: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("15") }
    var recurrence by remember { mutableStateOf("NONE") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArcSurface,
        title = { Text("Schedule Reminder / Alarm", color = ArcCyan) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ArcField("Title", title) { title = it }
                ArcField("Trigger in (minutes)", minutes) { minutes = it.filter { c -> c.isDigit() } }
                Text("Recurrence", style = MaterialTheme.typography.labelMedium, color = ArcTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NONE", "DAILY", "WEEKDAYS", "WEEKLY").forEach { mode ->
                        FilterChip(
                            selected = recurrence == mode,
                            onClick = { recurrence = mode },
                            label = { Text(mode, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan,
                                selectedLabelColor = ArcVoid
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val m = minutes.toLongOrNull()?.coerceAtLeast(1L) ?: 15L
                    onSave(title.ifBlank { "ARC Alarm" }, m, recurrence)
                },
                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid)
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = ArcTextSecondary) }
        }
    )
}
