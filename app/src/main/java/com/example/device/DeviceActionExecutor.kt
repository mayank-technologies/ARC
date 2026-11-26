package com.example.device

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.remote.ExternalApis
import com.example.service.ArcAccessibilityService
import com.example.service.ArcDeviceAdminReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DeviceActionExecutor"
private const val ACTION_ARC_SMS_SENT = "com.example.arc.SMS_SENT"

sealed class ActionOutcome {
    data class Success(val message: String, val detail: String = "") : ActionOutcome()
    data class NeedsUserAction(val message: String, val options: List<String> = emptyList()) : ActionOutcome()
    data class Failure(val reason: String) : ActionOutcome()
}

data class ContactMatch(val displayName: String, val phoneNumber: String)

/**
 * Executes real Android device actions, intents, hardware toggles, and deep links.
 * Never fakes success for OS-restricted operations.
 */
class DeviceActionExecutor(
    private val context: Context,
    private val externalApis: ExternalApis
) {
    private var torchEnabled = false

    private data class AppTarget(
        val aliases: List<String>,
        val packageNames: List<String>,
        val fallbackUrl: String
    )

    private val appMap = listOf(
        AppTarget(listOf("whatsapp", "whats app", "wa"), listOf("com.whatsapp", "com.whatsapp.w4b"), "https://web.whatsapp.com"),
        AppTarget(listOf("youtube", "you tube", "yt"), listOf("com.google.android.youtube"), "https://www.youtube.com"),
        AppTarget(listOf("instagram", "insta"), listOf("com.instagram.android"), "https://www.instagram.com"),
        AppTarget(listOf("flipkart", "flipkart minutes"), listOf("com.flipkart.android"), "https://www.flipkart.com"),
        AppTarget(listOf("gmail", "mail", "google mail"), listOf("com.google.android.gm"), "https://mail.google.com"),
        AppTarget(listOf("facebook", "fb"), listOf("com.facebook.katana", "com.facebook.lite"), "https://www.facebook.com"),
        AppTarget(listOf("amazon", "amazon shopping"), listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"), "https://www.amazon.in"),
        AppTarget(listOf("spotify"), listOf("com.spotify.music"), "https://open.spotify.com"),
        AppTarget(listOf("maps", "google maps"), listOf("com.google.android.apps.maps"), "https://maps.google.com"),
        AppTarget(listOf("ola", "ola cabs"), listOf("com.olacabs.customer"), "https://book.olacabs.com"),
        AppTarget(listOf("uber"), listOf("com.ubercab"), "https://m.uber.com"),
        AppTarget(listOf("rapido"), listOf("com.rapido.passenger"), "https://www.rapido.bike"),
        AppTarget(listOf("chrome", "browser"), listOf("com.android.chrome"), "https://www.google.com")
    )

    fun openApp(rawName: String): ActionOutcome {
        val normalized = rawName.lowercase().replace("\\s+".toRegex(), " ").trim()
        val compact = normalized.replace(" ", "")

        val target = appMap.firstOrNull { entry ->
            entry.aliases.any { alias ->
                val aCompact = alias.replace(" ", "")
                normalized == alias || compact == aCompact ||
                    normalized.contains(alias) || alias.contains(normalized)
            }
        }

        if (target != null) {
            for (pkg in target.packageNames) {
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return ActionOutcome.Success("Opened $rawName app.")
                }
            }
            // App not installed -> open in external Chrome browser (never an in-app WebView)
            return openExternalUrl(target.fallbackUrl, "$rawName isn't installed, so I opened it in Chrome.")
        }

        // Fuzzy query against installed launchable packages (only visible packages via <queries> / launcher)
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = context.packageManager.queryIntentActivities(mainIntent, 0)
        val fuzzyMatch = resolved.firstOrNull { info ->
            val label = info.loadLabel(context.packageManager).toString().lowercase()
            label.contains(normalized) || label.replace(" ", "").contains(compact)
        }
        if (fuzzyMatch != null) {
            val pkg = fuzzyMatch.activityInfo.packageName
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return ActionOutcome.Success("Opened ${fuzzyMatch.loadLabel(context.packageManager)}.")
            }
        }

        val searchUrl = "https://www.google.com/search?q=${URLEncoder.encode(rawName, "UTF-8")}"
        return openExternalUrl(searchUrl, "Couldn't find an installed app for $rawName, so I searched in Chrome.")
    }

    fun openExternalUrl(url: String, customSuccess: String? = null): ActionOutcome {
        return try {
            val safeUrl = if (url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("spotify:") || url.startsWith("geo:") ||
                url.startsWith("olacabs:") || url.startsWith("uber:") || url.startsWith("rapido:")
            ) url else "https://$url"

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Prefer external Chrome browser for web URLs if available
            if (safeUrl.startsWith("http")) {
                val chromeIntent = Intent(intent).setPackage("com.android.chrome")
                if (chromeIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(chromeIntent)
                    return ActionOutcome.Success(customSuccess ?: "Opened in Chrome.")
                }
            }
            context.startActivity(intent)
            ActionOutcome.Success(customSuccess ?: "Opened link externally.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL $url", e)
            ActionOutcome.Failure("Unable to open link: ${e.localizedMessage}")
        }
    }

    fun makePhoneCall(phoneNumber: String): ActionOutcome {
        val cleaned = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleaned.isBlank()) return ActionOutcome.Failure("No valid phone number provided.")
        return try {
            val hasCallPerm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
            val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action, Uri.parse("tel:$cleaned")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            if (hasCallPerm) {
                ActionOutcome.Success("Calling $cleaned.")
            } else {
                ActionOutcome.NeedsUserAction("Opened dialer with $cleaned. Grant Phone permission for direct dialing.")
            }
        } catch (e: Exception) {
            ActionOutcome.Failure("Could not start call: ${e.localizedMessage}")
        }
    }

    fun callContactByName(spokenName: String): ActionOutcome {
        val hasContacts = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) {
            return ActionOutcome.Failure("Contacts permission is not granted yet.")
        }

        val matches = findMatchingContacts(spokenName)
        return when {
            matches.isEmpty() -> ActionOutcome.Failure("No contact matching '$spokenName' was found.")
            matches.size == 1 -> {
                val match = matches.first()
                makePhoneCall(match.phoneNumber).let { outcome ->
                    if (outcome is ActionOutcome.Success) {
                        ActionOutcome.Success("Calling ${match.displayName}.")
                    } else outcome
                }
            }
            else -> {
                val names = matches.take(5).map { "${it.displayName} (${it.phoneNumber})" }
                ActionOutcome.NeedsUserAction(
                    message = "I found ${matches.size} contacts matching $spokenName: ${names.joinToString(", ")}. Which one should I call?",
                    options = names
                )
            }
        }
    }

    private fun findMatchingContacts(query: String): List<ContactMatch> {
        val q = query.lowercase().trim()
        val matches = linkedMapOf<String, ContactMatch>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    val lower = name.lowercase()
                    if (lower == q || lower.contains(q) || q.split(" ").all { part -> part.isNotBlank() && lower.contains(part) }) {
                        val key = "${name.lowercase()}_${number.filter { it.isDigit() }.takeLast(10)}"
                        matches.putIfAbsent(key, ContactMatch(name, number))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact query failed", e)
        }
        return matches.values.toList()
    }

    /**
     * Resolves a raw incoming SMS phone number to a saved Contact display name if READ_CONTACTS is granted.
     */
    private fun lookupContactNameByNumber(phoneNumber: String): String {
        val hasContacts = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts || phoneNumber.isBlank()) return phoneNumber
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: phoneNumber else phoneNumber
            } ?: phoneNumber
        } catch (_: Exception) {
            phoneNumber
        }
    }

    /**
     * Checks if an SMS looks sensitive (OTP, verification code, PIN, banking password).
     * ARC may read it aloud when asked, but NEVER takes any automated action (no auto-fill/click/forward).
     */
    private fun containsSensitiveOtpOrPin(body: String): Boolean {
        val lower = body.lowercase()
        val sensitiveWords = listOf("otp", "one time password", "verification code", "mpin", "pin", "cvv", "passcode", "security code")
        if (sensitiveWords.any { lower.contains(it) }) return true
        return Regex("\\b\\d{4,8}\\b").containsMatchIn(body) &&
            (lower.contains("code") || lower.contains("verify") || lower.contains("login"))
    }

    /**
     * Two-Way SMS (Read): Queries Android Telephony SMS Inbox for recent or unread messages.
     */
    fun readRecentSms(limit: Int = 5, unreadOnly: Boolean = false): ActionOutcome {
        val hasReadSms = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            return ActionOutcome.Failure("SMS read permission is not granted yet. Please allow SMS permission in Onboarding or Settings.")
        }

        val cappedLimit = limit.coerceIn(1, 10)
        val selection = if (unreadOnly) "${Telephony.Sms.READ} = 0" else null
        val entries = mutableListOf<String>()
        var anySensitive = false
        val fmt = SimpleDateFormat("dd MMM h:mm a", Locale.getDefault())

        return try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ
                ),
                selection,
                null,
                Telephony.Sms.Inbox.DEFAULT_SORT_ORDER
            )?.use { cursor ->
                while (cursor.moveToNext() && entries.size < cappedLimit) {
                    val address = cursor.getString(0).orEmpty().ifBlank { "Unknown" }
                    val body = cursor.getString(1).orEmpty().trim()
                    val dateMs = cursor.getLong(2)
                    val isRead = cursor.getInt(3) == 1

                    val senderLabel = lookupContactNameByNumber(address)
                    val isSensitive = containsSensitiveOtpOrPin(body)
                    if (isSensitive) anySensitive = true

                    val statusTag = if (isRead) "Read" else "Unread"
                    val timeLabel = if (dateMs > 0L) fmt.format(Date(dateMs)) else ""
                    val preview = body.take(220)
                    val note = if (isSensitive) " [SENSITIVE OTP/PIN — read only, NEVER auto-fill or take action]" else ""
                    entries.add("${entries.size + 1}. From $senderLabel ($statusTag, $timeLabel): \"$preview\"$note")
                }
            }

            if (entries.isEmpty()) {
                val emptyMsg = if (unreadOnly) "Aapke koi unread SMS nahi hain." else "Inbox me koi recent SMS nahi mila."
                ActionOutcome.Success(emptyMsg)
            } else {
                val safetyNote = if (anySensitive) {
                    " Note: One or more messages look sensitive (OTP/PIN) — ARC will read them if asked, without taking any automated action."
                } else ""
                ActionOutcome.Success(
                    message = "Found ${entries.size} ${if (unreadOnly) "unread " else ""}SMS messages.$safetyNote",
                    detail = entries.joinToString("\n")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read SMS inbox", e)
            ActionOutcome.Failure("SMS read failed: ${e.localizedMessage ?: "unable to access inbox"}")
        }
    }

    /**
     * Two-Way SMS (Send): Matches contact name using existing fuzzy contact matcher (or direct phone number),
     * dispatches via SmsManager, and awaits real carrier PendingIntent confirmation so failures are never hidden.
     */
    suspend fun sendSmsToContactOrNumber(contactOrNumber: String, message: String): ActionOutcome {
        val target = contactOrNumber.trim()
        val body = message.trim()
        if (target.isBlank()) {
            return ActionOutcome.Failure("Kisko SMS bhejna hai? Please tell me the contact name or number.")
        }
        if (body.isBlank()) {
            return ActionOutcome.Failure("SMS me kya message likhna hai? Message khali nahi ho sakta.")
        }

        val hasSendSms = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasSendSms) {
            return ActionOutcome.Failure("SEND_SMS permission is not granted yet. SMS was NOT sent — please allow SMS permission first.")
        }

        // Resolve contact name -> phone number via existing fuzzy-match flow unless user gave digits directly
        val rawDigits = target.filter { it.isDigit() || it == '+' }
        val looksLikePhoneNumber = target.none { it.isLetter() } && rawDigits.count { it.isDigit() } >= 7

        val (displayName, phoneNumber) = if (looksLikePhoneNumber) {
            target to rawDigits
        } else {
            val hasContacts = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasContacts) {
                return ActionOutcome.Failure("Contacts permission is not granted yet, so I cannot look up '$target'. SMS was NOT sent.")
            }

            val matches = findMatchingContacts(target)
            when {
                // Reuse existing "contact not found" error flow cleanly
                matches.isEmpty() -> return ActionOutcome.Failure("No contact matching '$target' was found.")
                matches.size > 1 -> {
                    val names = matches.take(5).map { "${it.displayName} (${it.phoneNumber})" }
                    return ActionOutcome.NeedsUserAction(
                        message = "I found ${matches.size} contacts matching $target: ${names.joinToString(", ")}. Which one should I send the SMS to?",
                        options = names
                    )
                }
                else -> matches.first().let { it.displayName to it.phoneNumber }
            }
        }

        val cleanedNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanedNumber.isBlank()) {
            return ActionOutcome.Failure("Invalid phone number for $displayName. SMS was NOT sent.")
        }

        val sentDeferred = CompletableDeferred<Int>()
        val actionName = "${ACTION_ARC_SMS_SENT}_${System.currentTimeMillis()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!sentDeferred.isCompleted) {
                    sentDeferred.complete(resultCode)
                }
            }
        }

        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(actionName),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val sentPi = PendingIntent.getBroadcast(
                context,
                (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
                Intent(actionName).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                val sentPis = ArrayList<PendingIntent>(parts.size).apply {
                    add(sentPi)
                    repeat(parts.size - 1) { add(sentPi) }
                }
                smsManager.sendMultipartTextMessage(cleanedNumber, null, parts, sentPis, null)
            } else {
                smsManager.sendTextMessage(cleanedNumber, null, body, sentPi, null)
            }

            // Wait up to 8 seconds for actual carrier/radio status instead of assuming success
            val code = withTimeoutOrNull(8000L) { sentDeferred.await() }
            when (code) {
                Activity.RESULT_OK -> ActionOutcome.Success("SMS sent to $displayName ($cleanedNumber).")
                SmsManager.RESULT_ERROR_NO_SERVICE -> ActionOutcome.Failure("SMS send failed for $displayName: No cellular service.")
                SmsManager.RESULT_ERROR_RADIO_OFF -> ActionOutcome.Failure("SMS send failed for $displayName: Mobile radio / Airplane mode is off.")
                SmsManager.RESULT_ERROR_NULL_PDU -> ActionOutcome.Failure("SMS send failed for $displayName: Message encoding error.")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> ActionOutcome.Failure("SMS send failed for $displayName: Carrier/SIM rejected or no balance.")
                null -> ActionOutcome.Failure("SMS delivery status timed out — message may not have been sent to $displayName. Please verify in your Messages app.")
                else -> ActionOutcome.Failure("SMS send failed for $displayName (error code $code). Message was NOT sent.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS send exception", e)
            ActionOutcome.Failure("SMS send failed: ${e.localizedMessage ?: "device error"}. Message was NOT sent.")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun openWhatsAppOrSendMessage(contactOrPhone: String?, message: String?): ActionOutcome {
        // NOTE: Meta blocks third-party automated WhatsApp voice/video call dialing.
        // For messages, we use official wa.me prefilled deep links; for calls we open chat and prompt the user.
        if (contactOrPhone.isNullOrBlank() && message.isNullOrBlank()) {
            return openApp("WhatsApp")
        }
        val digits = contactOrPhone?.filter { it.isDigit() } ?: ""
        val phoneParam = if (digits.length >= 10) digits else ""
        val encodedText = URLEncoder.encode(message.orEmpty(), "UTF-8")
        val url = if (phoneParam.isNotBlank()) {
            "https://wa.me/$phoneParam?text=$encodedText"
        } else {
            "https://api.whatsapp.com/send?text=$encodedText"
        }
        return openExternalUrl(url, "Opened WhatsApp with your prefilled message ready to send.")
    }

    fun openWhatsAppCallScreen(contactName: String?): ActionOutcome {
        // Platform restriction: WhatsApp does NOT allow third-party apps to auto-dial calls.
        val openOutcome = openApp("WhatsApp")
        return if (openOutcome is ActionOutcome.Success) {
            ActionOutcome.NeedsUserAction(
                "Opened WhatsApp${if (!contactName.isNullOrBlank()) " for $contactName" else ""}. WhatsApp blocks third-party auto-dialing, so please tap the call button yourself."
            )
        } else openOutcome
    }

    fun openMapsRoute(destination: String, origin: String? = null, travelMode: String = "driving"): ActionOutcome {
        val destEnc = URLEncoder.encode(destination, "UTF-8")
        val origPart = if (!origin.isNullOrBlank()) "&origin=${URLEncoder.encode(origin, "UTF-8")}" else ""
        val url = "https://www.google.com/maps/dir/?api=1$origPart&destination=$destEnc&travelmode=$travelMode"
        return openExternalUrl(url, "Opened Google Maps route to $destination.")
    }

    suspend fun assistRideBooking(
        provider: String,
        destination: String,
        vehicleType: String?
    ): ActionOutcome {
        if (vehicleType.isNullOrBlank()) {
            return ActionOutcome.NeedsUserAction(
                "Would you like a bike, a 4-seater car, or a 6-seater cab to $destination?",
                listOf("Bike", "4-seater car", "6-seater car")
            )
        }
        val pickup = externalApis.resolveLiveLocation()
        val dropEnc = URLEncoder.encode(destination, "UTF-8")
        val pickupEnc = URLEncoder.encode(pickup.fullAddress, "UTF-8")

        val lowerProvider = provider.lowercase()
        val deepLink = when {
            lowerProvider.contains("uber") ->
                "https://m.uber.com/ul/?action=setPickup&pickup[latitude]=${pickup.latitude}&pickup[longitude]=${pickup.longitude}&pickup[nickname]=$pickupEnc&dropoff[formatted_address]=$dropEnc"
            lowerProvider.contains("ola") ->
                "https://book.olacabs.com/?lat=${pickup.latitude}&lng=${pickup.longitude}&drop_name=$dropEnc"
            lowerProvider.contains("rapido") ->
                "https://www.rapido.bike/"
            else ->
                "https://m.uber.com/ul/?action=setPickup&pickup[latitude]=${pickup.latitude}&pickup[longitude]=${pickup.longitude}&dropoff[formatted_address]=$dropEnc"
        }

        // Open native app or external fallback; never claim ride was booked (user confirms final tap).
        openExternalUrl(deepLink)
        return ActionOutcome.NeedsUserAction(
            "Prepared $provider ($vehicleType) from ${pickup.city} to $destination. Please review fare and tap Book in the app."
        )
    }

    fun assistShopping(app: String, query: String): ActionOutcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val lower = app.lowercase()
        val url = when {
            lower.contains("amazon") -> "https://www.amazon.in/s?k=$encoded"
            else -> "https://www.flipkart.com/search?q=$encoded"
        }
        openExternalUrl(url)
        return ActionOutcome.NeedsUserAction(
            "Opened $app search for '$query'. Automated checkout is not permitted, so please complete payment yourself."
        )
    }

    fun toggleFlashlight(enable: Boolean? = null): ActionOutcome {
        return try {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camMgr.cameraIdList.firstOrNull { id ->
                camMgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionOutcome.Failure("No camera flash available on this device.")
            val next = enable ?: !torchEnabled
            camMgr.setTorchMode(cameraId, next)
            torchEnabled = next
            ActionOutcome.Success(if (next) "Flashlight turned on." else "Flashlight turned off.")
        } catch (e: Exception) {
            ActionOutcome.Failure("Flashlight control failed: ${e.localizedMessage}")
        }
    }

    fun toggleWifi(): ActionOutcome {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
                ActionOutcome.NeedsUserAction("Opened Wi-Fi quick panel (Android restricts silent background Wi-Fi toggles).")
            } else {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val next = !wm.isWifiEnabled
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = next
                ActionOutcome.Success(if (next) "Wi-Fi enabled." else "Wi-Fi disabled.")
            }
        } catch (e: Exception) {
            ActionOutcome.Failure("Unable to open Wi-Fi controls: ${e.localizedMessage}")
        }
    }

    fun openBluetoothSettings(): ActionOutcome {
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val state = if (btMgr.adapter?.isEnabled == true) "currently ON" else "currently OFF"
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ActionOutcome.NeedsUserAction("Bluetooth is $state. Opened Bluetooth settings for you.")
    }

    fun adjustVolume(direction: String): ActionOutcome {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flag = AudioManager.FLAG_SHOW_UI
        when (direction.lowercase()) {
            "up", "increase", "louder" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flag)
            "down", "decrease", "quieter" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flag)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flag)
            else -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, flag)
        }
        val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return ActionOutcome.Success("Media volume adjusted to $level out of $max.")
    }

    fun adjustBrightness(levelPercent: Int?): ActionOutcome {
        return try {
            if (Settings.System.canWrite(context)) {
                val pct = (levelPercent ?: 70).coerceIn(5, 100)
                val value = (pct * 255) / 100
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
                ActionOutcome.Success("Screen brightness set to $pct%.")
            } else {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionOutcome.NeedsUserAction("Opened Display settings because Write System Settings permission isn't granted.")
            }
        } catch (e: Exception) {
            ActionOutcome.Failure("Brightness control failed: ${e.localizedMessage}")
        }
    }

    fun readBatteryPercentage(): ActionOutcome {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return ActionOutcome.Success(
            "Battery is at $level%${if (charging) " and currently charging" else ""}."
        )
    }

    fun lockScreen(): ActionOutcome {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, ArcDeviceAdminReceiver::class.java)
        return if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            ActionOutcome.Success("Screen locked.")
        } else {
            ActionOutcome.NeedsUserAction(
                "Device Admin permission isn't enabled yet. Enable ARC Device Admin in Settings to lock the screen by voice."
            )
        }
    }

    fun readClipboardOnCommand(): ActionOutcome {
        // Privacy guarantee: clipboard is read ONLY upon explicit user voice/tap command.
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (text.isNullOrBlank()) {
            return ActionOutcome.Failure("Your clipboard is empty right now.")
        }
        val suggestion = when {
            text.startsWith("http://") || text.startsWith("https://") ->
                "It looks like a web link — want me to open it in Chrome?"
            text.contains(",") || text.contains("road", true) || text.contains("street", true) || text.contains("sector", true) ->
                "It looks like an address — want me to open directions in Google Maps?"
            text.count { it.isDigit() } in 10..13 ->
                "It looks like a phone number — want me to call or WhatsApp it?"
            else -> "Would you like me to translate, summarize, or search this?"
        }
        return ActionOutcome.Success("Clipboard says: \"$text\". $suggestion", text)
    }

    fun closeAppsBestEffort(): ActionOutcome {
        // OS limitation: Android does not permit third-party apps to force-close other apps' processes.
        val service = ArcAccessibilityService.instance
        return if (service != null) {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionOutcome.NeedsUserAction(
                "Android restricts third-party apps from force-closing background apps directly. I opened Recent Apps so you can swipe or tap Clear All."
            )
        } else {
            val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionOutcome.NeedsUserAction(
                "Android doesn't allow force-closing other apps automatically. Opened App Settings for manual control."
            )
        }
    }
}
