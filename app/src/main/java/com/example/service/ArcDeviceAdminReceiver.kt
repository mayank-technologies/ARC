package com.example.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Minimal Device Admin receiver requesting only force-lock (lockNow()) scope.
 */
class ArcDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("ArcDeviceAdmin", "ARC Device Admin enabled (force-lock only).")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("ArcDeviceAdmin", "ARC Device Admin disabled.")
    }
}
