package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.ai.ArcToolRegistry
import com.example.ai.ClaudeReasoningClient
import com.example.ai.FallbackTtsManager
import com.example.ai.GeminiLiveEngine
import com.example.ai.ModelTier
import com.example.ai.VoiceEngineState
import com.example.core.ArcFloatingOrbController
import com.example.core.AudioRoutingController
import com.example.core.ContextDetector
import com.example.core.JarvisSystemFeatures
import com.example.core.ProactiveAlertMonitor
import com.example.core.SystemDiagnosticsSnapshot
import com.example.data.local.ArcDatabase
import com.example.data.local.ArcLocalRepository
import com.example.data.local.ChatMessageEntity
import com.example.data.local.GalleryPhotoEntity
import com.example.data.local.MemoryFactEntity
import com.example.data.local.ProtocolEntity
import com.example.data.local.ReminderEntity
import com.example.data.prefs.ArcPreferences
import com.example.data.prefs.ArcSettingsState
import com.example.data.prefs.MaleVoicePreset
import com.example.data.prefs.PersonalInfo
import com.example.data.prefs.VoiceGender
import com.example.data.prefs.VoicePathStatus
import com.example.data.remote.ExternalApis
import com.example.data.remote.WeatherSnapshot
import com.example.device.DeviceActionExecutor
import com.example.service.ArcEdgeOverlayManager
import com.example.service.ArcForegroundService
import com.example.service.ReminderScheduler
import com.example.service.ServiceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val TAG = "ArcViewModel"

/**
 * Coordinates ARC's UI state, Gemini Live voice session, Foreground Service, Room DB, and DataStore.
 *
 * CHANGE (Manual Mic-Toggle Architecture replacing Porcupine Wake-Word):
 * - Removed all wake-word events and loops.
 * - Gemini Live session lifecycle is bound directly to the user's manual Mic ON/OFF toggle:
 *   * Mic ON  -> persists state, starts Foreground Service ("ARC mic is active"), starts Gemini Live.
 *   * Mic OFF -> persists state, closes Gemini Live immediately (zero audio processed), stops FG service if idle.
 * - "Good Night ARC" routine automatically turns Mic OFF/MUTED and closes any active Live session.
 */
class ArcViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val prefs = ArcPreferences(appContext)
    private val db = ArcDatabase.getInstance(appContext)
    val repository = ArcLocalRepository(db)
    val externalApis = ExternalApis(appContext)
    val deviceExecutor = DeviceActionExecutor(appContext, externalApis)
    private val audioRouting = AudioRoutingController(appContext)
    private val fallbackTts = FallbackTtsManager(appContext, audioRouting)
    private val claudeClient = ClaudeReasoningClient()

    private val _navigateToGalleryDate = MutableStateFlow<String?>(null)
    val navigateToGalleryDate = _navigateToGalleryDate.asStateFlow()

    private val _antiTheftAlert = MutableStateFlow<String?>(null)
    val antiTheftAlert = _antiTheftAlert.asStateFlow()

    private val _requestFrontPhotoCapture = MutableStateFlow(false)
    val requestFrontPhotoCapture = _requestFrontPhotoCapture.asStateFlow()

    private val floatingOrbOverlay = ArcEdgeOverlayManager(appContext)

    private val toolRegistry = ArcToolRegistry(
        context = appContext,
        prefs = prefs,
        repository = repository,
        externalApis = externalApis,
        deviceExecutor = deviceExecutor,
        claudeClient = claudeClient,
        onSideEffect = { effect ->
            effect.openGalleryDate?.let { _navigateToGalleryDate.value = it }
            effect.setOnlineState?.let { online ->
                viewModelScope.launch { updateOnlineState(online, speakRoutine = false) }
            }
            effect.setMicActiveState?.let { micOn ->
                setMicActive(micOn)
            }
            effect.setArmedState?.let { armed ->
                if (armed) ensureForegroundServiceBound()
                fgService?.setAntiTheftArmed(armed)
            }
            if (effect.showDiagnosticsHud) {
                viewModelScope.launch {
                    _diagnostics.value = JarvisSystemFeatures.collectDiagnostics(appContext)
                    _showDiagnosticsSheet.value = true
                }
            }
        }
    )

    private val liveEngine = GeminiLiveEngine(
        context = appContext,
        repository = repository,
        toolRegistry = toolRegistry,
        fallbackTts = fallbackTts,
        claudeClient = claudeClient,
        audioRouting = audioRouting
    )

    val settings: StateFlow<ArcSettingsState> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ArcSettingsState())

    val loadedSettings: StateFlow<ArcSettingsState?> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val reminders: StateFlow<List<ReminderEntity>> = repository.reminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryFactEntity>> = repository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messages: StateFlow<List<ChatMessageEntity>> = repository.messages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val galleryPhotos: StateFlow<List<GalleryPhotoEntity>> = repository.galleryPhotos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val protocols: StateFlow<List<ProtocolEntity>> = repository.protocols
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val voiceState: StateFlow<VoiceEngineState> = combine(
        liveEngine.engineState,
        fallbackTts.isSpeaking
    ) { liveState, ttsSpeaking ->
        if (ttsSpeaking) VoiceEngineState.SPEAKING else liveState
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VoiceEngineState.IDLE)

    val speechEnergy: StateFlow<Float> = combine(
        liveEngine.speechEnergy,
        fallbackTts.waveformLevel
    ) { liveEnergy, ttsEnergy ->
        maxOf(liveEnergy, ttsEnergy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    val activeTier: StateFlow<ModelTier> = liveEngine.activeTier
    val waveformBars: StateFlow<List<Float>> = liveEngine.waveformBars
    val engineStatus: StateFlow<String> = liveEngine.statusMessage
    val voicePathStatus: StateFlow<VoicePathStatus> = combine(
        liveEngine.voicePathStatus,
        fallbackTts.voicePathStatus
    ) { livePath, ttsPath ->
        if (livePath == VoicePathStatus.LIVE_HD) livePath else ttsPath
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VoicePathStatus.IDLE)
    val ttsDiagnostic: StateFlow<String?> = fallbackTts.ttsDiagnostic

    private val _weather = MutableStateFlow<WeatherSnapshot?>(null)
    val weather = _weather.asStateFlow()

    private val _liveTime = MutableStateFlow(formatNow())
    val liveTime = _liveTime.asStateFlow()

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing = _isScreenSharing.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast = _statusToast.asStateFlow()

    private val _isChatThinking = MutableStateFlow(false)
    val isChatThinking = _isChatThinking.asStateFlow()

    private val _chatError = MutableStateFlow<String?>(null)
    val chatError = _chatError.asStateFlow()

    private val _diagnostics = MutableStateFlow<SystemDiagnosticsSnapshot?>(null)
    val diagnostics = _diagnostics.asStateFlow()

    private val _showDiagnosticsSheet = MutableStateFlow(false)
    val showDiagnosticsSheet = _showDiagnosticsSheet.asStateFlow()

    private var fgService: ArcForegroundService? = null
    private var serviceBound = false
    private var liveSessionJob: Job? = null
    private var onlineRoutineJob: Job? = null
    private var textTurnJob: Job? = null
    private var initialRestoreHandled = false

    @Volatile
    private var onlineTarget: Boolean? = null

    private fun isCurrentlyOnline(): Boolean = onlineTarget ?: settings.value.isOnline

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ArcForegroundService.LocalBinder ?: return
            val svc = binder.getService()
            fgService = svc
            serviceBound = true

            viewModelScope.launch {
                val st = settings.value
                // Sync foreground service strictly to persisted manual mic / guard state
                svc.updateMicActive(!st.isMicMuted)
                svc.setAntiTheftArmed(st.antiTheftArmed)
            }

            viewModelScope.launch {
                svc.events.collect { ev ->
                    when (ev) {
                        is ServiceEvent.MicMuteRequested -> {
                            // User tapped "Mute Mic" directly on the persistent notification
                            setMicActive(false)
                        }
                        is ServiceEvent.AntiTheftTriggered -> {
                            _antiTheftAlert.value = ev.reason
                            _requestFrontPhotoCapture.value = true
                            viewModelScope.launch {
                                repository.addMessage("SYSTEM", "SECURITY ALERT: ${ev.reason}", "EMPATHETIC")
                            }
                        }
                        is ServiceEvent.ScreenShareStopped -> {
                            _isScreenSharing.value = false
                        }
                    }
                }
            }

            viewModelScope.launch {
                svc.isScreenSharing.collect { sharing ->
                    _isScreenSharing.value = sharing
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fgService = null
            serviceBound = false
        }
    }

    init {
        startClockTicker()
        refreshWeather()
        observeEdgeOverlaySync()
        observeFloatingOrbSync()
        observeProactiveAlerts()
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureDefaultProtocols()
        }
    }

    private fun observeFloatingOrbSync() {
        ArcFloatingOrbController.onToggleMic = { toggleMicSession() }
        ArcFloatingOrbController.onOpenArcHome = {
            val intent = Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { appContext.startActivity(intent) }
        }
        viewModelScope.launch {
            combine(settings, voiceState) { st, vs -> st to vs }.collect { (st, vs) ->
                if (st.floatingOrbEnabled && Settings.canDrawOverlays(appContext)) {
                    floatingOrbOverlay.showOrUpdateFloatingOrb(
                        micActive = !st.isMicMuted,
                        isSpeaking = vs == VoiceEngineState.SPEAKING,
                        onTapToggleMic = { ArcFloatingOrbController.onToggleMic?.invoke() },
                        onLongPressOpenApp = { ArcFloatingOrbController.onOpenArcHome?.invoke() }
                    )
                } else {
                    floatingOrbOverlay.hideFloatingOrb()
                }
            }
        }
    }

    private fun startClockTicker() {
        viewModelScope.launch {
            while (true) {
                _liveTime.value = formatNow()
                delay(1000L)
            }
        }
    }

    private fun formatNow(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"))

    private fun observeEdgeOverlaySync() {
        viewModelScope.launch {
            voiceState.collect { state ->
                val showGlow = state == VoiceEngineState.LISTENING || state == VoiceEngineState.SPEAKING
                fgService?.setEdgeGlow(showGlow, state == VoiceEngineState.SPEAKING)
            }
        }
    }

    /**
     * Subscribes to one-time battery threshold (20%, 15%, 10%) and charging-start events,
     * syncing the user's "Proactive Alerts: ON/OFF" preference cleanly.
     */
    private fun observeProactiveAlerts() {
        ProactiveAlertMonitor.acquire(appContext)
        viewModelScope.launch {
            prefs.settingsFlow.collect { st ->
                ProactiveAlertMonitor.setEnabled(st.proactiveAlertsEnabled)
            }
        }
        viewModelScope.launch {
            ProactiveAlertMonitor.alerts.collect { alert ->
                val st = settings.value
                if (!st.proactiveAlertsEnabled) return@collect
                if (fgService?.isSirenPlaying?.value == true) return@collect

                repository.addMessage("ARC", alert.message, "FACTUAL")
                _statusToast.value = alert.message
                if (isCurrentlyOnline()) {
                    fallbackTts.speak(alert.message, st.voiceGender, st.maleVoicePreset, viewModelScope)
                }
            }
        }
    }

    /**
     * Restores the persisted Mic ON state if the user left Mic ON before reopening the app.
     * If Mic is OFF/MUTED (default), does NOT start any foreground service or audio recording,
     * ensuring zero idle battery drain.
     */
    fun onHomeEntered() {
        if (initialRestoreHandled) return
        initialRestoreHandled = true

        val st = settings.value
        val hasMicPerm = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!st.isMicMuted && hasMicPerm) {
            setMicActive(true)
        } else if (st.antiTheftArmed) {
            ensureForegroundServiceBound()
        }
    }

    private fun ensureForegroundServiceBound() {
        try {
            val intent = Intent(appContext, ArcForegroundService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
            if (!serviceBound) {
                appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start failed: ${e.message}")
        }
    }

    fun refreshWeather() {
        viewModelScope.launch {
            externalApis.fetchWeather().onSuccess { _weather.value = it }
        }
    }

    fun completeNameOnboarding(name: String) {
        viewModelScope.launch {
            prefs.saveUserNameAndCompleteOnboarding(name)
        }
    }

    fun completePermissionsReview() {
        viewModelScope.launch {
            prefs.setPermissionsReviewed(true)
        }
    }

    /**
     * Manual Mic ON / OFF(MUTED) toggle controlling Gemini Live + Foreground Service.
     * - Mic ON  -> persists state, starts "ARC mic is active" FG service, opens continuous Gemini Live session.
     * - Mic OFF -> persists state, closes Gemini Live immediately, stops FG service if idle (0 battery drain).
     */
    fun toggleMicSession() {
        val currentlyMuted = settings.value.isMicMuted
        setMicActive(currentlyMuted)
    }

    fun setMicActive(active: Boolean) {
        viewModelScope.launch {
            if (active) {
                val hasMic = ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    _statusToast.value = "Microphone permission required to turn Mic ON"
                    return@launch
                }

                onlineTarget = true
                prefs.setMicMuted(false)
                prefs.setOnline(true)
                ensureForegroundServiceBound()
                fgService?.updateMicActive(true)

                // Start continuous Gemini Live voice session until user toggles Mic OFF
                liveSessionJob?.cancel()
                liveSessionJob = viewModelScope.launch {
                    val st = settings.value
                    liveEngine.startLiveSession(
                        userName = st.userName.ifBlank { "Friend" },
                        voiceGender = st.voiceGender,
                        maleVoicePreset = st.maleVoicePreset,
                        scope = viewModelScope,
                        onTranscriptUpdate = { role, text ->
                            viewModelScope.launch {
                                repository.addMessage(role, text)
                            }
                        }
                    )
                }
            } else {
                // Immediately shut down Gemini Live audio processing & foreground mic service
                prefs.setMicMuted(true)
                liveSessionJob?.cancel()
                liveSessionJob = null
                liveEngine.stopLiveSession()
                fgService?.updateMicActive(false)
                if (serviceBound && !settings.value.antiTheftArmed && !_isScreenSharing.value) {
                    runCatching { appContext.unbindService(serviceConnection) }
                    serviceBound = false
                    fgService = null
                }
            }
        }
    }

    fun clearChatError() {
        _chatError.value = null
    }

    fun sendTextCommand(rawText: String, speakReply: Boolean = true) {
        val text = rawText.trim()
        if (text.isBlank()) return

        textTurnJob?.cancel()
        _chatError.value = null

        textTurnJob = viewModelScope.launch {
            val contextTag = ContextDetector.detectContext(text).name
            repository.addMessage("USER", text, contextTag)

            val lower = text.lowercase()
            if (lower.contains("good morning arc")) {
                _isChatThinking.value = false
                updateOnlineState(true, speakRoutine = true)
                return@launch
            }
            if (lower.contains("good night arc")) {
                _isChatThinking.value = false
                updateOnlineState(false, speakRoutine = false)
                return@launch
            }
            if (lower.contains("security on") || lower.contains("arm security")) {
                _isChatThinking.value = false
                setAntiTheftArmed(true)
                val msg = "Security Guard armed. Moving or unplugging the phone will trigger the siren."
                repository.addMessage("ARC", msg, "FACTUAL")
                if (speakReply) {
                    val st = settings.value
                    fallbackTts.speak(msg, st.voiceGender, st.maleVoicePreset, this)
                }
                return@launch
            }
            if (lower.contains("security off") || lower.contains("disarm security")) {
                _isChatThinking.value = false
                setAntiTheftArmed(false)
                val msg = "Security Guard disarmed."
                repository.addMessage("ARC", msg, "FACTUAL")
                if (speakReply) {
                    val st = settings.value
                    fallbackTts.speak(msg, st.voiceGender, st.maleVoicePreset, this)
                }
                return@launch
            }

            val st = settings.value
            val heavyKeywords = listOf("download", "large file", "movie", "video", "youtube", "update app", "hd stream")
            if (st.proactiveAlertsEnabled && heavyKeywords.any { lower.contains(it) }) {
                ProactiveAlertMonitor.checkDataSaverForHeavyAction(appContext, "data-heavy task")?.let { warn ->
                    _statusToast.value = warn
                    repository.addMessage("ARC", warn, "FACTUAL")
                    if (speakReply) {
                        fallbackTts.speak(warn, st.voiceGender, st.maleVoicePreset, this)
                    }
                }
            }

            _isChatThinking.value = true
            try {
                var replyInserted = false
                val result = liveEngine.sendTextTurn(
                    rawMessage = text,
                    userName = st.userName.ifBlank { "Friend" },
                    voiceGender = st.voiceGender,
                    maleVoicePreset = st.maleVoicePreset,
                    speakResponse = speakReply,
                    scope = this,
                    onReplyReady = { reply ->
                        if (!replyInserted) {
                            replyInserted = true
                            _isChatThinking.value = false
                            repository.addMessage("ARC", reply, contextTag)
                        }
                    }
                )
                result.fold(
                    onSuccess = { reply ->
                        _isChatThinking.value = false
                        if (!replyInserted) {
                            repository.addMessage("ARC", reply, contextTag)
                        }
                    },
                    onFailure = { err ->
                        _isChatThinking.value = false
                        val errText = err.localizedMessage?.takeIf { it.isNotBlank() } ?: "Connection error"
                        _chatError.value = errText
                        val fallbackMsg = "Sorry, I couldn't respond right now ($errText)."
                        repository.addMessage("ARC", fallbackMsg, "EMPATHETIC")
                        _statusToast.value = errText
                        if (speakReply) {
                            fallbackTts.speak("Sorry, I couldn't connect right now.", st.voiceGender, st.maleVoicePreset, this)
                        }
                    }
                )
            } catch (e: CancellationException) {
                _isChatThinking.value = false
                throw e
            }
        }
    }

    /**
     * Power ON/OFF & Good Morning / Standby control.
     * - ON: Speaks greeting immediately, fetches weather/news/tasks concurrently with tight timeouts,
     *       and delivers the briefing right after the greeting.
     * - OFF: Instant silence kill-switch — cancels all active routines/chat/live tasks and halts
     *        TTS + microphone immediately with ZERO goodbye speech.
     */
    fun updateOnlineState(online: Boolean, speakRoutine: Boolean = true) {
        onlineTarget = online
        onlineRoutineJob?.cancel()
        onlineRoutineJob = null

        if (!online) {
            textTurnJob?.cancel()
            textTurnJob = null
            _isChatThinking.value = false
            liveSessionJob?.cancel()
            liveSessionJob = null
            fallbackTts.stop()

            onlineRoutineJob = viewModelScope.launch {
                prefs.setOnline(false)
                prefs.setMicMuted(true)
                liveEngine.stopLiveSession()
                fallbackTts.stop()
                fgService?.updateMicActive(false)
                if (serviceBound && !settings.value.antiTheftArmed && !_isScreenSharing.value) {
                    runCatching { appContext.unbindService(serviceConnection) }
                    serviceBound = false
                    fgService = null
                }
            }
            return
        }

        onlineRoutineJob = viewModelScope.launch {
            prefs.setOnline(true)
            val st = settings.value
            val name = st.userName.ifBlank { "Friend" }
            if (!speakRoutine) return@launch

            val now = LocalTime.now()
            val greeting = ContextDetector.timeOfDayOnlineGreeting(now)

            // Fetch weather, news (NEWS_API_KEY gated), and tasks concurrently while Hinglish greeting speaks immediately
            val weatherDeferred = async(Dispatchers.IO) {
                withTimeoutOrNull(3800L) {
                    externalApis.fetchWeather().getOrNull()
                }?.also { _weather.value = it } ?: _weather.value
            }
            val newsDeferred = async(Dispatchers.IO) {
                withTimeoutOrNull(4200L) {
                    val list = externalApis.fetchTopNews("india", 2).getOrNull().orEmpty()
                    if (list.isEmpty()) "" else externalApis.formatHeadlinesInHinglish(list)
                }.orEmpty()
            }
            val tasksDeferred = async(Dispatchers.IO) {
                repository.getEnabledReminders()
            }

            val displayGreeting = "$greeting $name, main ARC online hoon."
            val spokenGreeting = "$greeting $name, main आर्क online hoon."
            repository.addMessage("ARC", displayGreeting, "GREETING")
            if (!isActive || !isCurrentlyOnline()) return@launch
            fallbackTts.speak(spokenGreeting, st.voiceGender, st.maleVoicePreset, this)

            val newsHinglish = newsDeferred.await()
            val weatherSnap = weatherDeferred.await()
            val tasks = tasksDeferred.await()

            // Abort immediately if user switched ARC OFF while greeting/fetches ran
            if (!isActive || !isCurrentlyOnline()) return@launch

            val newsPart = if (newsHinglish.isNotBlank()) {
                "Aaj ki taaza khabar hai: $newsHinglish "
            } else {
                ""
            }

            val clockStr = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))
            val weatherPart = if (weatherSnap != null) {
                val cond = externalApis.conditionInHinglish(weatherSnap.condition)
                "Abhi samay $clockStr hai aur ${weatherSnap.city} ka mausam ${weatherSnap.temperatureC.toInt()} degree, $cond hai."
            } else {
                "Abhi samay $clockStr hai, lekin mausam ki jaankari abhi uplabdh nahi hai."
            }

            val taskPart = if (tasks.isNotEmpty()) {
                " Aaj aapke ${tasks.size} task scheduled hain: ${tasks.take(3).joinToString(", ") { it.title }}."
            } else {
                ""
            }

            val briefingText = "$newsPart$weatherPart$taskPart".trim()
            repository.addMessage("ARC", briefingText, "FACTUAL")
            if (!isActive || !isCurrentlyOnline()) return@launch
            fallbackTts.speak(briefingText, st.voiceGender, st.maleVoicePreset, this)
        }
    }

    fun setVoiceGender(gender: VoiceGender) {
        viewModelScope.launch {
            prefs.setVoiceGender(gender)
            if (!settings.value.isMicMuted) {
                delay(120L)
                setMicActive(true)
            }
        }
    }

    fun setMaleVoicePreset(preset: MaleVoicePreset) {
        viewModelScope.launch {
            prefs.setMaleVoicePreset(preset)
            if (!settings.value.isMicMuted && settings.value.voiceGender == VoiceGender.MALE) {
                delay(120L)
                setMicActive(true)
            }
        }
    }

    fun toggleNotificationApp(packageName: String, enabled: Boolean) {
        viewModelScope.launch { prefs.toggleNotificationApp(packageName, enabled) }
    }

    fun setProactiveAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setProactiveAlertsEnabled(enabled)
            ProactiveAlertMonitor.setEnabled(enabled)
            _statusToast.value = if (enabled) "Proactive Battery & Data Alerts ON" else "Proactive Alerts OFF"
        }
    }

    fun savePersonalInfo(info: PersonalInfo) {
        viewModelScope.launch {
            prefs.savePersonalInfo(info)
            _statusToast.value = "Personal info saved for forms auto-fill"
        }
    }

    fun addReminder(title: String, notes: String, triggerAtMillis: Long, recurrence: String, weekdaysCsv: String = "") {
        viewModelScope.launch {
            val entity = ReminderEntity(
                title = title.trim(),
                notes = notes.trim(),
                triggerAtMillis = triggerAtMillis,
                recurrence = recurrence,
                weekdaysCsv = weekdaysCsv
            )
            val id = repository.addReminder(entity)
            ReminderScheduler.schedule(appContext, entity.copy(id = id))
            externalApis.insertDeviceCalendarEvent(title, triggerAtMillis, notes)
            _statusToast.value = "Reminder scheduled"
        }
    }

    fun toggleReminder(reminder: ReminderEntity) {
        viewModelScope.launch {
            val updated = reminder.copy(isEnabled = !reminder.isEnabled)
            repository.updateReminder(updated)
            ReminderScheduler.schedule(appContext, updated)
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch {
            ReminderScheduler.cancel(appContext, id)
            repository.deleteReminder(id)
        }
    }

    fun addMemoryFact(fact: String, category: String = "Preference") {
        if (fact.isBlank()) return
        viewModelScope.launch {
            repository.addMemory(fact, category)
        }
    }

    fun deleteMemoryFact(id: Long) {
        viewModelScope.launch { repository.deleteMemory(id) }
    }

    fun wipeAllMemories() {
        viewModelScope.launch {
            repository.wipeAllMemories()
            _statusToast.value = "All ARC memories cleared"
        }
    }

    fun setAntiTheftArmed(armed: Boolean) {
        viewModelScope.launch {
            prefs.setAntiTheftArmed(armed)
            if (armed) ensureForegroundServiceBound()
            fgService?.setAntiTheftArmed(armed)
            if (!armed) _antiTheftAlert.value = null
        }
    }

    fun stopAntiTheftSiren() {
        fgService?.stopSiren()
        _antiTheftAlert.value = null
        viewModelScope.launch {
            prefs.setAntiTheftArmed(false)
            fgService?.setAntiTheftArmed(false)
        }
    }

    fun consumeFrontPhotoCaptureFlag() {
        _requestFrontPhotoCapture.value = false
    }

    fun saveCapturedPhotoUri(uri: Uri, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val today = LocalDate.now().toString()
            repository.addGalleryPhoto(uri.toString(), today, label)
        }
    }

    fun deleteGalleryPhoto(id: Long) {
        viewModelScope.launch { repository.deleteGalleryPhoto(id) }
    }

    fun analyzePhotoUri(uri: Uri, prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stream = appContext.contentResolver.openInputStream(uri) ?: return@launch
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                if (bmp != null) {
                    val scaled = scaleBitmapForVision(bmp)
                    val st = settings.value
                    val res = liveEngine.analyzeVisualFrame(
                        bitmap = scaled,
                        question = prompt,
                        userName = st.userName.ifBlank { "Friend" },
                        voiceGender = st.voiceGender,
                        maleVoicePreset = st.maleVoicePreset,
                        speakResult = true,
                        scope = viewModelScope
                    )
                    res.onSuccess { ans ->
                        repository.addMessage("USER", "[Photo Vision] $prompt")
                        repository.addMessage("ARC", ans, "FACTUAL")
                    }.onFailure { err ->
                        _statusToast.value = err.localizedMessage
                    }
                }
            } catch (e: Exception) {
                _statusToast.value = "Could not analyze photo: ${e.localizedMessage}"
            }
        }
    }

    private fun scaleBitmapForVision(bmp: Bitmap): Bitmap {
        val maxDim = 1024
        if (bmp.width <= maxDim && bmp.height <= maxDim) return bmp
        val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
        return Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
    }

    fun startScreenShareSession(resultCode: Int, data: Intent) {
        ensureForegroundServiceBound()
        fgService?.startScreenSharing(resultCode, data)
        _isScreenSharing.value = true
    }

    fun stopScreenShareSession() {
        fgService?.stopScreenSharing()
        _isScreenSharing.value = false
    }

    fun consumeGalleryNavigation() {
        _navigateToGalleryDate.value = null
    }

    fun clearStatusToast() {
        _statusToast.value = null
    }

    fun setFloatingOrbEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !Settings.canDrawOverlays(appContext)) {
                _statusToast.value = "Grant Display Over Other Apps permission for Floating Mini Orb"
                return@launch
            }
            prefs.setFloatingOrbEnabled(enabled)
            _statusToast.value = if (enabled) "Floating Mini JARVIS Orb enabled" else "Floating Mini Orb hidden"
        }
    }

    fun runSystemDiagnostics(speakReport: Boolean = true) {
        viewModelScope.launch {
            val snap = JarvisSystemFeatures.collectDiagnostics(appContext)
            _diagnostics.value = snap
            _showDiagnosticsSheet.value = true
            val st = settings.value
            val report = snap.toHinglishReport(st.userName)
            repository.addMessage("ARC", report, "FACTUAL")
            if (speakReport) {
                fallbackTts.speak(report, st.voiceGender, st.maleVoicePreset, this)
            }
        }
    }

    fun dismissDiagnosticsSheet() {
        _showDiagnosticsSheet.value = false
    }

    fun activateProtocol(protocol: ProtocolEntity, speakConfirmation: Boolean = true) {
        viewModelScope.launch {
            val res = JarvisSystemFeatures.executeProtocolActions(appContext, protocol, deviceExecutor)
            when (protocol.micMode) {
                "ON" -> setMicActive(true)
                "OFF" -> setMicActive(false)
            }
            if (protocol.goStandby) {
                updateOnlineState(false, speakRoutine = false)
            }
            repository.addMessage("ARC", res.spokenHinglishSummary, "FACTUAL")
            _statusToast.value = "${protocol.name} active"
            if (speakConfirmation && !protocol.goStandby) {
                val st = settings.value
                fallbackTts.speak(res.spokenHinglishSummary, st.voiceGender, st.maleVoicePreset, this)
            }
        }
    }

    fun saveCustomProtocol(
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
    ) {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return
        viewModelScope.launch {
            repository.addProtocol(
                ProtocolEntity(
                    name = cleaned,
                    description = description.trim().ifBlank { "Custom multi-action protocol" },
                    isBuiltIn = false,
                    brightnessPercent = brightnessPercent,
                    volumeMode = volumeMode,
                    torchMode = torchMode,
                    openAppName = openAppName.trim(),
                    openBluetoothSettings = openBluetoothSettings,
                    openWifiPanel = openWifiPanel,
                    micMode = micMode,
                    goStandby = goStandby
                )
            )
            _statusToast.value = "Protocol '$cleaned' saved"
        }
    }

    fun deleteCustomProtocol(protocol: ProtocolEntity) {
        if (protocol.isBuiltIn) return
        viewModelScope.launch {
            repository.deleteProtocol(protocol.id)
            _statusToast.value = "Deleted ${protocol.name}"
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch { repository.clearMessages() }
    }

    override fun onCleared() {
        ArcFloatingOrbController.onToggleMic = null
        ArcFloatingOrbController.onOpenArcHome = null
        floatingOrbOverlay.hideFloatingOrb()
        ProactiveAlertMonitor.release(appContext)
        if (serviceBound) {
            runCatching { appContext.unbindService(serviceConnection) }
            serviceBound = false
        }
        fallbackTts.shutdown()
        super.onCleared()
    }
}
