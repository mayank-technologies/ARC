package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.AudioRoutingController
import com.example.core.ContextDetector
import com.example.data.local.ArcLocalRepository
import com.example.data.prefs.MaleVoicePreset
import com.example.data.prefs.VoicePathStatus
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Transcription
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.sin

private const val TAG = "GeminiLiveEngine"

enum class VoiceEngineState {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    PROCESSING,
    FALLBACK_LIMITED,
    ERROR
}

enum class ModelTier(val modelName: String, val label: String) {
    TIER_0_LIVE_3_1("gemini-3.1-flash-live-preview", "Tier 0: Gemini 3.1 Live HD"),
    TIER_1_NATIVE_AFFECTIVE("gemini-2.5-flash-native-audio-preview-12-2025", "Tier 1: Native Audio 2.5"),
    TIER_2_NATIVE_STANDARD("gemini-2.5-flash-native-audio-preview-09-2025", "Tier 2: Native Audio Fallback"),
    TIER_3_TEXT_TTS("gemini-3.8-flash", "Tier 3: ElevenLabs / Fallback TTS")
}

/**
 * Core Gemini Live bidirectional voice engine with:
 * - 4-tier model fallback (Gemini 3.1 Live -> 2.5 Native Audio -> Gemini 3.1 Flash TTS Male)
 * - Male-only JARVIS presets (Orus / Charon / Algenib) with female voice permanently removed
 * - Wideband speaker/headset audio routing so Live audio avoids telephony DSP compression
 * - Context-aware conversational pacing (GREETING / FACTUAL / CASUAL / EMPATHETIC)
 * - Real-time waveform bar levels (32 bars) that never flicker/collapse during silence gaps
 * - Multimodal camera/image analysis via Gemini 3.8 Flash
 */
