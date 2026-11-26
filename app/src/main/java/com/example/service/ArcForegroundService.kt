package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.ProactiveAlertMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "ArcForegroundService"
private const val FG_CHANNEL_ID = "arc_foreground_channel"
private const val FG_NOTIFICATION_ID = 1001
private const val ACTION_MUTE_FROM_NOTIFICATION = "com.example.arc.ACTION_MUTE_MIC"

sealed class ServiceEvent {
    // Emitted when user taps "Mute Mic" directly from the persistent foreground notification
    data object MicMuteRequested : ServiceEvent()
    data class AntiTheftTriggered(val reason: String) : ServiceEvent()
    data object ScreenShareStopped : ServiceEvent()
}

/**
 * Foreground Service managing active background capabilities ONLY when explicitly enabled:
 *
 * CHANGE (Porcupine / wake-word removed):
 * - Removed Picovoice Porcupine & background SpeechRecognizer "Hey ARC" wake-word loops completely.
 * - Service now enters foreground microphone mode ONLY when the user manually toggles Mic ON
 *   (showing persistent notification "ARC mic is active"), keeping the Gemini Live session alive
 *   even when ARC is minimized or in the background.
 * - When Mic is OFF/MUTED (and neither Anti-Theft Guard nor Screen Share is active), the service
 *   stops foreground mode and shuts itself down (stopSelf) so zero audio is processed and zero
 *   battery is drained in the background.
 */
class ArcForegroundService : Service(), SensorEventListener {

    inner class LocalBinder : Binder() {
        fun getService(): ArcForegroundService = this@ArcForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var overlayManager: ArcEdgeOverlayManager
    private var sensorManager: SensorManager? = null
    private var mediaProjection: MediaProjection? = null

    private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _isSirenPlaying = MutableStateFlow(false)
    val isSirenPlaying = _isSirenPlaying.asStateFlow()

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing = _isScreenSharing.asStateFlow()

    private var isMicActive = false
    private var isArmed = false
    private var lastAcceleration = 9.8f
    private var armedTimestamp = 0L
    private var sirenJob: Job? = null

    private val powerDisconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_POWER_DISCONNECTED && isArmed) {
                triggerAntiTheft("Charging cable unplugged while Security Guard armed")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlayManager = ArcEdgeOverlayManager(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createChannel()
        registerReceiver(powerDisconnectReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        ProactiveAlertMonitor.acquire(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle quick "Turn Mic OFF" action button from the persistent notification
        if (intent?.action == ACTION_MUTE_FROM_NOTIFICATION) {
            isMicActive = false
            _events.tryEmit(ServiceEvent.MicMuteRequested)
            stopIfNothingActive()
            return START_NOT_STICKY
        }

        // Promote to foreground immediately on startForegroundService() per Android OS requirement
        promoteToForeground()
        return if (isMicActive || isArmed || _isScreenSharing.value) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        ProactiveAlertMonitor.release(this)
        stopSiren()
        stopScreenSharing()
        overlayManager.hideEdgeGlow()
        runCatching { unregisterReceiver(powerDisconnectReceiver) }
        sensorManager?.unregisterListener(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    fun setEdgeGlow(visible: Boolean, isSpeaking: Boolean) {
        if (visible) overlayManager.showEdgeGlow(isSpeaking) else overlayManager.hideEdgeGlow()
    }

    /**
     * Called whenever the user toggles the manual Mic ON/MUTED button.
     * - active = true  -> keeps foreground service running with "ARC mic is active" notification
     * - active = false -> hides edge glow and stops foreground service if no other guard is active
     */
    fun updateMicActive(active: Boolean) {
        isMicActive = active
        if (active) {
            promoteToForeground()
        } else {
            overlayManager.hideEdgeGlow()
            stopIfNothingActive()
        }
    }

    private fun promoteToForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = if (isMicActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
            if (_isScreenSharing.value) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (type == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(FG_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(FG_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Shuts down the foreground service cleanly when Mic is OFF and neither
     * Anti-Theft Guard nor Screen Sharing is running — preventing idle battery drain.
     */
    private fun stopIfNothingActive() {
        if (!isMicActive && !isArmed && !_isScreenSharing.value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(FG_NOTIFICATION_ID, buildForegroundNotification())
        }
    }

    fun setAntiTheftArmed(armed: Boolean) {
        isArmed = armed
        if (armed) {
            armedTimestamp = System.currentTimeMillis()
            promoteToForeground()
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } else {
            sensorManager?.unregisterListener(this)
            stopSiren()
            stopIfNothingActive()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isArmed || event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        if (System.currentTimeMillis() - armedTimestamp < 2500L) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = kotlin.math.abs(magnitude - lastAcceleration)
        lastAcceleration = magnitude
        if (delta > 4.2f && !_isSirenPlaying.value) {
            triggerAntiTheft("Motion detected while Security Guard armed")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerAntiTheft(reason: String) {
        if (_isSirenPlaying.value) return
        _isSirenPlaying.value = true
        _events.tryEmit(ServiceEvent.AntiTheftTriggered(reason))
        startLoudSiren()
    }

    private fun startLoudSiren() {
        sirenJob?.cancel()
        sirenJob = serviceScope.launch {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

                val sampleRate = 22050
                val bufferSamples = sampleRate / 2
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, bufferSamples * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                val pcm = ShortArray(bufferSamples)
                var phase = 0.0
                var tick = 0
                while (_isSirenPlaying.value) {
                    val freq = if (tick % 2 == 0) 960.0 else 680.0
                    for (i in pcm.indices) {
                        pcm[i] = (sin(phase) * Short.MAX_VALUE * 0.92).toInt().toShort()
                        phase += 2.0 * PI * freq / sampleRate
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                    }
                    track.write(pcm, 0, pcm.size)
                    tick++
                }
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "Siren playback error", e)
            }
        }
    }

    fun stopSiren() {
        _isSirenPlaying.value = false
        sirenJob?.cancel()
        sirenJob = null
    }

    fun startScreenSharing(resultCode: Int, data: Intent) {
        try {
            _isScreenSharing.value = true
            promoteToForeground()
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopScreenSharing()
                }
            }, mainHandler)
            overlayManager.showStopSharingPill {
                stopScreenSharing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection", e)
            stopScreenSharing()
        }
    }

    fun stopScreenSharing() {
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        _isScreenSharing.value = false
        overlayManager.hideStopSharingPill()
        _events.tryEmit(ServiceEvent.ScreenShareStopped)
        stopIfNothingActive()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                FG_CHANNEL_ID,
                getString(R.string.fg_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent transparent indicator while ARC mic is active"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            isMicActive -> getString(R.string.fg_notification_title) // "ARC mic is active"
            _isScreenSharing.value -> "ARC is sharing screen"
            else -> "ARC Anti-Theft Guard is armed"
        }
        val text = when {
            isMicActive -> getString(R.string.fg_notification_text)
            _isScreenSharing.value -> "Tap Stop Sharing pill anytime to end screen capture."
            else -> "Motion & unplug siren guard active."
        }

        val builder = NotificationCompat.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)

        if (isMicActive) {
            val muteIntent = Intent(this, ArcForegroundService::class.java).apply {
                action = ACTION_MUTE_FROM_NOTIFICATION
            }
            val mutePi = PendingIntent.getService(
                this,
                1,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Mute Mic", mutePi)
        }

        return builder.build()
    }
}
