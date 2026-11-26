package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "ProactiveAlertMonitor"

enum class ProactiveAlertType {
    BATTERY_LOW,
    CHARGING_STARTED,
    DATA_SAVER
}

data class ProactiveAlertEvent(
    val type: ProactiveAlertType,
    val message: String,
    val batteryPercent: Int? = null
)

/**
 * Monitors device battery thresholds (20%, 15%, 10%), charging start transitions,
 * and metered mobile-data usage for heavy actions — WITHOUT any background polling
 * or invasive data-usage tracking permissions.
 *
 * Beginner-friendly lifecycle:
 * - Call `acquire(context)` from ArcViewModel or ArcForegroundService when active.
 * - Call `release(context)` when torn down. Reference counting prevents double registration
 *   and prevents duplicate spoken alerts when both UI and foreground service are alive.
 */
object ProactiveAlertMonitor {

    // Descending thresholds: once ARC speaks an alert at a threshold, it won't repeat
    // until battery drops to a lower threshold OR charges up and drains again.
    private val BATTERY_THRESHOLDS = listOf(20, 15, 10)
    private const val DATA_SAVER_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes between mobile-data warnings

    private val lock = Any()
    private var refCount = 0
    private var receiverRegistered = false
    private var appCtx: Context? = null

    @Volatile
    private var alertsEnabled: Boolean = true

    // Tracks which thresholds have already triggered in the current discharge cycle
    private val alertedThresholds = mutableSetOf<Int>()
    private var lastIsCharging: Boolean? = null
    private var wasLowBeforeCharging: Boolean = false
    private var lastDataWarningMs: Long = 0L

    private val _alerts = MutableSharedFlow<ProactiveAlertEvent>(extraBufferCapacity = 8)
    val alerts = _alerts.asSharedFlow()

    // Android sends ACTION_BATTERY_CHANGED automatically whenever % or charger state changes
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                handleBatteryIntent(intent)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            alertsEnabled = enabled
            if (!enabled) {
                // Reset transient cycle flags cleanly when user turns Proactive Alerts OFF
                alertedThresholds.clear()
                wasLowBeforeCharging = false
            }
        }
    }

    fun acquire(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            appCtx = ctx
            refCount++
            if (!receiverRegistered) {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                // Sticky broadcast immediately returns current battery snapshot if available
                val sticky = ctx.registerReceiver(batteryReceiver, filter)
                receiverRegistered = true
                sticky?.let { handleBatteryIntent(it) }
                Log.d(TAG, "Battery proactive monitor registered")
            }
        }
    }

    fun release(context: Context) {
        synchronized(lock) {
            refCount = (refCount - 1).coerceAtLeast(0)
            if (refCount == 0 && receiverRegistered) {
                runCatching { appCtx?.unregisterReceiver(batteryReceiver) }
                receiverRegistered = false
                Log.d(TAG, "Battery proactive monitor unregistered")
            }
        }
    }

    private fun handleBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        var pendingEvent: ProactiveAlertEvent? = null

        synchronized(lock) {
            val prevCharging = lastIsCharging
            lastIsCharging = isCharging

            // 1) Charging started confirmation when battery had dipped into low zone
            if (prevCharging == false && isCharging && wasLowBeforeCharging) {
                wasLowBeforeCharging = false
                alertedThresholds.clear()
                if (alertsEnabled) {
                    pendingEvent = ProactiveAlertEvent(
                        type = ProactiveAlertType.CHARGING_STARTED,
                        message = "Charging shuru ho gayi hai, battery abhi $percent% hai.",
                        batteryPercent = percent
                    )
                }
                if (pendingEvent != null) {
                    _alerts.tryEmit(pendingEvent!!)
                }
                return
            }

            if (isCharging) {
                // Reset thresholds once battery climbs slightly above each threshold (hysteresis +3%)
                alertedThresholds.removeAll { threshold -> percent > threshold + 3 }
                if (percent > 30) {
                    wasLowBeforeCharging = false
                }
                return
            }

            // 2) Discharging path: remember if battery dropped into low range (<= 25%)
            if (percent <= 25) {
                wasLowBeforeCharging = true
            }

            if (!alertsEnabled) return

            // Find the lowest threshold currently crossed (e.g., if jumping 22% -> 14%, speak 15%/14% once
            // and mark higher thresholds like 20% as covered so ARC doesn't repeat alerts back-to-back)
            val crossedThresholds = BATTERY_THRESHOLDS.filter { percent <= it }
            val lowestUncovered = crossedThresholds.minOrNull()
            if (lowestUncovered != null && !alertedThresholds.contains(lowestUncovered)) {
                // Mark all crossed thresholds >= lowestUncovered as alerted for this discharge cycle
                alertedThresholds.addAll(crossedThresholds)
                pendingEvent = ProactiveAlertEvent(
                    type = ProactiveAlertType.BATTERY_LOW,
                    message = "Battery $percent% hai, charger lagaiye.",
                    batteryPercent = percent
                )
            }
        }

        pendingEvent?.let {
            Log.i(TAG, "Emitting proactive battery alert: ${it.message}")
            _alerts.tryEmit(it)
        }
    }

    /**
     * Checks if the device is currently on metered Mobile Data (cellular, not Wi-Fi).
     * We do NOT build full background traffic tracking (restricted by Android OS);
     * instead we politely suggest Wi-Fi ONLY when a data-heavy action triggers!
     *
     * Returns the warning string if a suggestion should be surfaced, or null otherwise.
     */
    fun checkDataSaverForHeavyAction(context: Context, actionLabel: String): String? {
        if (!alertsEnabled) return null
        if (!isOnMeteredMobileData(context)) return null

        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastDataWarningMs < DATA_SAVER_COOLDOWN_MS) return null
            lastDataWarningMs = now
        }

        val msg = "Aap mobile data pe hain ($actionLabel), Wi-Fi connect karna chahenge?"
        Log.i(TAG, "Data saver warning: $msg")
        return msg
    }

    fun isOnMeteredMobileData(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val onCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            hasInternet && onCellular && !onWifi && !unmetered
        } catch (e: Exception) {
            Log.w(TAG, "Network capability check failed: ${e.message}")
            false
        }
    }
}