@OptIn(PublicPreviewAPI::class)
class GeminiLiveEngine(
    private val context: Context,
    private val repository: ArcLocalRepository,
    private val toolRegistry: ArcToolRegistry,
    private val fallbackTts: FallbackTtsManager,
    private val claudeClient: ClaudeReasoningClient,
    private val audioRouting: AudioRoutingController
) {
    private val _engineState = MutableStateFlow(VoiceEngineState.IDLE)
    val engineState = _engineState.asStateFlow()

    private val _activeTier = MutableStateFlow(ModelTier.TIER_0_LIVE_3_1)
    val activeTier = _activeTier.asStateFlow()

    private val _waveformBars = MutableStateFlow(List(32) { 0.12f })
    val waveformBars = _waveformBars.asStateFlow()

    private val _speechEnergy = MutableStateFlow(0f)
    val speechEnergy = _speechEnergy.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage = _statusMessage.asStateFlow()

    private val _voicePathStatus = MutableStateFlow(VoicePathStatus.IDLE)
    val voicePathStatus = _voicePathStatus.asStateFlow()

    private var liveSession: LiveSession? = null
    private var visualizer: Visualizer? = null
    private var waveformJob: Job? = null
    private var inputTranscriptBuffer = StringBuilder()
    private var outputTranscriptBuffer = StringBuilder()
    private var lastTranscriptTime = 0L

    private val visionAndChatModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.8-flash")
    }

    @SuppressLint("MissingPermission")
    suspend fun startLiveSession(
        userName: String,
        maleVoicePreset: MaleVoicePreset = MaleVoicePreset.BRITISH_JARVIS,
        scope: CoroutineScope,
        onTranscriptUpdate: (role: String, text: String) -> Unit
    ) {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _engineState.value = VoiceEngineState.ERROR
            _statusMessage.value = "Microphone permission required for voice session."
            return
        }

        stopLiveSession()
        _engineState.value = VoiceEngineState.CONNECTING
        _statusMessage.value = "Connecting to ARC Live HD..."

        val selectedGeminiVoice = maleVoicePreset.geminiVoiceName
        val voiceLabel = "Male • ${maleVoicePreset.label}"

        val memories = repository.getAllMemories().map { it.factText }
        val systemPrompt = ContextDetector.buildSystemPrompt(userName, memories)

        val liveTiers = listOf(
            ModelTier.TIER_0_LIVE_3_1,
            ModelTier.TIER_1_NATIVE_AFFECTIVE,
            ModelTier.TIER_2_NATIVE_STANDARD
        )

        var connectedSession: LiveSession? = null
        var chosenTier = ModelTier.TIER_0_LIVE_3_1
        var lastError: Exception? = null

        for (tier in liveTiers) {
            try {
                val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                    modelName = tier.modelName,
                    generationConfig = liveGenerationConfig {
                        responseModality = ResponseModality.AUDIO
                        speechConfig = SpeechConfig(voice = Voice(selectedGeminiVoice))
                        temperature = 0.88f
                        inputAudioTranscription = AudioTranscriptionConfig()
                        outputAudioTranscription = AudioTranscriptionConfig()
                    },
                    systemInstruction = content { text(systemPrompt) },
                    tools = toolRegistry.buildTools()
                )
                connectedSession = withTimeout(25_000L) {
                    liveModel.connect()
                }
                chosenTier = tier
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${tier.label} handshake failed: ${e.message}")
                lastError = e
            }
        }

        if (connectedSession != null) {
            liveSession = connectedSession
            _activeTier.value = chosenTier
            _voicePathStatus.value = VoicePathStatus.LIVE_HD
            _engineState.value = VoiceEngineState.LISTENING
            _statusMessage.value = "${chosenTier.label} • $voiceLabel"

            // Force wideband speaker/headset routing before Live AudioTrack initializes
            audioRouting.enterLiveVoiceMode()
            startWaveformVisualizer(scope)

            try {
                connectedSession.startAudioConversation(
                    functionCallHandler = { call: FunctionCallPart ->
                        _engineState.value = VoiceEngineState.PROCESSING
                        toolRegistry.handleFunctionCall(call)
                    },
                    transcriptHandler = { input: Transcription?, output: Transcription? ->
                        input?.text?.takeIf { it.isNotBlank() }?.let { chunk ->
                            inputTranscriptBuffer.append(chunk)
                            lastTranscriptTime = System.currentTimeMillis()
                            _engineState.value = VoiceEngineState.LISTENING
                        }
                        output?.text?.takeIf { it.isNotBlank() }?.let { chunk ->
                            if (inputTranscriptBuffer.isNotBlank()) {
                                val userSay = inputTranscriptBuffer.toString().trim()
                                inputTranscriptBuffer.clear()
                                onTranscriptUpdate("USER", userSay)
                            }
                            outputTranscriptBuffer.append(chunk)
                            lastTranscriptTime = System.currentTimeMillis()
                            _engineState.value = VoiceEngineState.SPEAKING
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live audio conversation failed after connect", e)
                _activeTier.value = ModelTier.TIER_3_TEXT_TTS
                _voicePathStatus.value = fallbackTts.voicePathStatus.value
                Log.w(TAG, "Live stream interrupted — falling back to Tier 3 (ElevenLabs / Fallback TTS).")
                _engineState.value = VoiceEngineState.FALLBACK_LIMITED
                _statusMessage.value = "Live stream interrupted: ${e.localizedMessage ?: "Fallback TTS active"}"
            } finally {
                audioRouting.exitLiveVoiceMode()
            }
        } else {
            // Tier 3 Fallback: Keep text + ElevenLabs HD operational
            _activeTier.value = ModelTier.TIER_3_TEXT_TTS
            _voicePathStatus.value = fallbackTts.voicePathStatus.value
            Log.w(TAG, "Live tiers unavailable (${lastError?.message}); Tier 3 ElevenLabs/TTS active.")
            _engineState.value = VoiceEngineState.FALLBACK_LIMITED
            _statusMessage.value = "Live unavailable — ElevenLabs / Fallback TTS active"
        }
    }

    suspend fun stopLiveSession() {
        waveformJob?.cancel()
        waveformJob = null
        runCatching { visualizer?.release() }
        visualizer = null
        runCatching { liveSession?.close() }
        liveSession = null
        audioRouting.exitLiveVoiceMode()
        fallbackTts.stop()
        _waveformBars.value = List(32) { 0.12f }
        _speechEnergy.value = 0f
        _engineState.value = VoiceEngineState.IDLE
        _voicePathStatus.value = VoicePathStatus.IDLE
        _statusMessage.value = "Session ended"
    }

    /**
     * Handles typed chat messages and text+TTS turns.
     * Always generates a visible text reply first (via onReplyReady) and then speaks it aloud
     * when speakResponse is true, rather than dropping typed prompts into a live audio socket.
     */
    suspend fun sendTextTurn(
        rawMessage: String,
        userName: String,
        maleVoicePreset: MaleVoicePreset = MaleVoicePreset.BRITISH_JARVIS,
        deepBassEnabled: Boolean = true,
        speakResponse: Boolean,
        scope: CoroutineScope,
        onReplyReady: (suspend (String) -> Unit)? = null
    ): Result<String> {
        val trimmed = rawMessage.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Empty message"))

        _engineState.value = VoiceEngineState.PROCESSING
        val memories = repository.getAllMemories().map { it.factText }
        val contextType = ContextDetector.detectContext(trimmed)
        val wrappedPrompt = ContextDetector.wrapTurnWithDeliveryCue(
            userMessage = trimmed,
            context = contextType,
            userName = userName,
            memorySummary = memories.take(8).joinToString("; ")
        )
        val systemPrompt = ContextDetector.buildSystemPrompt(userName, memories)

        return runTextPlusFallbackTts(
            wrappedPrompt = wrappedPrompt,
            systemPrompt = systemPrompt,
            rawUserMessage = trimmed,
            maleVoicePreset = maleVoicePreset,
            deepBassEnabled = deepBassEnabled,
            speakResponse = speakResponse,
            scope = scope,
            onReplyReady = onReplyReady
        )
    }

    private suspend fun runTextPlusFallbackTts(
        wrappedPrompt: String,
        systemPrompt: String,
        rawUserMessage: String,
        maleVoicePreset: MaleVoicePreset,
        deepBassEnabled: Boolean,
        speakResponse: Boolean,
        scope: CoroutineScope,
        onReplyReady: (suspend (String) -> Unit)? = null
    ): Result<String> {
        return try {
            val primaryResult = runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(25_000L) {
                        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                            modelName = "gemini-3.8-flash",
                            systemInstruction = content { text(systemPrompt) },
                            tools = toolRegistry.buildTools()
                        )
                        val chat = model.startChat()
                        var reply = chat.sendMessage(wrappedPrompt)
                        var hops = 0
                        while (reply.functionCalls.isNotEmpty() && hops < 4) {
                            val toolResponses = reply.functionCalls.map { call ->
                                toolRegistry.handleFunctionCall(call)
                            }
                            reply = chat.sendMessage(
                                content("function") {
                                    toolResponses.forEach { part: FunctionResponsePart -> part(part) }
                                }
                            )
                            hops++
                        }
                        reply.text?.trim().orEmpty()
                    }
                }
            }

            primaryResult.exceptionOrNull()?.let { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "Primary gemini-3.8-flash turn failed (${err.message}); trying secondary brain.")
            }

            val primaryText = primaryResult.getOrNull().orEmpty()
            val finalReply = if (primaryText.isNotBlank()) {
                primaryText
            } else {
                val fallbackRes = claudeClient.reason(rawUserMessage, systemPrompt)
                fallbackRes.exceptionOrNull()?.let { err ->
                    if (err is CancellationException) throw err
                }
                fallbackRes.getOrNull()?.first?.takeIf { it.isNotBlank() }
                    ?: return run {
                        val cause = primaryResult.exceptionOrNull()
                            ?: fallbackRes.exceptionOrNull()
                            ?: IllegalStateException("Unable to reach AI right now. Please check your connection.")
                        _engineState.value = if (liveSession != null) VoiceEngineState.LISTENING else VoiceEngineState.ERROR
                        Result.failure(cause)
                    }
            }

            // Deliver reply to chat UI immediately before TTS audio playback starts
            onReplyReady?.invoke(finalReply)

            if (speakResponse) {
                val hadLive = liveSession != null
                if (hadLive) {
                    audioRouting.exitLiveVoiceMode()
                }
                _engineState.value = VoiceEngineState.SPEAKING
                startWaveformVisualizer(scope)
                try {
                    fallbackTts.speak(finalReply, maleVoicePreset, deepBassEnabled, scope)
                    _voicePathStatus.value = fallbackTts.voicePathStatus.value
                } finally {
                    if (liveSession != null) {
                        audioRouting.enterLiveVoiceMode()
                        _engineState.value = VoiceEngineState.LISTENING
                    } else {
                        _engineState.value = VoiceEngineState.IDLE
                    }
                }
            } else {
                _engineState.value = if (liveSession != null) VoiceEngineState.LISTENING else VoiceEngineState.IDLE
            }
            Result.success(finalReply)
        } catch (e: CancellationException) {
            _engineState.value = if (liveSession != null) VoiceEngineState.LISTENING else VoiceEngineState.IDLE
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Text/Fallback turn failed", e)
            _engineState.value = if (liveSession != null) VoiceEngineState.LISTENING else VoiceEngineState.ERROR
            Result.failure(e)
        }
    }

    /**
     * Multimodal camera / photo vision analysis using Gemini 3.8 Flash.
     */
    suspend fun analyzeVisualFrame(
        bitmap: Bitmap,
        question: String,
        userName: String,
        maleVoicePreset: MaleVoicePreset = MaleVoicePreset.BRITISH_JARVIS,
        deepBassEnabled: Boolean = true,
        speakResult: Boolean,
        scope: CoroutineScope
    ): Result<String> {
        return try {
            _engineState.value = VoiceEngineState.PROCESSING
            val response = withTimeout(60_000L) {
                visionAndChatModel.generateContent(
                    content {
                        image(bitmap)
                        text(
                            "You are ARC (pronounced as the Hindi word 'आर्क' / 'aark' — never spell out A-R-C letter-by-letter, " +
                                "and in English speak it smoothly as 'aark'/'Arc' without spelling letters) assisting $userName. " +
                                "Analyze this visual input in relaxed, natural spoken English or Hinglish (no bullet lists or markdown) and respond to: $question"
                        )
                    }
                )
            }
            val text = response.text?.trim().orEmpty()
            if (text.isBlank()) {
                _engineState.value = VoiceEngineState.IDLE
                return Result.failure(IllegalStateException("Vision model returned empty output."))
            }
            if (speakResult) {
                _engineState.value = VoiceEngineState.SPEAKING
                fallbackTts.speak(text, maleVoicePreset, deepBassEnabled, scope)
                _voicePathStatus.value = fallbackTts.voicePathStatus.value
            }
            _engineState.value = VoiceEngineState.IDLE
            Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Visual analysis failed", e)
            _engineState.value = VoiceEngineState.ERROR
            Result.failure(e)
        }
    }

    /**
     * Drives smooth 32-bar waveform & real-time speechEnergy [0..1] for the CORE sphere's
     * in-out radial breathing and outer-particle flare during Live & TTS speech.
     */
    private fun startWaveformVisualizer(scope: CoroutineScope) {
        waveformJob?.cancel()
        runCatching { visualizer?.release() }
        visualizer = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                enabled = true
            }
        }.getOrNull()

        val fftBuf = ByteArray(visualizer?.captureSize ?: 128)
        waveformJob = scope.launch(Dispatchers.Default) {
            var phase = 0f
            var speechPhase = 0f
            while (_engineState.value != VoiceEngineState.IDLE && _engineState.value != VoiceEngineState.ERROR) {
                val now = System.currentTimeMillis()
                val visLv = visualizer?.let { vis ->
                    runCatching {
                        if (vis.getWaveForm(fftBuf) == Visualizer.SUCCESS) {
                            var sum = 0f
                            for (b in fftBuf) sum += abs((b.toInt() and 0xFF) - 128)
                            (sum / (fftBuf.size * 38f)).coerceIn(0f, 1f)
                        } else 0f
                    }.getOrDefault(0f)
                } ?: 0f

                if (visLv > 0.05f) {
                    lastTranscriptTime = now
                }

                // Auto-return from SPEAKING -> LISTENING once Live speech audio & transcripts settle
                if (_engineState.value == VoiceEngineState.SPEAKING &&
                    liveSession != null &&
                    !fallbackTts.isSpeaking.value &&
                    now - lastTranscriptTime > 2400L
                ) {
                    _engineState.value = VoiceEngineState.LISTENING
                }

                val activeLevel = when (_engineState.value) {
                    VoiceEngineState.SPEAKING -> {
                        val ttsLv = fallbackTts.waveformLevel.value
                        // Organic syllable cadence fallback when OS Visualizer is silent on MODE_IN_COMMUNICATION
                        val cadence = (0.20f +
                            0.46f * (0.5f + 0.5f * sin(speechPhase * 1.9f)) * (0.5f + 0.5f * sin(speechPhase * 0.65f + 0.8f)) +
                            0.24f * abs(sin(speechPhase * 3.1f)))
                            .coerceIn(0.16f, 0.92f)
                        if (fallbackTts.isSpeaking.value) {
                            maxOf(ttsLv, visLv)
                        } else if (visLv > 0.08f) {
                            visLv
                        } else {
                            cadence
                        }
                    }
                    VoiceEngineState.LISTENING -> maxOf(visLv * 0.5f, 0.16f)
                    VoiceEngineState.PROCESSING -> 0.14f
                    else -> 0.05f
                }

                _speechEnergy.value = activeLevel

                val recentSpeechBoost = if (now - lastTranscriptTime < 900L) 0.12f else 0f
                val bars = List(32) { idx ->
                    val centerWeight = 1f - abs(idx - 15.5f) / 16.5f
                    val wave = 0.5f + 0.5f * sin(phase + idx * 0.42f)
                    (0.12f + (activeLevel + recentSpeechBoost) * centerWeight * (0.45f + 0.55f * wave))
                        .coerceIn(0.12f, 1.0f)
                }
                _waveformBars.value = bars
                phase += 0.34f
                speechPhase += 0.17f
                delay(38L)
            }
            _speechEnergy.value = 0f
            _waveformBars.value = List(32) { 0.12f }
        }
    }
}
