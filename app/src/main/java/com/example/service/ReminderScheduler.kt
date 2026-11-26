package com.example.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.ArcDatabase
import com.example.data.local.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private const val REMINDER_CHANNEL_ID = "arc_reminders_channel"
private const val EXTRA_REMINDER_ID = "extra_reminder_id"
private const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
private const val EXTRA_REMINDER_NOTES = "extra_reminder_notes"

object ReminderScheduler {

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(REMINDER_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    context.getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "ARC reminders, recurring alarms, and scheduled alerts"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun schedule(context: Context, reminder: ReminderEntity) {
        if (!reminder.isEnabled) {
            cancel(context, reminder.id)
            return
        }
        ensureNotificationChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextTrigger = computeNextTriggerMillis(reminder)
        if (nextTrigger <= System.currentTimeMillis() - 60_000L && reminder.recurrence == "NONE") {
            return
        }

        val intent = Intent(context, ArcReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TITLE, reminder.title)
            putExtra(EXTRA_REMINDER_NOTES, reminder.notes)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi)
            }
        } catch (e: SecurityException) {
            Log.w("ReminderScheduler", "Falling back to inexact alarm: ${e.message}")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi)
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ArcReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
    }

    fun computeNextTriggerMillis(reminder: ReminderEntity, nowMillis: Long = System.currentTimeMillis()): Long {
        if (reminder.recurrence == "NONE") {
            return reminder.triggerAtMillis
        }
        val zone = ZoneId.systemDefault()
        val baseTime = Instant.ofEpochMilli(reminder.triggerAtMillis).atZone(zone).toLocalTime()
        var candidate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            .withHour(baseTime.hour)
            .withMinute(baseTime.minute)
            .withSecond(0)
            .withNano(0)
        if (candidate.toInstant().toEpochMilli() <= nowMillis) {
            candidate = candidate.plusDays(1)
        }
        return when (reminder.recurrence) {
            "DAILY" -> candidate.toInstant().toEpochMilli()
            "WEEKDAYS" -> {
                while (candidate.dayOfWeek == DayOfWeek.SATURDAY || candidate.dayOfWeek == DayOfWeek.SUNDAY) {
                    candidate = candidate.plusDays(1)
                }
                candidate.toInstant().toEpochMilli()
            }
            "WEEKLY" -> {
                val allowedDays = reminder.weekdaysCsv
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .toSet()
                if (allowedDays.isEmpty()) {
                    candidate.toInstant().toEpochMilli()
                } else {
                    var safety = 0
                    while (!allowedDays.contains(candidate.dayOfWeek.value) && safety < 8) {
                        candidate = candidate.plusDays(1)
                        safety++
                    }
                    candidate.toInstant().toEpochMilli()
                }
            }
            else -> candidate.toInstant().toEpochMilli()
        }
    }
}

class ArcReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: "ARC Reminder"
        val notes = intent.getStringExtra(EXTRA_REMINDER_NOTES) ?: ""

        ReminderScheduler.ensureNotificationChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            id.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ARC: $title")
            .setContentText(notes.ifBlank { "Scheduled reminder" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
        nm.notify((10000 + id).toInt(), notification)

        // Reschedule if recurring
        CoroutineScope(Dispatchers.IO).launch {
            val all = ArcDatabase.getInstance(context).reminderDao().getAllEnabled()
            val match = all.firstOrNull { it.id == id }
            if (match != null && match.recurrence != "NONE") {
                ReminderScheduler.schedule(context, match)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CoroutineScope(Dispatchers.IO).launch {
                val reminders = ArcDatabase.getInstance(context).reminderDao().getAllEnabled()
                reminders.forEach { ReminderScheduler.schedule(context, it) }
            }
        }
    }
}
