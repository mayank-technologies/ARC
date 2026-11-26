package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.heightIn
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.VoiceEngineState
import com.example.ui.ArcViewModel
import com.example.ui.components.ArcBackgroundGrid
import com.example.ui.components.ArcCoreSphere
import com.example.ui.components.ArcWaveform
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
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ArcViewModel,
    onOpenSettings: () -> Unit,
    onOpenGallery: (String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val speechEnergy by viewModel.speechEnergy.collectAsStateWithLifecycle()
    val voicePathStatus by viewModel.voicePathStatus.collectAsStateWithLifecycle()
    val ttsDiagnostic by viewModel.ttsDiagnostic.collectAsStateWithLifecycle()
    val waveformBars by viewModel.waveformBars.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val liveTime by viewModel.liveTime.collectAsStateWithLifecycle()
    val antiTheftAlert by viewModel.antiTheftAlert.collectAsStateWithLifecycle()
    val captureFrontPhoto by viewModel.requestFrontPhotoCapture.collectAsStateWithLifecycle()
    val isScreenSharing by viewModel.isScreenSharing.collectAsStateWithLifecycle()
    val statusToast by viewModel.statusToast.collectAsStateWithLifecycle()
    val galleryNavDate by viewModel.navigateToGalleryDate.collectAsStateWithLifecycle()

    var showVisionSheet by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var showCameraCaptureDialog by remember { mutableStateOf(false) }
    var useFrontCameraForAlert by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }

    // MediaProjection launcher for screen share
    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startScreenShareSession(result.resultCode, result.data!!)
        }
    }

    // Zero-permission Photo Picker for visual Q&A
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.analyzePhotoUri(uri, "Describe what you see and highlight anything useful.")
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setMicActive(true)
    }

    LaunchedEffect(Unit) {
        viewModel.onHomeEntered()
    }

    LaunchedEffect(statusToast) {
        statusToast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusToast()
        }
    }

    LaunchedEffect(galleryNavDate) {
        galleryNavDate?.let { date ->
            viewModel.consumeGalleryNavigation()
            onOpenGallery(date)
        }
    }

    // Visible front-camera photo capture when Anti-Theft Siren triggers (transparent, never silent/hidden)
    LaunchedEffect(captureFrontPhoto) {
        if (captureFrontPhoto) {
            viewModel.consumeFrontPhotoCaptureFlag()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                useFrontCameraForAlert = true
                showCameraCaptureDialog = true
            }
        }
    }

    val showWaveform = voiceState == VoiceEngineState.LISTENING ||
        voiceState == VoiceEngineState.SPEAKING ||
        voiceState == VoiceEngineState.CONNECTING ||
        voiceState == VoiceEngineState.PROCESSING

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ArcSurface,
                drawerContentColor = ArcTextPrimary,
                modifier = Modifier.width(320.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "ARC TRANSCRIPT",
                                style = MaterialTheme.typography.titleLarge,
                                color = ArcCyan
                            )
                            Text(
                                text = "${settings.activeVoiceDisplayLabel()} • ${voicePathStatus.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ArcTextSecondary
                            )
                            ttsDiagnostic?.let { diag ->
                                Text(
                                    text = diag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ArcStandbyAmber
                                )
                            }
                        }
                        if (messages.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearChatHistory() }) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "Clear history",
                                    tint = ArcTextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick utility links inside side drawer (keeps Home screen clean & uncluttered)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                onOpenGallery(null)
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, ArcGlassBorder),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp), tint = ArcCyan)
                            Spacer(Modifier.width(6.dp))
                            Text("Gallery", style = MaterialTheme.typography.labelMedium, color = ArcTextPrimary)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.setAntiTheftArmed(!settings.antiTheftArmed)
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(
                                1.dp,
                                if (settings.antiTheftArmed) ArcAlertCrimson else ArcGlassBorder
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (settings.antiTheftArmed) ArcAlertCrimson else ArcCyan
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (settings.antiTheftArmed) "Armed" else "Guard",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (settings.antiTheftArmed) ArcAlertCrimson else ArcTextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = ArcGlassBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Your voice and chat history with ARC will appear here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                val isUser = msg.role == "USER"
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isUser) ArcIonBlue.copy(alpha = 0.22f) else ArcSurfaceElevated,
                                    border = BorderStroke(
                                        1.dp,
                                        if (msg.role == "SYSTEM") ArcAlertCrimson.copy(alpha = 0.5f) else ArcGlassBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = if (isUser) settings.userName.ifBlank { "You" } else msg.role,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isUser) ArcCyan else ArcTeal
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = msg.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = ArcTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ArcSurfaceElevated, contentColor = ArcCyan)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Settings, Memory & Privacy")
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = ArcVoid,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                ArcBackgroundGrid()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .widthIn(max = 600.dp)
                        .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // TOP BAR: Menu (☰) + Glowing "ARC" Title + Settings Icon
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("menu_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Transcript Drawer",
                                tint = ArcCyan
                            )
                        }

                        Text(
                            text = "ARC",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = ArcCyan,
                            modifier = Modifier.shadow(elevation = 14.dp, spotColor = ArcCyan)
                        )

                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Open Settings",
                                tint = ArcTextSecondary
                            )
                        }
                    }

                    // Active Anti-Theft Alert Banner
                    AnimatedVisibility(visible = antiTheftAlert != null || settings.antiTheftArmed) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (antiTheftAlert != null) ArcAlertCrimson.copy(alpha = 0.2f) else ArcSurfaceElevated,
                            border = BorderStroke(1.dp, if (antiTheftAlert != null) ArcAlertCrimson else ArcCyan.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (antiTheftAlert != null) ArcAlertCrimson else ArcCyan
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = antiTheftAlert ?: "Anti-Theft Guard Armed",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ArcTextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        if (antiTheftAlert != null) viewModel.stopAntiTheftSiren()
                                        else viewModel.setAntiTheftArmed(false)
                                    }
                                ) {
                                    Text(
                                        text = if (antiTheftAlert != null) "Stop Siren" else "Disarm",
                                        color = ArcAlertCrimson,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(0.6f))

                    // CENTRAL CORE PARTICLE SPHERE (~58-60% screen-width diameter; tap toggles Mic ON/MUTED)
                    ArcCoreSphere(
                        voiceState = voiceState,
                        isOnline = settings.isOnline,
                        audioEnergy = speechEnergy,
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .widthIn(max = 320.dp)
                            .aspectRatio(1f)
                            .clickable {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) viewModel.toggleMicSession()
                                else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            .testTag("arc_core_sphere")
                    )

                    // Subtle live amplitude waveform when voice session is active
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ArcWaveform(
                            bars = waveformBars,
                            visible = showWaveform,
                            isSpeaking = voiceState == VoiceEngineState.SPEAKING
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Restored sleek Power ON/OFF (ONLINE / STANDBY) toggle pill directly below the CORE sphere
                    Surface(
                        onClick = { viewModel.updateOnlineState(!settings.isOnline, speakRoutine = true) },
                        shape = RoundedCornerShape(50),
                        color = ArcSurfaceElevated.copy(alpha = 0.88f),
                        border = BorderStroke(
                            1.dp,
                            if (settings.isOnline) ArcCyan.copy(alpha = 0.48f) else ArcStandbyAmber.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("power_routine_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (settings.isOnline) ArcTeal else ArcStandbyAmber,
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = if (settings.isOnline) "ONLINE" else "STANDBY",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (settings.isOnline) ArcCyan else ArcStandbyAmber
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(14.dp)
                                    .background(ArcGlassBorder)
                            )
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = if (settings.isOnline) "ARC is ON — tap to turn OFF for Standby" else "ARC is OFF — tap to turn ON",
                                tint = if (settings.isOnline) ArcTeal else ArcStandbyAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (settings.isOnline) "ON" else "OFF",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (settings.isOnline) ArcTeal else ArcStandbyAmber
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(0.50f))

                    // THREE INFO PILLS: Weather, Tasks Count, Live Device Time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val weatherLabel = weather?.let {
                            "${it.temperatureC.toInt()}°C ${it.city.substringBefore(" ")}"
                        } ?: "Weather --"
                        InfoPill(
                            icon = Icons.Default.WbSunny,
                            label = weatherLabel,
                            onClick = { viewModel.refreshWeather() },
                            modifier = Modifier.weight(1f)
                        )
                        val activeTasks = reminders.count { it.isEnabled }
                        InfoPill(
                            icon = Icons.Default.TaskAlt,
                            label = "$activeTasks task${if (activeTasks == 1) "" else "s"}",
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(0.85f)
                        )
                        InfoPill(
                            icon = Icons.Default.Schedule,
                            label = liveTime,
                            onClick = {},
                            modifier = Modifier.weight(0.95f)
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // THREE BOTTOM ICON BUTTONS: Camera/Screen Vision, Central Mic + Mute, Chat/Text Input
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1) Camera / Screen Vision button
                        Surface(
                            onClick = { showVisionSheet = true },
                            shape = CircleShape,
                            color = ArcSurfaceElevated,
                            border = BorderStroke(1.dp, if (isScreenSharing) ArcAlertCrimson else ArcGlassBorder),
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("vision_menu_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.CameraAlt,
                                    contentDescription = "Camera and Screen Vision",
                                    tint = if (isScreenSharing) ArcAlertCrimson else ArcCyan
                                )
                            }
                        }

                        // 2) Big Central Manual Mic ON / MUTED Toggle Button
                        // Clearly shows glowing mic-on vs crossed mic-off state, persisted in DataStore
                        val micIsOn = !settings.isMicMuted
                        val micActive = micIsOn && (
                            voiceState == VoiceEngineState.LISTENING ||
                                voiceState == VoiceEngineState.SPEAKING ||
                                voiceState == VoiceEngineState.CONNECTING
                            )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                onClick = {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) viewModel.toggleMicSession()
                                    else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                shape = CircleShape,
                                color = Color.Transparent,
                                border = if (micIsOn) {
                                    BorderStroke(2.dp, ArcCyan.copy(alpha = 0.85f))
                                } else {
                                    BorderStroke(1.5.dp, ArcAlertCrimson.copy(alpha = 0.8f))
                                },
                                modifier = Modifier
                                    .size(76.dp)
                                    .shadow(
                                        elevation = if (micIsOn) 20.dp else 4.dp,
                                        shape = CircleShape,
                                        spotColor = if (micIsOn) ArcCyan else ArcAlertCrimson
                                    )
                                    .testTag("main_mic_button")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            brush = Brush.linearGradient(
                                                colors = when {
                                                    !micIsOn -> listOf(ArcSurfaceElevated, ArcSurface)
                                                    micActive -> listOf(ArcTeal, ArcCyan)
                                                    else -> listOf(ArcCyan, ArcIonBlue)
                                                }
                                            ),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (micIsOn) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = if (micIsOn) "Mic ON — tap to Mute and stop listening" else "Mic OFF — tap to turn Mic ON and talk",
                                        tint = if (micIsOn) ArcVoid else ArcAlertCrimson,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (micIsOn) ArcCyan.copy(alpha = 0.14f) else ArcAlertCrimson.copy(alpha = 0.14f),
                                border = BorderStroke(
                                    1.dp,
                                    if (micIsOn) ArcCyan.copy(alpha = 0.5f) else ArcAlertCrimson.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = if (micIsOn) "MIC ON" else "MIC MUTED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (micIsOn) ArcCyan else ArcAlertCrimson,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // 3) Chat / Text Input button
                        Surface(
                            onClick = { showChatSheet = true },
                            shape = CircleShape,
                            color = ArcSurfaceElevated,
                            border = BorderStroke(1.dp, ArcGlassBorder),
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("chat_input_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = "Open Chat Input",
                                    tint = ArcCyan
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Camera / Screen Vision Sheet
    if (showVisionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVisionSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ArcSurface,
            contentColor = ArcTextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Vision & Device Tools", style = MaterialTheme.typography.titleLarge, color = ArcCyan)

                VisionActionRow(Icons.Default.CameraAlt, "Camera Vision", "Take a photo and ask ARC to analyze it") {
                    showVisionSheet = false
                    useFrontCameraForAlert = false
                    showCameraCaptureDialog = true
                }
                VisionActionRow(Icons.Default.PhotoLibrary, "Analyze Photo from Picker", "Zero-permission photo inspection") {
                    showVisionSheet = false
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                VisionActionRow(
                    icon = if (isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                    title = if (isScreenSharing) "Stop Screen Share" else "Share Screen",
                    subtitle = if (isScreenSharing) "End active MediaProjection session" else "Starts sharing with floating Stop Sharing pill"
                ) {
                    showVisionSheet = false
                    if (isScreenSharing) {
                        viewModel.stopScreenShareSession()
                    } else {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenShareLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                }
                VisionActionRow(Icons.Default.Visibility, "Read Current Screen", "Uses Accessibility + ML Kit OCR fallback") {
                    showVisionSheet = false
                    viewModel.sendTextCommand("Read what is on my screen right now", speakReply = true)
                }
                VisionActionRow(Icons.Default.ContentPaste, "Check Clipboard", "Reads clipboard on command and suggests actions") {
                    showVisionSheet = false
                    val outcome = viewModel.deviceExecutor.readClipboardOnCommand()
                    val msg = when (outcome) {
                        is com.example.device.ActionOutcome.Success -> outcome.message
                        is com.example.device.ActionOutcome.NeedsUserAction -> outcome.message
                        is com.example.device.ActionOutcome.Failure -> outcome.reason
                    }
                    viewModel.sendTextCommand("Clipboard check result: $msg. Respond concisely.", speakReply = true)
                }
            }
        }
    }

    // Chat / Text Input Sheet
    if (showChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChatSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ArcSurface,
            contentColor = ArcTextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Message ARC", style = MaterialTheme.typography.titleLarge, color = ArcCyan)

                val recent = messages.takeLast(6)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recent, key = { it.id }) { msg ->
                        val isUser = msg.role == "USER"
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isUser) ArcIonBlue.copy(alpha = 0.2f) else ArcSurfaceElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${if (isUser) "You" else "ARC"}: ${msg.text}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextPrimary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        placeholder = { Text("Ask ARC or give a command (any language)...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (chatInput.isNotBlank()) {
                                    viewModel.sendTextCommand(chatInput, speakReply = true)
                                    chatInput = ""
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ArcCyan,
                            unfocusedBorderColor = ArcGlassBorder,
                            focusedTextColor = ArcTextPrimary,
                            unfocusedTextColor = ArcTextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_text_field")
                    )
                    IconButton(
                        onClick = {
                            if (chatInput.isNotBlank()) {
                                viewModel.sendTextCommand(chatInput, speakReply = true)
                                chatInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_chat_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = ArcCyan)
                    }
                }

                // Voice note share to WhatsApp button
                WhatsAppVoiceNoteRow(context = context)
            }
        }
    }

    // Visible CameraX Dialog (for Camera Vision or Transparent Anti-Theft Alert photo capture)
    if (showCameraCaptureDialog) {
        CameraCaptureDialog(
            useFrontCamera = useFrontCameraForAlert,
            title = if (useFrontCameraForAlert) "Anti-Theft Alert Capture" else "ARC Camera Vision",
            onDismiss = { showCameraCaptureDialog = false },
            onPhotoCaptured = { file ->
                showCameraCaptureDialog = false
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                viewModel.saveCapturedPhotoUri(
                    uri,
                    if (useFrontCameraForAlert) "Anti-Theft Alert" else "ARC Vision"
                )
                if (!useFrontCameraForAlert) {
                    viewModel.analyzePhotoUri(uri, "Describe what the camera captured and answer clearly.")
                }
            }
        )
    }
}

@Composable
private fun InfoPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = ArcSurface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, ArcGlassBorder),
        modifier = modifier.height(48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ArcCyan,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = ArcTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VisionActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ArcSurfaceElevated),
        border = BorderStroke(1.dp, ArcGlassBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = ArcCyan)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = ArcTextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ArcTextSecondary)
            }
        }
    }
}

