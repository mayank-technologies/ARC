package com.example.ui.screens

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.service.ArcAccessibilityService
import com.example.service.ArcDeviceAdminReceiver
import com.example.service.ArcNotificationListenerService
import com.example.ui.components.ArcBackgroundGrid
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcGlassBorder
import com.example.ui.theme.ArcIonBlue
import com.example.ui.theme.ArcSuccessGreen
import com.example.ui.theme.ArcSurface
import com.example.ui.theme.ArcSurfaceElevated
import com.example.ui.theme.ArcTextPrimary
import com.example.ui.theme.ArcTextSecondary
import com.example.ui.theme.ArcVoid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NameOnboardingScreen(
    onContinue: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val infinite = rememberInfiniteTransition(label = "onboarding_ring")
    val orbit by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ArcBackgroundGrid()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(max = 520.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Glowing ring with geometric "A" logo & orbital shimmer particles
            Box(
                modifier = Modifier.size(164.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.minDimension * 0.44f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(ArcCyan.copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = size.minDimension * 0.52f
                        ),
                        radius = size.minDimension * 0.52f,
                        center = Offset(cx, cy)
                    )
                    for (i in 0 until 12) {
                        val a = orbit + i * (2f * PI.toFloat() / 12f)
                        val px = cx + cos(a) * r
                        val py = cy + sin(a) * r
                        drawCircle(
                            color = if (i % 2 == 0) ArcCyan else ArcIonBlue,
                            radius = if (i % 3 == 0) 3.dp.toPx() else 1.8.dp.toPx(),
                            center = Offset(px, py)
                        )
                    }
                }
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_art_1795527374577),
                    contentDescription = "ARC Geometric Emblem",
                    modifier = Modifier
                        .size(116.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "ARC",
                style = MaterialTheme.typography.displayLarge,
                color = ArcCyan
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Your Personal Voice & Device Companion",
                style = MaterialTheme.typography.bodyLarge,
                color = ArcTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Glassmorphism onboarding card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ArcSurface.copy(alpha = 0.82f)),
                border = BorderStroke(1.dp, ArcGlassBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "What should ARC call you?",
                        style = MaterialTheme.typography.titleLarge,
                        color = ArcTextPrimary
                    )
                    Text(
                        text = "Used for personalized morning briefings, reminders, and natural voice responses.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArcTextSecondary
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("Enter your name") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (name.isNotBlank()) onContinue(name) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ArcCyan,
                            unfocusedBorderColor = ArcGlassBorder,
                            focusedTextColor = ArcTextPrimary,
                            unfocusedTextColor = ArcTextPrimary,
                            cursorColor = ArcCyan
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_name_input")
                    )

                    Button(
                        onClick = { onContinue(name.trim().ifBlank { "Commander" }) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("get_started_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = ArcVoid
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.horizontalGradient(listOf(ArcCyan, ArcIonBlue)),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Get Started",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ArcVoid
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = ArcVoid
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PermissionEntry(
    val id: String,
    val title: String,
    val rationale: String,
    val icon: ImageVector,
    val isGranted: Boolean,
    val isSpecialSystemAccess: Boolean = false
)

@Composable
fun PermissionsOnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
    }.toTypedArray()

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTrigger++ }

    val singlePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    val adminComponent = remember { ComponentName(context, ArcDeviceAdminReceiver::class.java) }

    fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val entries = remember(refreshTrigger) {
        listOf(
            PermissionEntry(
                id = "mic",
                title = "Microphone",
                rationale = "Used ONLY when you manually tap the Mic ON button — never listens in background when muted.",
                icon = Icons.Default.Mic,
                isGranted = hasPerm(Manifest.permission.RECORD_AUDIO)
            ),
            PermissionEntry(
                id = "notif",
                title = "Notifications",
                rationale = "Shows the transparent background listening indicator and delivers your reminders.",
                icon = Icons.Default.NotificationsActive,
                isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasPerm(Manifest.permission.POST_NOTIFICATIONS)
                } else true
            ),
            PermissionEntry(
                id = "location",
                title = "Location",
                rationale = "Fetches local weather for your city and sets GPS pickup for ride assistance.",
                icon = Icons.Default.LocationOn,
                isGranted = hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION) || hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
            ),
            PermissionEntry(
                id = "contacts",
                title = "Contacts & Phone",
                rationale = "Matches spoken contact names and dials confirmed calls.",
                icon = Icons.Default.Contacts,
                isGranted = hasPerm(Manifest.permission.READ_CONTACTS) && hasPerm(Manifest.permission.CALL_PHONE)
            ),
            PermissionEntry(
                id = "sms",
                title = "SMS Read & Send",
                rationale = "SMS padhne/bhejne ke liye — voice se recent messages sunne aur contacts ko SMS bhejne ke liye.",
                icon = Icons.Default.Sms,
                isGranted = hasPerm(Manifest.permission.READ_SMS) && hasPerm(Manifest.permission.SEND_SMS)
            ),
            PermissionEntry(
                id = "camera",
                title = "Camera & Flashlight",
                rationale = "Powers visual Q&A, torch control, and visible anti-theft alert capture.",
                icon = Icons.Default.CameraAlt,
                isGranted = hasPerm(Manifest.permission.CAMERA)
            ),
            PermissionEntry(
                id = "calendar",
                title = "Calendar",
                rationale = "Reads your next meeting briefing and syncs reminders to your calendar.",
                icon = Icons.Default.CalendarMonth,
                isGranted = hasPerm(Manifest.permission.READ_CALENDAR)
            ),
            PermissionEntry(
                id = "overlay",
                title = "Display Over Other Apps",
                rationale = "Displays edge listening glow and the floating \"Stop Sharing\" pill.",
                icon = Icons.Default.Layers,
                isGranted = Settings.canDrawOverlays(context),
                isSpecialSystemAccess = true
            ),
            PermissionEntry(
                id = "accessibility",
                title = "Accessibility Service",
                rationale = "Reads screen text on command, skips YouTube ads, controls reels, and auto-fills forms.",
                icon = Icons.Default.AccessibilityNew,
                isGranted = ArcAccessibilityService.isEnabled(context),
                isSpecialSystemAccess = true
            ),
            PermissionEntry(
                id = "notif_listener",
                title = "Notification Access",
                rationale = "Lets ARC read WhatsApp and selected app notifications aloud on command.",
                icon = Icons.Default.NotificationsPaused,
                isGranted = ArcNotificationListenerService.isPermissionGranted(context),
                isSpecialSystemAccess = true
            ),
            PermissionEntry(
                id = "device_admin",
                title = "Device Admin (Force-Lock Only)",
                rationale = "Enables locking the screen immediately via voice command.",
                icon = Icons.Default.AdminPanelSettings,
                isGranted = dpm.isAdminActive(adminComponent),
                isSpecialSystemAccess = true
            )
        )
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ArcBackgroundGrid()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
        ) {
            Text(
                text = "Permissions & System Access",
                style = MaterialTheme.typography.headlineMedium,
                color = ArcCyan
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ARC sirf tab sunta hai jab aap mic ON karte ho — 'Hey ARC' bolne ki zarurat nahi, bas mic-button dabao aur seedha baat karo.",
                style = MaterialTheme.typography.bodyMedium,
                color = ArcTextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { multiPermLauncher.launch(runtimePerms) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("grant_all_runtime_permissions"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ArcSurfaceElevated, contentColor = ArcCyan)
            ) {
                Text("Grant Standard Permissions", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { item ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = ArcSurface.copy(alpha = 0.85f)),
                        border = BorderStroke(
                            1.dp,
                            if (item.isGranted) ArcSuccessGreen.copy(alpha = 0.45f) else ArcGlassBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (item.isGranted) ArcSuccessGreen.copy(alpha = 0.16f) else ArcCyan.copy(alpha = 0.12f),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        tint = if (item.isGranted) ArcSuccessGreen else ArcCyan,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ArcTextPrimary
                                )
                                Text(
                                    text = item.rationale,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ArcTextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (item.isGranted) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Granted",
                                    tint = ArcSuccessGreen
                                )
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        when (item.id) {
                                            "mic" -> singlePermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            "notif" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                singlePermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                            "location" -> multiPermLauncher.launch(
                                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                            )
                                            "contacts" -> multiPermLauncher.launch(
                                                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
                                            )
                                            "sms" -> multiPermLauncher.launch(
                                                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
                                            )
                                            "camera" -> singlePermLauncher.launch(Manifest.permission.CAMERA)
                                            "calendar" -> multiPermLauncher.launch(
                                                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                                            )
                                            "overlay" -> context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                            "accessibility" -> context.startActivity(
                                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                            "notif_listener" -> context.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                            "device_admin" -> {
                                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                                    putExtra(
                                                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                                        "Allows ARC to lock your screen by voice command (force-lock only)."
                                                    )
                                                }
                                                context.startActivity(intent)
                                            }
                                            else -> openAppSettings()
                                        }
                                    },
                                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f))
                                ) {
                                    Text(if (item.isSpecialSystemAccess) "Settings" else "Allow", color = ArcCyan)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { openAppSettings() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ArcGlassBorder)
                ) {
                    Text("App Settings", color = ArcTextSecondary)
                }
                Button(
                    onClick = onComplete,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(52.dp)
                        .testTag("enter_arc_core_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid)
                ) {
                    Text("Enter ARC Core", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
