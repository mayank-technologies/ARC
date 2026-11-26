package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.prefs.PersonalInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "ArcAccessibility"

/**
 * Unified Accessibility Service powering:
 * 1) Reading current screen content (Accessibility tree + ML Kit OCR fallback)
 * 2) Auto-tapping named UI elements & YouTube "Skip Ad" button via dispatchGesture()
 * 3) Scrolling / pausing videos or reels
 * 4) Explicit-command forms auto-fill (skipping ambiguous fields)
 */
class ArcAccessibilityService : AccessibilityService() {

    private val ocrExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "ARC Accessibility Service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive inspection happens only when explicitly commanded by the user.
    }

    override fun onInterrupt() {
        Log.w(TAG, "ARC Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        instance = null
        ocrExecutor.shutdown()
        super.onDestroy()
    }

    suspend fun extractCurrentScreenText(): String {
        val root = rootInActiveWindow
        val collected = mutableListOf<String>()
        if (root != null) {
            collectVisibleText(root, collected)
        }
        val treeText = collected.distinct().joinToString("\n").trim()
        if (treeText.length >= 25) {
            return treeText
        }

        // Fallback: ML Kit OCR on screenshot (Android 11+ / API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ocrText = captureAndRunOcr()
            if (ocrText.isNotBlank()) return ocrText
        }
        return treeText.ifBlank { "No readable text found on the current screen." }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val txt = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!txt.isNullOrBlank()) out.add(txt)
        else if (!desc.isNullOrBlank()) out.add(desc)
        for (i in 0 until node.childCount) {
            collectVisibleText(node.getChild(i), out)
        }
    }

    private suspend fun captureAndRunOcr(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val bitmap: Bitmap = suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ocrExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        hw?.recycle()
                        if (copy != null) cont.resume(copy) else cont.resume(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot for OCR failed: $errorCode")
                        cont.resume(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }
                }
            )
        }
        if (bitmap.width <= 1) return ""

        return suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    bitmap.recycle()
                    recognizer.close()
                    cont.resume(result.text.trim())
                }
                .addOnFailureListener {
                    bitmap.recycle()
                    recognizer.close()
                    cont.resume("")
                }
        }
    }

    fun trySkipYouTubeAd(): Pair<Boolean, String> {
        val root = rootInActiveWindow
            ?: return false to "Could not inspect the screen right now."
        val target = findNodeMatching(root) { node ->
            val txt = node.text?.toString()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            (txt.contains("skip ad") || txt == "skip" || desc.contains("skip ad") || viewId.contains("skip_ad_button")) &&
                node.isVisibleToUser
        }
        if (target != null) {
            val tapped = tapNodeOrBounds(target)
            return if (tapped) {
                true to "Skipped the YouTube ad."
            } else {
                false to "Found Skip Ad button, but tap failed."
            }
        }
        val countdownPresent = findNodeMatching(root) { node ->
            val txt = node.text?.toString()?.lowercase().orEmpty()
            txt.contains("ad") && (txt.contains("sec") || txt.contains("ends in"))
        } != null
        return if (countdownPresent) {
            false to "The YouTube ad is not skippable yet."
        } else {
            false to "No Skip Ad button is currently visible on screen."
        }
    }

    fun tapElementByName(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = query.lowercase().trim()
        val match = findNodeMatching(root) { node ->
            val txt = node.text?.toString()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            (txt.contains(q) || desc.contains(q)) && node.isVisibleToUser
        } ?: return false
        return tapNodeOrBounds(match)
    }

    fun scrollMedia(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val h = metrics.heightPixels.toFloat()
        val (startY, endY) = when (direction.lowercase()) {
            "down", "next", "up_swipe" -> h * 0.75f to h * 0.25f
            "up", "previous", "down_swipe" -> h * 0.25f to h * 0.75f
            else -> h * 0.72f to h * 0.28f
        }
        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun toggleCenterPlayPause(): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.42f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun autoFillCurrentForm(info: PersonalInfo): Pair<Int, Int> {
        val root = rootInActiveWindow ?: return 0 to 0
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, editableNodes)
        var filled = 0
        var skipped = 0

        for (node in editableNodes) {
            val hint = buildString {
                append(node.hintText?.toString().orEmpty()).append(' ')
                append(node.text?.toString().orEmpty()).append(' ')
                append(node.contentDescription?.toString().orEmpty()).append(' ')
                append(node.viewIdResourceName.orEmpty())
            }.lowercase()

            val valueToFill: String? = when {
                (hint.contains("email") || hint.contains("e-mail")) && info.email.isNotBlank() -> info.email
                (hint.contains("phone") || hint.contains("mobile") || hint.contains("tel")) && info.phone.isNotBlank() -> info.phone
                (hint.contains("city") || hint.contains("town")) && info.city.isNotBlank() -> info.city
                (hint.contains("address") || hint.contains("street")) && info.address.isNotBlank() -> info.address
                (hint.contains("full_name") || hint.contains("fullname") || hint.contains("name")) &&
                    !hint.contains("user") && info.fullName.isNotBlank() -> info.fullName
                else -> null // Ambiguous or unmatched field -> skip rather than guessing!
            }

            if (valueToFill != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, valueToFill)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    filled++
                } else {
                    skipped++
                }
            } else {
                skipped++
            }
        }
        return filled to skipped
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable && node.isVisibleToUser) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectEditableNodes(node.getChild(i), out)
        }
    }

    private fun findNodeMatching(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val found = findNodeMatching(node.getChild(i), predicate)
            if (found != null) return found
        }
        return null
    }

    private fun tapNodeOrBounds(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = Path().apply {
            moveTo(rect.exactCenterX(), rect.exactCenterY())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        @Volatile
        var instance: ArcAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${ArcAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val component = splitter.next()
                if (component.equals(expected, ignoreCase = true) ||
                    component.endsWith("/.service.ArcAccessibilityService", ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }
    }
}
