package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class CapturedNotification(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val timestampMillis: Long
)

/**
 * Captures posted notifications in memory so ARC can read WhatsApp or any
 * user-enabled app's notifications aloud when requested.
 */
class ArcNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg.substringAfterLast("."))

        recentNotifications.add(
            0,
            CapturedNotification(
                packageName = pkg,
                appLabel = appLabel,
                title = title,
                body = text,
                timestampMillis = sbn.postTime
            )
        )
        while (recentNotifications.size > 40) {
            recentNotifications.removeAt(recentNotifications.lastIndex)
        }
    }

    companion object {
        @Volatile
        var instance: ArcNotificationListenerService? = null
            private set

        private val recentNotifications = CopyOnWriteArrayList<CapturedNotification>()

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, ArcNotificationListenerService::class.java).flattenToString()
            return flat.contains(cn)
        }

        fun getFilteredNotifications(enabledPackages: Set<String>, packageFilter: String? = null): List<CapturedNotification> {
            return recentNotifications.filter { item ->
                val allowed = enabledPackages.isEmpty() || enabledPackages.contains(item.packageName)
                val matchesFilter = packageFilter.isNullOrBlank() ||
                    item.packageName.contains(packageFilter, ignoreCase = true) ||
                    item.appLabel.contains(packageFilter, ignoreCase = true)
                allowed && matchesFilter
            }
        }
    }
}
