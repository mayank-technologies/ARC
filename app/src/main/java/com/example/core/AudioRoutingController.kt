package com.example.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

private const val TAG = "AudioRouting"

/**
 * Manages wideband speaker/headset routing and Android AudioFocus for both
 * Gemini Live bidirectional conversations and ElevenLabs HD playback.
 *
 * Why this matters:
 * Android's internal Live voice stream defaults to narrowband telephony/earpiece
 * DSP unless built-in speakerphone (or an attached Bluetooth/wired headset) is
 * explicitly selected as the communication device. Routing to TYPE_BUILTIN_SPEAKER
 * preserves deep chest-voice bass and eliminates metallic/tinny robotic artifacts.
 */
class AudioRoutingController(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMode: Int? = null
    private var savedSpeakerphoneOn: Boolean? = null
    private var liveFocusRequest: AudioFocusRequest? = null
    private var mediaFocusRequest: AudioFocusRequest? = null

    fun enterLiveVoiceMode() {
        runCatching {
            if (savedMode == null) {
                savedMode = audioManager.mode
                @Suppress("DEPRECATION")
                savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                liveFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Live session handles barge-in via VAD */ }
                    .build()
                audioManager.requestAudioFocus(req)
                liveFocusRequest = req
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val externalHeadset = devices.firstOrNull { dev ->
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val chosen = externalHeadset ?: speaker
                if (chosen != null) {
                    val ok = audioManager.setCommunicationDevice(chosen)
                    Log.d(TAG, "Wideband communication device selected: ${chosen.productName} (type=${chosen.type}, ok=$ok)")
                }
            } else {
                @Suppress("DEPRECATION")
                val hasExternal = audioManager.isBluetoothScoOn ||
                    audioManager.isBluetoothA2dpOn ||
                    audioManager.isWiredHeadsetOn
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = !hasExternal
                Log.d(TAG, "Legacy speakerphone routing enabled=${!hasExternal}")
            }
        }.onFailure {
            Log.w(TAG, "Unable to apply wideband live routing: ${it.message}")
        }
    }

    fun exitLiveVoiceMode() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            savedSpeakerphoneOn?.let { prevSpeaker ->
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = prevSpeaker
            }
            savedMode?.let { prevMode ->
                audioManager.mode = prevMode
            } ?: run {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            savedMode = null
            savedSpeakerphoneOn = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                liveFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                liveFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }.onFailure {
            Log.w(TAG, "Failed to restore audio routing: ${it.message}")
        }
    }

    fun requestMediaSpeechFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mediaFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(req)
                mediaFocusRequest = req
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }.onFailure {
            Log.w(TAG, "Media speech focus request failed: ${it.message}")
        }
    }

    fun abandonMediaSpeechFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mediaFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                mediaFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }
}