@Composable
private fun WhatsAppVoiceNoteRow(context: Context) {
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (recording) "Recording voice note..." else "WhatsApp Voice Note",
            style = MaterialTheme.typography.bodyMedium,
            color = if (recording) ArcAlertCrimson else ArcTextSecondary
        )
        OutlinedButton(
            onClick = {
                if (!recording) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(context, "Grant Microphone permission first", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    val file = File(context.cacheDir, "arc_voicenote_${System.currentTimeMillis()}.m4a")
                    outputFile = file
                    val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else @Suppress("DEPRECATION") MediaRecorder()
                    runCatching {
                        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        rec.setOutputFile(file.absolutePath)
                        rec.prepare()
                        rec.start()
                        recorder = rec
                        recording = true
                    }.onFailure {
                        Toast.makeText(context, "Recorder error: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runCatching {
                        recorder?.stop()
                        recorder?.release()
                    }
                    recorder = null
                    recording = false
                    outputFile?.let { f ->
                        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/mp4"
                            putExtra(Intent.EXTRA_STREAM, contentUri)
                            setPackage("com.whatsapp")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Share Voice Note"))
                        }
                    }
                }
            },
            border = BorderStroke(1.dp, if (recording) ArcAlertCrimson else ArcCyan.copy(alpha = 0.5f))
        ) {
            Text(
                text = if (recording) "Stop & Share" else "Record Note",
                color = if (recording) ArcAlertCrimson else ArcCyan
            )
        }
    }
}

@Composable
private fun CameraCaptureDialog(
    useFrontCamera: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onPhotoCaptured: (File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(useFrontCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }
        }, executor)
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArcSurface,
        title = { Text(title, color = ArcCyan) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = if (useFrontCamera) {
                        "Transparent alert capture: visible camera preview active."
                    } else {
                        "Point the camera and tap Capture for Gemini Vision analysis."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArcTextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val photoFile = File(context.filesDir, "arc_photo_${System.currentTimeMillis()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                onPhotoCaptured(photoFile)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid)
            ) {
                Text("Capture")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null, tint = ArcTextSecondary)
                Spacer(Modifier.width(4.dp))
                Text("Cancel", color = ArcTextSecondary)
            }
        }
    )
}
