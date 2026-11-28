package com.example.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as AndroidTtsVoice
import android.util.Log
import com.example.BuildConfig
import com.example.core.AudioRoutingController
import com.example.core.ContextDetector
import com.example.data.prefs.MaleVoicePreset
import com.example.data.prefs.VoicePathStatus
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sin

private const val TAG = "FallbackTts"

/**
 * Pure male-only speech synthesis chain with rich deep baritone bass resonance:
 * 1) ElevenLabs HD API (44.1kHz MP3, Brian / Daniel / Adam) when ELEVENLABS_API_KEY is set.
 * 2) Gemini 3.1 Flash TTS Male (`gemini-3.1-flash-tts-preview` via Firebase AI Logic, zero API key)
 *    with baritone style direction, controlled pitch-lowering AudioTrack playback, and BassBoost.
 * 3) Strict Male-Only Android native TextToSpeech offline fallback with deep pitch tuning.
 */
@OptIn(PublicPreviewAPI::class)
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
    private var audioTrack: AudioTrack? = null
    private var bassBoost: BassBoost? = null
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
        malePreset: MaleVoicePreset = MaleVoicePreset.BRITISH_JARVIS,
        deepBassEnabled: Boolean = true,
        scope: CoroutineScope
    ) {
        val spokenText = ContextDetector.formatForNaturalSpeech(text)
        if (spokenText.isBlank()) return
        stop()

        val elevenKey = BuildConfig.ELEVENLABS_API_KEY.trim()
        if (elevenKey.isNotBlank() && !elevenKey.startsWith("MY_")) {
            val played = tryElevenLabs(spokenText, malePreset.elevenLabsVoiceId, elevenKey, deepBassEnabled, scope)
            if (played) return
        }

        // Primary zero-key online path: Gemini 3.1 Flash TTS Male (Orus / Charon / Algenib)
        val geminiPlayed = tryGeminiTtsMale(spokenText, malePreset, deepBassEnabled)
        if (geminiPlayed) return

        // Offline / last-resort fallback: Strict Male-only Android native TextToSpeech
        _voicePathStatus.value = VoicePathStatus.DEVICE_TTS_FALLBACK
        speakWithMaleAndroidTts(spokenText, deepBassEnabled, scope)
    }

    private suspend fun tryGeminiTtsMale(
        text: String,
        malePreset: MaleVoicePreset,
        deepBassEnabled: Boolean
    ): Boolean {
        return try {
            val config = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(
                    voice = Voice(malePreset.geminiVoiceName)
                )
            }
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = "gemini-3.1-flash-tts-preview",
                generationConfig = config
            )
            // Guide Gemini TTS register with Instruction: Text format so style cue shapes pitch without being read aloud
            val directedPrompt = if (deepBassEnabled) {
                "Say in a very deep, calm, resonant baritone bass voice at a steady composed pace: $text"
            } else {
                "Say in a calm, steady male voice: $text"
            }

            val response = withTimeout(30_000L) {
                withContext(Dispatchers.IO) {
                    model.generateContent(directedPrompt)
                }
            }
            val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                .filterIsInstance<InlineDataPart>()
            if (parts.isEmpty()) {
                Log.w(TAG, "Gemini TTS returned no inline audio parts")
                return false
            }

            val pcmOut = ByteArrayOutputStream()
            var sampleRate = 24000
            parts.forEach { part ->
                pcmOut.write(part.inlineData)
                parseSampleRate(part.mimeType)?.let { sampleRate = it }
            }
            val pcmBytes = pcmOut.toByteArray()
            if (pcmBytes.isEmpty()) return false

            _ttsDiagnostic.value = null
            _voicePathStatus.value = VoicePathStatus.GEMINI_TTS_MALE
            playPcmAudio(pcmBytes, sampleRate, malePreset, deepBassEnabled)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "Gemini Male TTS unavailable (${e.localizedMessage ?: "offline"}) — using Male Device TTS"
            Log.w(TAG, reason)
            _ttsDiagnostic.value = reason
            false
        }
    }

    private fun parseSampleRate(mimeType: String?): Int? {
        if (mimeType.isNullOrBlank()) return null
        val match = Regex("""rate=(\d+)""").find(mimeType) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 8000..48000 }
    }

    private suspend fun playPcmAudio(
        pcmBytes: ByteArray,
        sampleRate: Int,
        malePreset: MaleVoicePreset,
        deepBassEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        val effectiveRate = if (deepBassEnabled) {
            (sampleRate * malePreset.deepBassPitchMultiplier).toInt().coerceIn(16000, sampleRate)
        } else {
            sampleRate
        }
        val minBuf = AudioTrack.getMinBufferSize(
            effectiveRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(effectiveRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        if (deepBassEnabled) {
            attachBassBoost(track.audioSessionId)
        }
        audioRouting.requestMediaSpeechFocus()
        _isSpeaking.value = true

        try {
            track.play()
            val chunkSize = 2400 // ~50ms at 24kHz 16-bit mono
            var offset = 0
            while (isActive && _isSpeaking.value && offset < pcmBytes.size) {
                val len = minOf(chunkSize, pcmBytes.size - offset)
                val written = track.write(pcmBytes, offset, len)
                if (written <= 0) break
                offset += written

                var energySum = 0.0
                var i = offset - written
                val end = offset - 1
                while (i < end) {
                    val sample = ((pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)).toShort()
                    energySum += abs(sample.toInt())
                    i += 2
                }
                val sampleCount = (written / 2).coerceAtLeast(1)
                val norm = ((energySum / sampleCount) / 6500.0).toFloat().coerceIn(0.14f, 0.96f)
                _waveformLevel.value = norm
            }

            val totalFrames = pcmBytes.size / 2
            var drainWaitMs = 0L
            while (isActive && _isSpeaking.value && track.playbackHeadPosition < totalFrames && drainWaitMs < 3500L) {
                delay(30L)
                drainWaitMs += 30L
            }
        } finally {
            _isSpeaking.value = false
            _waveformLevel.value = 0f
            audioRouting.abandonMediaSpeechFocus()
            releaseBassBoost()
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
            if (audioTrack === track) {
                audioTrack = null
            }
        }
    }

    private fun attachBassBoost(audioSessionId: Int) {
        releaseBassBoost()
        runCatching {
            if (audioSessionId > 0) {
                bassBoost = BassBoost(0, audioSessionId).apply {
                    if (strengthSupported) {
                        setStrength(700.toShort())
                    }
                    enabled = true
                }
            }
        }.onFailure {
            Log.d(TAG, "BassBoost unavailable on device output: ${it.message}")
        }
    }

    private fun releaseBassBoost() {
        runCatching {
            bassBoost?.enabled = false
            bassBoost?.release()
        }
        bassBoost = null
    }

    private suspend fun tryElevenLabs(
        text: String,
        voiceId: String,
        apiKey: String,
        deepBassEnabled: Boolean,
        scope: CoroutineScope
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128"
            val payload = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.45)
                    put("similarity_boost", 0.78)
                    put("style", 0.08)
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
                    Log.w(TAG, "ElevenLabs HTTP ${resp.code}")
                    return@withContext false
                }
                val bytes = resp.body?.bytes() ?: return@withContext false
                val tempFile = File(context.cacheDir, "arc_tts_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(bytes)

                _ttsDiagnostic.value = null
                _voicePathStatus.value = VoicePathStatus.ELEVENLABS_HD
                withContext(Dispatchers.Main) {
                    playMp3File(tempFile, deepBassEnabled, scope)
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ElevenLabs error (${e.localizedMessage ?: "network"})")
            false
        }
    }

    private suspend fun playMp3File(file: File, deepBassEnabled: Boolean, scope: CoroutineScope) {
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
                        releaseBassBoost()
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
                        releaseBassBoost()
                        runCatching { file.delete() }
                        player.release()
                        mediaPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    prepare()
                    if (deepBassEnabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            runCatching {
                                playbackParams = playbackParams.setPitch(0.86f).setSpeed(0.96f)
                            }
                        }
                        attachBassBoost(audioSessionId)
                    }
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
                releaseBassBoost()
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private suspend fun speakWithMaleAndroidTts(
        text: String,
        deepBassEnabled: Boolean,
        scope: CoroutineScope
    ) {
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

            val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            val defaultLang = Locale.getDefault().language
            val maleVoice = selectMaleAndroidVoice(voices, defaultLang)

            if (maleVoice != null) {
                tts.voice = maleVoice
                tts.setPitch(if (deepBassEnabled) 0.72f else 0.86f)
                tts.setSpeechRate(0.92f)
                Log.d(TAG, "Selected offline male Android TTS voice: ${maleVoice.name}")
            } else {
                val nonFemaleVoice = voices
                    .filter { !isKnownFemaleVoice(it.name, it.features) }
                    .sortedWith(
                        compareByDescending<AndroidTtsVoice> { it.locale.language == defaultLang || it.locale.language == "en" }
                            .thenByDescending { it.quality }
                            .thenBy { it.isNetworkConnectionRequired }
                    )
                    .firstOrNull()
                if (nonFemaleVoice != null) {
                    tts.voice = nonFemaleVoice
                } else {
                    tts.language = Locale.ENGLISH
                }
                tts.setPitch(if (deepBassEnabled) 0.62f else 0.76f)
                tts.setSpeechRate(0.92f)
                Log.w(TAG, "No explicit male offline TTS voice installed; using deep-pitch fallback.")
            }

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
                val syllable = 0.5f + 0.5f * sin(t * 2.1f)
                val phrase = 0.5f + 0.5f * sin(t * 0.73f + 1.1f)
                val accent = abs(sin(t * 3.4f))
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
        releaseBassBoost()
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
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

    companion object {
        private val MALE_CODE_REGEX = Regex(
            """(^|[-_#])(male|m|iom|iol|iob|gbb|gbd|tpd|enc|end|ene|enm|hid|hie|sfb|rjs|ahp)([-_#]|$)"""
        )

        private val MALE_TOKENS = listOf(
            "#male", "-male", "_male",
            "prabhat", "madhur", "daniel", "brian", "ryan", "arthur", "guy"
        )

        private val FEMALE_CODE_REGEX = Regex(
            """(^|[-_#])(female|f|iog|sfg|tpf|ena|enb|hia|hib|hic|gba|gbc|gbf)([-_#]|$)"""
        )

        private val FEMALE_TOKENS = listOf(
            "#female", "-female", "_female",
            "neerja", "swara", "kalpana", "heera", "samantha", "karen", "victoria"
        )

        internal fun isKnownMaleVoice(name: String, features: Set<String>? = null): Boolean {
            if (isKnownFemaleVoice(name, features)) return false
            val n = name.lowercase(Locale.ROOT)
            val hasMaleFeature = features?.any { f ->
                val fl = f.lowercase(Locale.ROOT)
                fl.contains("male") && !fl.contains("female")
            } == true
            if (hasMaleFeature) return true
            return MALE_CODE_REGEX.containsMatchIn(n) ||
                MALE_TOKENS.any { token -> n.contains(token) } ||
                Regex("""\bmale\b""").containsMatchIn(n)
        }

        internal fun isKnownFemaleVoice(name: String, features: Set<String>? = null): Boolean {
            val n = name.lowercase(Locale.ROOT)
            val hasFemaleFeature = features?.any { it.lowercase(Locale.ROOT).contains("female") } == true
            if (hasFemaleFeature) return true
            return FEMALE_CODE_REGEX.containsMatchIn(n) ||
                FEMALE_TOKENS.any { token -> n.contains(token) } ||
                Regex("""\bfemale\b""").containsMatchIn(n)
        }

        internal fun selectMaleAndroidVoice(
            voices: Set<AndroidTtsVoice>,
            defaultLang: String
        ): AndroidTtsVoice? {
            val maleVoices = voices.filter { isKnownMaleVoice(it.name, it.features) }
            if (maleVoices.isEmpty()) return null
            return maleVoices
                .sortedWith(
                    compareByDescending<AndroidTtsVoice> { it.locale.language == defaultLang }
                        .thenByDescending { it.locale.language == "en" }
                        .thenByDescending { it.locale.country == "IN" || it.locale.country == "GB" }
                        .thenByDescending { it.quality }
                        .thenBy { it.isNetworkConnectionRequired }
                )
                .firstOrNull()
        }
    }
}
