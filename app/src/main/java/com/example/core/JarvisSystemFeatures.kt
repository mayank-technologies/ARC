package com.example.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.example.data.local.ProtocolEntity
import com.example.device.ActionOutcome
import com.example.device.DeviceActionExecutor
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Live hardware and OS telemetry snapshot for JARVIS System Diagnostics.
 * Every value comes directly from Android system services (no simulated numbers).
 */
data class SystemDiagnosticsSnapshot(
    val deviceModel: String,
    val androidVersion: String,
    val cpuCores: Int,
    val ramTotalMb: Int,
    val ramAvailMb: Int,
    val ramUsedPercent: Int,
    val storageTotalGb: Double,
    val storageFreeGb: Double,
    val storageUsedPercent: Int,
    val batteryPercent: Int,
    val batteryTempC: Float,
    val isCharging: Boolean,
    val batteryHealth: String,
    val networkLabel: String,
    val linkDownMbps: Int,
    val thermalStatus: String,
    val capturedAtMillis: Long = System.currentTimeMillis()
) {
    fun toHinglishReport(userName: String): String {
        val safeName = userName.ifBlank { "Sir" }
        val freeRamPct = (100 - ramUsedPercent).coerceIn(0, 100)
        val freeStorageStr = String.format(Locale.US, "%.1f", storageFreeGb)
        val tempStr = String.format(Locale.US, "%.1f", batteryTempC)
        val chargingStr = if (isCharging) "aur abhi charge ho rahi hai" else ""
        return "System check complete, $safeName. Battery $batteryPercent percent hai $chargingStr " +
            "aur temperature $tempStr degree Celsius hai. RAM $freeRamPct percent free hai, " +
            "storage $freeStorageStr GB khali hai, network $networkLabel par hai, " +
            "aur thermal status ${thermalStatus.lowercase()} hai."
                .replace("  ", " ")
    }
}

data class ProtocolRunResult(
    val protocolName: String,
    val stepDetails: List<String>,
    val spokenHinglishSummary: String
)

/**
 * Process-wide bridge connecting the Floating Mini JARVIS Orb overlay
 * directly to ArcViewModel's manual Mic toggle & Home launch.
 */
object ArcFloatingOrbController {
    @Volatile
    var onToggleMic: (() -> Unit)? = null

    @Volatile
    var onOpenArcHome: (() -> Unit)? = null
}

object JarvisSystemFeatures {

    fun collectDiagnostics(context: Context): SystemDiagnosticsSnapshot {
        // 1) RAM
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(1)
        val availRamMb = (memInfo.availMem / (1024L * 1024L)).toInt().coerceIn(0, totalRamMb)
        val ramUsedPct = (((memInfo.totalMem - memInfo.availMem).toDouble() / memInfo.totalMem) * 100.0)
            .roundToInt().coerceIn(0, 100)

        // 2) Internal Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes.coerceAtLeast(1L)
        val freeBytes = stat.availableBytes.coerceIn(0L, totalBytes)
        val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
        val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)
        val storageUsedPct = (((totalBytes - freeBytes).toDouble() / totalBytes) * 100.0)
            .roundToInt().coerceIn(0, 100)

        // 3) Battery & Temperature
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val levelProp = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryPct = if (levelProp in 0..100) {
            levelProp
        } else {
            val lvl = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (lvl >= 0 && scale > 0) (lvl * 100) / scale else 0
        }
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val tempC = tempTenths / 10f
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = bm.isCharging ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val healthCode = batteryIntent?.getIntExtra(
            BatteryManager.EXTRA_HEALTH,
            BatteryManager.BATTERY_HEALTH_GOOD
        ) ?: BatteryManager.BATTERY_HEALTH_GOOD
        val healthLabel = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Needs Service"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Normal"
        }

        // 4) Network Connectivity
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        val networkLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
        val downMbps = ((caps?.linkDownstreamBandwidthKbps ?: 0) / 1000).coerceAtLeast(0)

        // 5) Thermal Status
        val thermalLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Nominal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Load"
                PowerManager.THERMAL_STATUS_SEVERE -> "Warm / Heavy"
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical Heat"
                else -> "Nominal"
            }
        } else {
            "Normal"
        }

        return SystemDiagnosticsSnapshot(
            deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            ramTotalMb = totalRamMb,
            ramAvailMb = availRamMb,
            ramUsedPercent = ramUsedPct,
            storageTotalGb = totalGb,
            storageFreeGb = freeGb,
            storageUsedPercent = storageUsedPct,
            batteryPercent = batteryPct,
            batteryTempC = tempC,
            isCharging = isCharging,
            batteryHealth = healthLabel,
            networkLabel = networkLabel,
            linkDownMbps = downMbps,
            thermalStatus = thermalLabel
        )
    }

    fun executeProtocolActions(
        context: Context,
        protocol: ProtocolEntity,
        executor: DeviceActionExecutor
    ): ProtocolRunResult {
        val steps = mutableListOf<String>()

        if (protocol.brightnessPercent in 5..100) {
            when (val res = executor.adjustBrightness(protocol.brightnessPercent)) {
                is ActionOutcome.Success -> steps.add("Brightness ${protocol.brightnessPercent}%")
                is ActionOutcome.NeedsUserAction -> steps.add("Display settings opened")
                is ActionOutcome.Failure -> steps.add(res.reason)
            }
        }

        if (protocol.volumeMode != "UNCHANGED") {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxMusic = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetMusic = when (protocol.volumeMode) {
                "MUTE" -> 0
                "LOW" -> (maxMusic * 0.25f).roundToInt().coerceAtLeast(1)
                "MEDIUM" -> (maxMusic * 0.60f).roundToInt()
                "HIGH" -> (maxMusic * 0.88f).roundToInt()
                else -> -1
            }
            if (targetMusic >= 0) {
                runCatching {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, AudioManager.FLAG_SHOW_UI)
                    steps.add("Volume ${protocol.volumeMode.lowercase()}")
                }
            }
        }

        when (protocol.torchMode) {
            "ON" -> {
                if (executor.toggleFlashlight(enable = true) is ActionOutcome.Success) {
                    steps.add("Flashlight ON")
                }
            }
            "OFF" -> {
                if (executor.toggleFlashlight(enable = false) is ActionOutcome.Success) {
                    steps.add("Flashlight OFF")
                }
            }
        }

        if (protocol.openWifiPanel) {
            executor.toggleWifi()
            steps.add("Wi-Fi panel")
        }

        if (protocol.openBluetoothSettings) {
            executor.openBluetoothSettings()
            steps.add("Bluetooth settings")
        }

        if (protocol.openAppName.isNotBlank()) {
            executor.openApp(protocol.openAppName)
            steps.add("${protocol.openAppName} launched")
        }

        when (protocol.micMode) {
            "ON" -> steps.add("Mic ON")
            "OFF" -> steps.add("Mic OFF")
        }
        if (protocol.goStandby) {
            steps.add("Standby ready")
        }

        val detailSummary = if (steps.isEmpty()) "configured actions" else steps.joinToString(", ")
        val spoken = "${protocol.name} active ho gaya hai — $detailSummary set kar diya gaya hai."
        return ProtocolRunResult(
            protocolName = protocol.name,
            stepDetails = steps,
            spokenHinglishSummary = spoken
        )
    }
}
