package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG = "ArcEdgeOverlay"

/**
 * Manages SYSTEM_ALERT_WINDOW overlays:
 * 1) Software cyan/electric-blue edge glow while ARC is listening or speaking.
 * 2) WhatsApp-style floating "Stop Sharing" pill during MediaProjection screen share.
 */
class ArcEdgeOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var edgeGlowView: EdgeGlowView? = null
    private var stopSharePillView: View? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun showEdgeGlow(isSpeaking: Boolean) {
        if (!canDrawOverlays()) return
        mainHandler.post {
            try {
                if (edgeGlowView == null) {
                    val view = EdgeGlowView(context)
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    )
                    windowManager.addView(view, params)
                    edgeGlowView = view
                }
                edgeGlowView?.setActiveMode(isSpeaking)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to show edge glow overlay: ${e.message}")
            }
        }
    }

    fun hideEdgeGlow() {
        mainHandler.post {
            edgeGlowView?.let { view ->
                runCatching { windowManager.removeViewImmediate(view) }
            }
            edgeGlowView = null
        }
    }

    fun showStopSharingPill(onStopClicked: () -> Unit) {
        if (!canDrawOverlays()) return
        mainHandler.post {
            try {
                hideStopSharingPillInternal()
                val pill = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padH = (18 * resources.displayMetrics.density).toInt()
                    val padV = (10 * resources.displayMetrics.density).toInt()
                    setPadding(padH, padV, padH, padV)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 60f * resources.displayMetrics.density
                        setColor(Color.parseColor("#E6FF3B5C"))
                        setStroke(2, Color.parseColor("#80FFFFFF"))
                    }
                    elevation = 12f
                    val label = TextView(context).apply {
                        text = "● ARC Sharing Screen  |  Stop Sharing"
                        setTextColor(Color.WHITE)
                        textSize = 13f
                    }
                    addView(label)
                    setOnClickListener {
                        onStopClicked()
                        hideStopSharingPill()
                    }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (56 * context.resources.displayMetrics.density).toInt()
                }
                windowManager.addView(pill, params)
                stopSharePillView = pill
            } catch (e: Exception) {
                Log.w(TAG, "Unable to show stop-sharing pill: ${e.message}")
            }
        }
    }

    fun hideStopSharingPill() {
        mainHandler.post { hideStopSharingPillInternal() }
    }

    private fun hideStopSharingPillInternal() {
        stopSharePillView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        stopSharePillView = null
    }

    private class EdgeGlowView(context: Context) : View(context) {
        private var speaking = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        fun setActiveMode(isSpeaking: Boolean) {
            speaking = isSpeaking
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = 14f * resources.displayMetrics.density
            paint.strokeWidth = stroke
            val primary = if (speaking) Color.parseColor("#8800E5FF") else Color.parseColor("#662979FF")
            val secondary = if (speaking) Color.parseColor("#881DE9B6") else Color.parseColor("#6600E5FF")
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(primary, secondary, primary),
                null,
                Shader.TileMode.CLAMP
            )
            val inset = stroke / 2f
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, 48f, 48f, paint)
        }
    }
}
