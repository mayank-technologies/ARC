package com.example.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as AndroidTtsVoice
import android.util.Log
import com.example.BuildConfig
import com.example.core.AudioRoutingController
import com.example.core.ContextDetector
import com.example.data.prefs.MaleVoicePreset
import com.example.data.prefs.VoiceGender
import com.example.data.prefs.VoicePathStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.sin

private const val TAG = "FallbackTts"

/**
 * Wideband speech synthesis chain for typed commands, vision Q&A, routines, and Live fallback:
 * 1) ElevenLabs HD API (44.1kHz MP3, eleven_multilingual_v2, speaker boost & low-artifact warmth)
 *    using Brian / Daniel / Adam presets for a deep, natural JARVIS-style male voice.
 * 2) Last-resort Android native TextToSpeech strictly matched to gender + deep pitch (~0.86f Male)
 *    with transparent diagnostic status when ELEVENLABS_API_KEY is missing or unreachable.
 */
class FallbackTtsManager(
    private val context: Context,
    private val audioRouting: AudioRoutingController
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private var androidTts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var pulseJob: Job? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking = _isSpeaking.asStateFlow()

    private val _waveformLevel = MutableStateFlow(0f)
    val waveformLevel = _waveformLevel.asStateFlow()

    private val _voicePathStatus = MutableStateFlow(VoicePathStatus.IDLE)
    val voicePathStatus = _voicePathStatus.asStateFlow()

    private val _ttsDiagnostic = MutableStateFlow<String?>(null)
    val ttsDiagnostic = _ttsDiagnostic.asStateFlow()

    init {
        androidTts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (!ttsReady) {
                Log.w(TAG, "Android native TTS failed to initialize: $status")
            }
        }
    }

    suspend fun speak(
        text: String,
        gender: VoiceGender,
        malePreset: MaleVoicePreset = MaleVoicePreset.WARM_COMPANION,
        scope: CoroutineScope
    ) {
        val spokenText = ContextDetector.formatForNaturalSpeech(text)
        if (spokenText.isBlank()) return
        stop()

        val voiceId = if (gender == VoiceGender.MALE) {
            malePreset.elevenLabsVoiceId
        } else {
            gender.elevenLabsVoiceId
        }

        val elevenKey = BuildConfig.ELEVENLABS_API_KEY.trim()
        if (elevenKey.isNotBlank() && !elevenKey.startsWith("MY_")) {
            val played = tryElevenLabs(spokenText, voiceId, elevenKey, scope)
            if (played) return
        } else {
            _ttsDiagnostic.value = "ELEVENLABS_API_KEY not set in AI Studio Secrets — using Device TTS"
        }

        _voicePathStatus.value = VoicePathStatus.DEVICE_TTS_FALLBACK
        speakWithAndroidTts(spokenText, gender, scope)
    }

    private suspend fun tryElevenLabs(
        text: String,
        voiceId: String,
        apiKey: String,
        scope: CoroutineScope
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Request wideband 44.1kHz 128kbps MP3 so low-end male chest resonance stays rich
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128"
            val payload = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.45)
                    put("similarity_boost", 0.78)
                    put("style", 0.08) // Low style avoids vocal wobble/metallic artifacts on deep voices
                    put("use_speaker_boost", true)
                })
            }.toString()

            val req = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", apiKey)
                .addHeader("Accept", "audio/mpeg")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val reason = "ElevenLabs HTTP ${resp.code} — using Device TTS"
                    Log.w(TAG, reason)
                    _ttsDiagnostic.value = reason
                    return@withContext false
                }
                val bytes = resp.body?.bytes() ?: return@withContext false
                val tempFile = File(context.cacheDir, "arc_tts_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(bytes)

                _ttsDiagnostic.value = null
                _voicePathStatus.value = VoicePathStatus.ELEVENLABS_HD
                withContext(Dispatchers.Main) {
                    playMp3File(tempFile, scope)
                }
                true
            }
        } catch (e: Exception) {
            val reason = "ElevenLabs error (${e.localizedMessage ?: "network"}) — using Device TTS"
            Log.w(TAG, reason)
            _ttsDiagnostic.value = reason
            false
        }
    }

    private suspend fun playMp3File(file: File, scope: CoroutineScope) {
        suspendCancellableCoroutine { cont ->
            try {
                audioRouting.requestMediaSpeechFocus()
                _isSpeaking.value = true
                startEnvelopePulse(scope)
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        _isSpeaking.value = false
                        _waveformLevel.value = 0f
                        pulseJob?.cancel()
                        audioRouting.abandonMediaSpeechFocus()
                        runCatching { file.delete() }
                        it.release()
                        mediaPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                    setOnErrorListener { player, _, _ ->
                        _isSpeaking.value = false
                        _waveformLevel.value = 0f
                        pulseJob?.cancel()
                        audioRouting.abandonMediaSpeechFocus()
                        runCatching { file.delete() }
                        player.release()
                        mediaPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = mp
                cont.invokeOnCancellation {
                    stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaPlayer failure", e)
                _isSpeaking.value = false
                pulseJob?.cancel()
                audioRouting.abandonMediaSpeechFocus()
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private suspend fun speakWithAndroidTts(text: String, gender: VoiceGender, scope: CoroutineScope) {
        val tts = androidTts ?: return
        var waitedMs = 0L
        while (!ttsReady && waitedMs < 2500L) {
            delay(50L)
            waitedMs += 50L
        }
        if (!ttsReady) return

        withContext(Dispatchers.Main) {
            audioRouting.requestMediaSpeechFocus()
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            // Strictly select highest-quality matching male/female voice so gender never silently flips
            val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            val preferredTag = if (gender == VoiceGender.MALE) "male" else "female"
            val oppositeTag = if (gender == VoiceGender.MALE) "female" else "male"

            val matchedVoice = voices
                .filter { v ->
                    val n = v.name.lowercase()
                    (n.contains(preferredTag) && !n.contains(oppositeTag)) ||
                        (gender == VoiceGender.MALE && (n.contains("iom") || n.contains("iol") || n.contains("gba") || n.contains("#male"))) ||
                        (gender == VoiceGender.FEMALE && (n.contains("iog") || n.contains("#female")))
                }
                .sortedWith(
                    compareByDescending<AndroidTtsVoice> { it.locale.language == Locale.getDefault().language }
                        .thenByDescending { it.quality }
                        .thenBy { it.isNetworkConnectionRequired }
                )
                .firstOrNull()

            if (matchedVoice != null) {
                tts.voice = matchedVoice
            } else {
                tts.language = Locale.getDefault()
                Log.w(TAG, "Exact $preferredTag voice unavailable; applying deep pitch tuning.")
            }

            tts.setPitch(if (gender == VoiceGender.MALE) 0.86f else 1.04f)
            tts.setSpeechRate(0.94f)

            suspendCancellableCoroutine { cont ->
                val utteranceId = UUID.randomUUID().toString()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        _isSpeaking.value = true
                        startEnvelopePulse(scope)
                    }

                    override fun onDone(id: String?) {
                        _isSpeaking.value = false
                        _waveformLevel.value = 0f
                        pulseJob?.cancel()
                        audioRouting.abandonMediaSpeechFocus()
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        _isSpeaking.value = false
                        _waveformLevel.value = 0f
                        pulseJob?.cancel()
                        audioRouting.abandonMediaSpeechFocus()
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                cont.invokeOnCancellation {
                    stop()
                }
            }
        }
    }

    private fun startEnvelopePulse(scope: CoroutineScope) {
        pulseJob?.cancel()
        pulseJob = scope.launch {
            var t = 0f
            while (_isSpeaking.value) {
                // Multi-harmonic syllable envelope (0.14..0.96) for natural in-out sphere breathing
                val syllable = 0.5f + 0.5f * kotlin.math.sin(t * 2.1f)
                val phrase = 0.5f + 0.5f * kotlin.math.sin(t * 0.73f + 1.1f)
                val accent = kotlin.math.abs(kotlin.math.sin(t * 3.4f))
                _waveformLevel.value = (0.14f + 0.52f * syllable * phrase + 0.30f * accent)
                    .coerceIn(0.14f, 0.96f)
                t += 0.16f
                delay(36L)
            }
        }
    }

    fun stop() {
        pulseJob?.cancel()
        _isSpeaking.value = false
        _waveformLevel.value = 0f
        audioRouting.abandonMediaSpeechFocus()
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        runCatching { androidTts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { androidTts?.shutdown() }
        androidTts = null
    }
}
