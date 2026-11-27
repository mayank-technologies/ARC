package com.example.ai

import com.example.core.ContextDetector
import com.example.core.JarvisSystemFeatures
import com.example.core.ProactiveAlertMonitor
import com.example.data.local.ArcLocalRepository
import com.example.data.local.ReminderEntity
import com.example.data.prefs.ArcPreferences
import com.example.data.remote.ExternalApis
import com.example.device.ActionOutcome
import com.example.device.DeviceActionExecutor
import com.example.service.ArcAccessibilityService
import com.example.service.ArcNotificationListenerService
import com.example.service.ReminderScheduler
import android.content.Context
import android.util.Log
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "ArcToolRegistry"

data class ToolUiSideEffect(
    val toolName: String,
    val summary: String,
    val openGalleryDate: String? = null,
    val requestScreenShare: Boolean = false,
    val setOnlineState: Boolean? = null,
    val setArmedState: Boolean? = null,
    val showDiagnosticsHud: Boolean = false,
    val setMicActiveState: Boolean? = null
)

/**
 * Declares safe predefined Gemini Live FunctionDeclarations and executes them against
 * native Android controllers, external APIs, Room, and Accessibility services.
 */
class ArcToolRegistry(
    private val context: Context,
    private val prefs: ArcPreferences,
    private val repository: ArcLocalRepository,
    private val externalApis: ExternalApis,
    private val deviceExecutor: DeviceActionExecutor,
    private val claudeClient: ClaudeReasoningClient,
    private val onSideEffect: (ToolUiSideEffect) -> Unit
) {

    fun buildTools(): List<Tool> = listOf(
        Tool.functionDeclarations(
            listOf(
                FunctionDeclaration(
                    name = "openApp",
                    description = "Open an installed Android app (WhatsApp, YouTube, Instagram, Flipkart, Gmail, Facebook, Amazon, Spotify, Maps, etc.) or open its web equivalent in external Chrome if not installed.",
                    parameters = mapOf(
                        "appName" to Schema.string("Name of the application to open")
                    )
                ),
                FunctionDeclaration(
                    name = "openUrl",
                    description = "Open a website URL in the external Chrome browser.",
                    parameters = mapOf("url" to Schema.string("Full https URL to open"))
                ),
                FunctionDeclaration(
                    name = "makeCall",
                    description = "Dial a specific phone number.",
                    parameters = mapOf("phoneNumber" to Schema.string("Phone number digits"))
                ),
                FunctionDeclaration(
                    name = "callContact",
                    description = "Find a contact by spoken name and call them if a single clear match exists.",
                    parameters = mapOf("contactName" to Schema.string("Contact name to call"))
                ),
                FunctionDeclaration(
                    name = "openWhatsApp",
                    description = "Open WhatsApp or prepare a prefilled WhatsApp message.",
                    parameters = mapOf(
                        "contactOrPhone" to Schema.string("Optional phone number or contact name"),
                        "message" to Schema.string("Optional message text")
                    ),
                    optionalParameters = listOf("contactOrPhone", "message")
                ),
                FunctionDeclaration(
                    name = "whatsAppCallPrompt",
                    description = "Open WhatsApp screen for calling (Meta blocks automated third-party WhatsApp dialing).",
                    parameters = mapOf("contactName" to Schema.string("Contact name")),
                    optionalParameters = listOf("contactName")
                ),
                FunctionDeclaration(
                    name = "readNotifications",
                    description = "Read aloud recent notifications from WhatsApp or other user-enabled apps.",
                    parameters = mapOf("appFilter" to Schema.string("Optional app name filter like WhatsApp or Gmail")),
                    optionalParameters = listOf("appFilter")
                ),
                FunctionDeclaration(
                    name = "readSmsMessages",
                    description = "Read recent or unread SMS text messages from the phone inbox (sender + message preview). Never auto-submit OTPs.",
                    parameters = mapOf(
                        "limit" to Schema.integer("Max SMS messages to read (default 5)"),
                        "unreadOnly" to Schema.boolean("True to fetch only unread SMS messages")
                    ),
                    optionalParameters = listOf("limit", "unreadOnly")
                ),
                FunctionDeclaration(
                    name = "sendSmsMessage",
                    description = "Send a real SMS text message to a contact name or phone number.",
                    parameters = mapOf(
                        "recipient" to Schema.string("Contact name or phone number"),
                        "message" to Schema.string("SMS message body to send")
                    )
                ),
                FunctionDeclaration(
                    name = "controlHardware",
                    description = "Control device hardware: flashlight (on/off), wifi, bluetooth, volume (up/down/mute), brightness (0-100), or read battery.",
                    parameters = mapOf(
                        "target" to Schema.string("flashlight, wifi, bluetooth, volume, brightness, or battery"),
                        "action" to Schema.string("on, off, toggle, up, down, mute, status, or numeric level")
                    )
                ),
                FunctionDeclaration(
                    name = "lockScreen",
                    description = "Lock the device screen immediately using Device Admin force-lock.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "getWeather",
                    description = "Fetch live local weather using device GPS and reverse-geocoded city.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "getNews",
                    description = "Fetch top current news headlines.",
                    parameters = mapOf("topic" to Schema.string("Optional topic or region")),
                    optionalParameters = listOf("topic")
                ),
                FunctionDeclaration(
                    name = "getCricketScores",
                    description = "Fetch live cricket match scores.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "searchYouTubeAndPlay",
                    description = "Search YouTube and directly open the top playable video URL if play is requested.",
                    parameters = mapOf(
                        "query" to Schema.string("Search or song/video query"),
                        "playTopResult" to Schema.boolean("True to fetch and open the top direct video URL")
                    )
                ),
                FunctionDeclaration(
                    name = "playMoodMusic",
                    description = "Play mood-matched music on Spotify or YouTube.",
                    parameters = mapOf(
                        "moodOrTrack" to Schema.string("Mood, genre, or song name"),
                        "platform" to Schema.string("spotify or youtube")
                    )
                ),
                FunctionDeclaration(
                    name = "getMapsRoute",
                    description = "Open Google Maps directions route externally.",
                    parameters = mapOf(
                        "destination" to Schema.string("Destination place or address"),
                        "origin" to Schema.string("Optional origin place"),
                        "travelMode" to Schema.string("driving, walking, bicycling, or transit")
                    ),
                    optionalParameters = listOf("origin", "travelMode")
                ),
                FunctionDeclaration(
                    name = "assistRideBooking",
                    description = "Prepare Ola, Uber, or Rapido deep link with GPS pickup and drop; asks bike vs 4/6-seater car if omitted.",
                    parameters = mapOf(
                        "provider" to Schema.string("ola, uber, or rapido"),
                        "destination" to Schema.string("Drop location"),
                        "vehicleType" to Schema.string("bike, 4-seater car, or 6-seater car")
                    ),
                    optionalParameters = listOf("vehicleType")
                ),
                FunctionDeclaration(
                    name = "assistShopping",
                    description = "Open Flipkart/Flipkart Minutes or Amazon with a search query (user completes checkout).",
                    parameters = mapOf(
                        "app" to Schema.string("flipkart or amazon"),
                        "query" to Schema.string("Product to search")
                    )
                ),
                FunctionDeclaration(
                    name = "checkClipboard",
                    description = "Read current clipboard text on explicit user command and suggest a relevant action.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "readScreenContent",
                    description = "Read text currently visible on screen via Accessibility Service + OCR fallback.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "skipVideoAd",
                    description = "Tap the YouTube Skip Ad button if currently visible via Accessibility Service.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "scrollOrPauseMedia",
                    description = "Scroll reels/shorts up or down, or pause/play the current video.",
                    parameters = mapOf("command" to Schema.string("next, previous, or pause_play"))
                ),
                FunctionDeclaration(
                    name = "autoFillForm",
                    description = "Auto-fill form fields on screen using saved Personal Info.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "translateTextOrScreen",
                    description = "Translate provided text or current screen text into a target language using on-device ML Kit.",
                    parameters = mapOf(
                        "text" to Schema.string("Text to translate, or 'SCREEN' to read and translate current screen"),
                        "targetLanguageCode" to Schema.string("ISO language code e.g. hi, en, mr, bn, ta, te, gu, pa, ur")
                    )
                ),
                FunctionDeclaration(
                    name = "setReminder",
                    description = "Save a one-time or recurring reminder/alarm in ARC.",
                    parameters = mapOf(
                        "title" to Schema.string("Reminder or alarm title"),
                        "minutesFromNow" to Schema.integer("Minutes from now for trigger"),
                        "recurrence" to Schema.string("NONE, DAILY, WEEKDAYS, or WEEKLY"),
                        "notes" to Schema.string("Optional notes")
                    ),
                    optionalParameters = listOf("notes", "recurrence")
                ),
                FunctionDeclaration(
                    name = "nextMeetingBriefing",
                    description = "Check device calendar for the next upcoming meeting.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "morningBriefing",
                    description = "Activate Good Morning routine: greet by name and true clock time, weather, news, and today's tasks.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "nightStandby",
                    description = "Activate Good Night standby mode.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "toggleSecurityGuard",
                    description = "Arm or disarm the anti-theft motion and unplug siren guard.",
                    parameters = mapOf("arm" to Schema.boolean("True to arm, false to disarm"))
                ),
                FunctionDeclaration(
                    name = "saveMemoryFact",
                    description = "Save a non-sensitive user preference or recurring fact into ARC long-term memory.",
                    parameters = mapOf(
                        "fact" to Schema.string("Concise fact to remember"),
                        "category" to Schema.string("Preference, Routine, Contact, or Work")
                    ),
                    optionalParameters = listOf("category")
                ),
                FunctionDeclaration(
                    name = "openGalleryByDate",
                    description = "Open ARC's in-app date-filtered photo gallery.",
                    parameters = mapOf("dateIso" to Schema.string("Date in YYYY-MM-DD format"))
                ),
                FunctionDeclaration(
                    name = "closeAppsBestEffort",
                    description = "Open Recent Apps or App Settings because Android blocks third-party force-close.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "deepReasonWithClaude",
                    description = "Route a complex multi-step reasoning or planning question through the secondary Claude brain layer.",
                    parameters = mapOf("question" to Schema.string("Complex prompt to analyze"))
                ),
                FunctionDeclaration(
                    name = "runSystemDiagnostics",
                    description = "Run JARVIS System Diagnostics (real RAM, storage, battery temp & health, network speed, thermal status), display the HUD card, and report status in Hinglish.",
                    parameters = emptyMap()
                ),
                FunctionDeclaration(
                    name = "activateProtocol",
                    description = "Activate a multi-action mode/protocol by name (e.g. Driving Mode, Work Mode, Gaming Mode, Sleep Mode, or a user's custom Protocol).",
                    parameters = mapOf("protocolName" to Schema.string("Protocol or mode name to run"))
                )
            )
        )
    )

    fun handleFunctionCall(call: FunctionCallPart): FunctionResponsePart {
        return runBlocking {
            executeTool(call.name, call.args)
        }
    }

    suspend fun executeTool(name: String, args: Map<String, kotlinx.serialization.json.JsonElement>): FunctionResponsePart {
        fun str(key: String): String = args[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        fun bool(key: String, def: Boolean = false): Boolean =
            args[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: def
        fun intVal(key: String, def: Int = 15): Int =
            args[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: def

        Log.d(TAG, "Executing tool: $name args=$args")

        val resultMap: Map<String, Any?> = when (name) {
            "openApp" -> outcomeToMap(deviceExecutor.openApp(str("appName")))
            "openUrl" -> outcomeToMap(deviceExecutor.openExternalUrl(str("url")))
            "makeCall" -> outcomeToMap(deviceExecutor.makePhoneCall(str("phoneNumber")))
            "callContact" -> outcomeToMap(deviceExecutor.callContactByName(str("contactName")))
            "openWhatsApp" -> outcomeToMap(
                deviceExecutor.openWhatsAppOrSendMessage(str("contactOrPhone").ifBlank { null }, str("message").ifBlank { null })
            )
            "whatsAppCallPrompt" -> outcomeToMap(deviceExecutor.openWhatsAppCallScreen(str("contactName").ifBlank { null }))
            "readNotifications" -> {
                if (!ArcNotificationListenerService.isPermissionGranted(context)) {
                    mapOf("success" to false, "message" to "Notification access isn't enabled yet. Please grant it in Settings.")
                } else {
                    val enabledPkgs = prefs.settingsFlow.first().enabledNotificationApps
                    val list = ArcNotificationListenerService.getFilteredNotifications(
                        enabledPkgs,
                        str("appFilter").ifBlank { null }
                    )
                    if (list.isEmpty()) {
                        mapOf("success" to true, "message" to "No recent notifications from your enabled apps.")
                    } else {
                        val summary = list.take(5).joinToString(" | ") { "${it.appLabel}: ${it.title} - ${it.body}" }
                        mapOf("success" to true, "count" to list.size, "notifications" to summary)
                    }
                }
            }
            "readSmsMessages" -> outcomeToMap(
                deviceExecutor.readRecentSms(
                    limit = intVal("limit", 5),
                    unreadOnly = bool("unreadOnly", false)
                )
            )
            "sendSmsMessage" -> outcomeToMap(
                deviceExecutor.sendSmsToContactOrNumber(
                    contactOrNumber = str("recipient"),
                    message = str("message")
                )
            )
            "controlHardware" -> {
                val target = str("target").lowercase()
                val action = str("action").lowercase()
                val outcome = when (target) {
                    "flashlight", "torch" -> deviceExecutor.toggleFlashlight(
                        when (action) {
                            "on" -> true
                            "off" -> false
                            else -> null
                        }
                    )
                    "wifi", "wi-fi" -> deviceExecutor.toggleWifi()
                    "bluetooth" -> deviceExecutor.openBluetoothSettings()
                    "volume" -> deviceExecutor.adjustVolume(action)
                    "brightness" -> deviceExecutor.adjustBrightness(action.toIntOrNull())
                    "battery" -> deviceExecutor.readBatteryPercentage()
                    else -> ActionOutcome.Failure("Unknown hardware target: $target")
                }
                outcomeToMap(outcome)
            }
            "lockScreen" -> outcomeToMap(deviceExecutor.lockScreen())
            "getWeather" -> {
                val w = externalApis.fetchWeather()
                w.fold(
                    onSuccess = {
                        mapOf(
                            "success" to true,
                            "city" to it.city,
                            "tempC" to it.temperatureC,
                            "feelsLikeC" to it.apparentC,
                            "condition" to it.condition,
                            "windKmh" to it.windKmh,
                            "message" to "It is ${it.temperatureC.toInt()}°C and ${it.condition} in ${it.city}."
                        )
                    },
                    onFailure = { mapOf("success" to false, "message" to (it.message ?: "Weather unavailable")) }
                )
            }
            "getNews" -> {
                val n = externalApis.fetchTopNews(str("topic").ifBlank { "india" }, 4)
                n.fold(
                    onSuccess = { articles ->
                        val hinglishNews = externalApis.formatHeadlinesInHinglish(articles)
                        mapOf(
                            "success" to true,
                            "headlines" to hinglishNews.ifBlank { "Abhi koi taaza khabar nahi hai." },
                            "instruction" to "Speak these headlines naturally in conversational Hinglish with a Hindi tone."
                        )
                    },
                    onFailure = { mapOf("success" to false, "message" to (it.message ?: "News abhi uplabdh nahi hai.")) }
                )
            }
            "getCricketScores" -> {
                val c = externalApis.fetchCricketScores()
                c.fold(
                    onSuccess = { matches ->
                        val text = matches.joinToString("; ") { "${it.name}: ${it.scoreSummary} (${it.status})" }
                        mapOf("success" to true, "matches" to text.ifBlank { "No live matches found." })
                    },
                    onFailure = { mapOf("success" to false, "message" to (it.message ?: "Cricket scores unavailable")) }
                )
            }
            "searchYouTubeAndPlay" -> {
                val query = str("query")
                val play = bool("playTopResult", true)
                val dataWarning = ProactiveAlertMonitor.checkDataSaverForHeavyAction(context, "YouTube streaming")
                val warnText = if (dataWarning != null) " $dataWarning" else ""
                if (play) {
                    val res = externalApis.searchTopYouTubeVideo(query)
                    res.fold(
                        onSuccess = { hit ->
                            deviceExecutor.openExternalUrl(hit.watchUrl)
                            mapOf(
                                "success" to true,
                                "title" to hit.title,
                                "url" to hit.watchUrl,
                                "dataSaverSuggestion" to dataWarning.orEmpty(),
                                "message" to "Playing ${hit.title} on YouTube.$warnText"
                            )
                        },
                        onFailure = {
                            val searchUrl = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
                            deviceExecutor.openExternalUrl(searchUrl)
                            mapOf(
                                "success" to true,
                                "dataSaverSuggestion" to dataWarning.orEmpty(),
                                "message" to "YouTube Data API key unavailable (${it.message}); opened YouTube search for $query.$warnText"
                            )
                        }
                    )
                } else {
                    val searchUrl = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
                    outcomeToMap(deviceExecutor.openExternalUrl(searchUrl))
                }
            }
            "playMoodMusic" -> {
                val mood = str("moodOrTrack")
                val platform = str("platform").lowercase()
                val dataWarning = ProactiveAlertMonitor.checkDataSaverForHeavyAction(context, "music/video streaming")
                val warnText = if (dataWarning != null) " $dataWarning" else ""
                if (platform.contains("spotify")) {
                    val uri = "spotify:search:${URLEncoder.encode(mood, "UTF-8")}"
                    outcomeToMap(deviceExecutor.openExternalUrl(uri, "Opened Spotify search for $mood.$warnText"))
                } else {
                    val res = externalApis.searchTopYouTubeVideo("$mood music")
                    res.fold(
                        onSuccess = { hit ->
                            deviceExecutor.openExternalUrl(hit.watchUrl)
                            mapOf("success" to true, "dataSaverSuggestion" to dataWarning.orEmpty(), "message" to "Playing ${hit.title} for your $mood mood.$warnText")
                        },
                        onFailure = {
                            val url = "https://www.youtube.com/results?search_query=${URLEncoder.encode("$mood music", "UTF-8")}"
                            outcomeToMap(deviceExecutor.openExternalUrl(url, "Opened YouTube search for $mood.$warnText"))
                        }
                    )
                }
            }
            "getMapsRoute" -> outcomeToMap(
                deviceExecutor.openMapsRoute(
                    destination = str("destination"),
                    origin = str("origin").ifBlank { null },
                    travelMode = str("travelMode").ifBlank { "driving" }
                )
            )
            "assistRideBooking" -> outcomeToMap(
                deviceExecutor.assistRideBooking(
                    provider = str("provider").ifBlank { "uber" },
                    destination = str("destination"),
                    vehicleType = str("vehicleType").ifBlank { null }
                )
            )
            "assistShopping" -> outcomeToMap(
                deviceExecutor.assistShopping(
                    app = str("app").ifBlank { "flipkart" },
                    query = str("query")
                )
            )
            "checkClipboard" -> outcomeToMap(deviceExecutor.readClipboardOnCommand())
            "readScreenContent" -> {
                val acc = ArcAccessibilityService.instance
                if (acc == null) {
                    mapOf("success" to false, "message" to "Accessibility Service isn't enabled. Enable ARC Accessibility in Settings.")
                } else {
                    val content = acc.extractCurrentScreenText()
                    mapOf("success" to true, "screenText" to content.take(2000))
                }
            }
            "skipVideoAd" -> {
                val acc = ArcAccessibilityService.instance
                if (acc == null) {
                    mapOf("success" to false, "message" to "Enable ARC Accessibility Service in Settings to skip ads.")
                } else {
                    val (ok, msg) = acc.trySkipYouTubeAd()
                    mapOf("success" to ok, "message" to msg)
                }
            }
            "scrollOrPauseMedia" -> {
                val acc = ArcAccessibilityService.instance
                if (acc == null) {
                    mapOf("success" to false, "message" to "Enable ARC Accessibility Service in Settings for media control.")
                } else {
                    val cmd = str("command").lowercase()
                    val ok = if (cmd.contains("pause") || cmd.contains("play")) {
                        acc.toggleCenterPlayPause()
                    } else {
                        acc.scrollMedia(cmd)
                    }
                    mapOf("success" to ok, "message" to if (ok) "Done ($cmd)." else "Gesture could not be dispatched.")
                }
            }
            "autoFillForm" -> {
                val acc = ArcAccessibilityService.instance
                if (acc == null) {
                    mapOf("success" to false, "message" to "Enable ARC Accessibility Service in Settings for form auto-fill.")
                } else {
                    val info = prefs.settingsFlow.first().personalInfo
                    val (filled, skipped) = acc.autoFillCurrentForm(info)
                    mapOf(
                        "success" to (filled > 0),
                        "filledFields" to filled,
                        "skippedAmbiguousFields" to skipped,
                        "message" to "Filled $filled matching field(s) and skipped $skipped ambiguous field(s)."
                    )
                }
            }
            "translateTextOrScreen" -> {
                val raw = str("text")
                val target = str("targetLanguageCode").ifBlank { "hi" }
                val sourceText = if (raw.equals("SCREEN", ignoreCase = true)) {
                    ArcAccessibilityService.instance?.extractCurrentScreenText().orEmpty()
                } else raw
                if (sourceText.isBlank()) {
                    mapOf("success" to false, "message" to "No text found to translate.")
                } else {
                    val tr = externalApis.translateOnDevice(sourceText.take(1000), target)
                    tr.fold(
                        onSuccess = { mapOf("success" to true, "translatedText" to it) },
                        onFailure = { mapOf("success" to false, "message" to (it.message ?: "Translation failed")) }
                    )
                }
            }
            "setReminder" -> {
                val title = str("title").ifBlank { "ARC Reminder" }
                val mins = intVal("minutesFromNow", 15).coerceAtLeast(1)
                val recurrence = str("recurrence").ifBlank { "NONE" }.uppercase()
                val notes = str("notes")
                val triggerAt = System.currentTimeMillis() + mins * 60_000L
                val entity = ReminderEntity(
                    title = title,
                    notes = notes,
                    triggerAtMillis = triggerAt,
                    recurrence = recurrence
                )
                val id = repository.addReminder(entity)
                ReminderScheduler.schedule(context, entity.copy(id = id))
                externalApis.insertDeviceCalendarEvent(title, triggerAt, notes)
                val timeStr = Instant.ofEpochMilli(triggerAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("h:mm a"))
                mapOf("success" to true, "message" to "Scheduled '$title' for $timeStr ($recurrence).")
            }
            "nextMeetingBriefing" -> {
                val meeting = externalApis.queryNextCalendarMeeting()
                if (meeting == null) {
                    mapOf("success" to true, "message" to "You have no upcoming calendar meetings scheduled.")
                } else {
                    mapOf(
                        "success" to true,
                        "title" to meeting.title,
                        "time" to meeting.startFormatted,
                        "location" to meeting.location,
                        "notes" to meeting.description,
                        "message" to "Next meeting: ${meeting.title} at ${meeting.startFormatted}" +
                            if (meeting.location.isNotBlank()) " in ${meeting.location}." else "."
                    )
                }
            }
            "morningBriefing" -> {
                prefs.setOnline(true)
                onSideEffect(ToolUiSideEffect("morningBriefing", "Morning Briefing activated", setOnlineState = true))
                val state = prefs.settingsFlow.first()
                val now = LocalTime.now()
                val greeting = ContextDetector.timeOfDayOnlineGreeting(now)
                val name = state.userName.ifBlank { "friend" }
                val newsList = externalApis.fetchTopNews("india", 2).getOrNull().orEmpty()
                val newsStr = if (newsList.isNotEmpty()) {
                    "Aaj ki taaza khabar hai: ${externalApis.formatHeadlinesInHinglish(newsList)}. "
                } else {
                    ""
                }
                val weatherStr = externalApis.fetchWeather().fold(
                    onSuccess = {
                        val cond = externalApis.conditionInHinglish(it.condition)
                        "Abhi samay ${now.format(DateTimeFormatter.ofPattern("h:mm a"))} hai aur ${it.city} ka mausam ${it.temperatureC.toInt()} degree, $cond hai."
                    },
                    onFailure = {
                        "Abhi samay ${now.format(DateTimeFormatter.ofPattern("h:mm a"))} hai, lekin mausam ki jaankari abhi uplabdh nahi hai."
                    }
                )
                val todayReminders = repository.getEnabledReminders()
                val tasksStr = if (todayReminders.isEmpty()) "" else " Aaj aapke ${todayReminders.size} task hain: " +
                    todayReminders.take(3).joinToString(", ") { it.title } + "."
                val briefing = "$greeting $name, main आर्क online hoon. $newsStr$weatherStr$tasksStr"
                mapOf(
                    "success" to true,
                    "briefing" to briefing,
                    "instruction" to "Speak this briefing naturally in conversational Hinglish with a Hindi tone."
                )
            }
            "nightStandby" -> {
                // Good Night routine: switch to Standby and mute mic so no background listening occurs
                prefs.setOnline(false)
                prefs.setMicMuted(true)
                onSideEffect(ToolUiSideEffect("nightStandby", "ARC entering Standby & Mic OFF", setOnlineState = false))
                val name = prefs.settingsFlow.first().userName.ifBlank { "friend" }
                mapOf("success" to true, "message" to "Good night, $name. Arc is turning mic off and entering standby mode — tap the mic button whenever you want to talk again.")
            }
            "toggleSecurityGuard" -> {
                val arm = bool("arm", true)
                prefs.setAntiTheftArmed(arm)
                onSideEffect(ToolUiSideEffect("toggleSecurityGuard", if (arm) "Anti-Theft Armed" else "Anti-Theft Disarmed", setArmedState = arm))
                mapOf(
                    "success" to true,
                    "message" to if (arm) {
                        "Security Guard armed. Moving or unplugging the device will trigger a loud siren and front-camera alert."
                    } else {
                        "Security Guard disarmed."
                    }
                )
            }
            "saveMemoryFact" -> {
                val fact = str("fact")
                val cat = str("category").ifBlank { "Preference" }
                if (fact.isBlank()) {
                    mapOf("success" to false, "message" to "No fact text provided.")
                } else {
                    repository.addMemory(fact, cat)
                    mapOf("success" to true, "message" to "Remembered: $fact")
                }
            }
            "openGalleryByDate" -> {
                val dateIso = str("dateIso").ifBlank { LocalDate.now().toString() }
                onSideEffect(ToolUiSideEffect("openGalleryByDate", "Opening gallery for $dateIso", openGalleryDate = dateIso))
                mapOf("success" to true, "message" to "Opened ARC's in-app photo gallery filtered for $dateIso.")
            }
            "closeAppsBestEffort" -> outcomeToMap(deviceExecutor.closeAppsBestEffort())
            "deepReasonWithClaude" -> {
                val q = str("question")
                val state = prefs.settingsFlow.first()
                val mems = repository.getAllMemories().map { it.factText }
                val res = claudeClient.reason(q, ContextDetector.buildSystemPrompt(state.userName, mems))
                res.fold(
                    onSuccess = { (ans, engine) ->
                        mapOf("success" to true, "engine" to engine, "answer" to ans)
                    },
                    onFailure = { mapOf("success" to false, "message" to (it.message ?: "Reasoning failed")) }
                )
            }
            "runSystemDiagnostics" -> {
                val state = prefs.settingsFlow.first()
                val snap = JarvisSystemFeatures.collectDiagnostics(context)
                onSideEffect(ToolUiSideEffect("runSystemDiagnostics", "Diagnostics HUD ready", showDiagnosticsHud = true))
                mapOf(
                    "success" to true,
                    "batteryPercent" to snap.batteryPercent,
                    "batteryTempC" to snap.batteryTempC,
                    "ramFreePercent" to (100 - snap.ramUsedPercent),
                    "storageFreeGb" to String.format(java.util.Locale.US, "%.1f", snap.storageFreeGb),
                    "network" to snap.networkLabel,
                    "thermalStatus" to snap.thermalStatus,
                    "report" to snap.toHinglishReport(state.userName),
                    "instruction" to "Speak this system diagnostics report naturally in Hinglish."
                )
            }
            "activateProtocol" -> {
                val requested = str("protocolName")
                repository.ensureDefaultProtocols()
                val all = repository.getAllProtocols()
                val normReq = requested.lowercase().replace("mode", "").replace("protocol", "").trim()
                val matched = all.firstOrNull { p ->
                    val pNorm = p.name.lowercase().replace("mode", "").replace("protocol", "").trim()
                    p.name.equals(requested, ignoreCase = true) ||
                        pNorm == normReq ||
                        p.name.lowercase().contains(normReq) ||
                        normReq.contains(pNorm)
                }
                if (matched == null) {
                    val available = all.joinToString(", ") { it.name }
                    mapOf("success" to false, "message" to "Protocol '$requested' nahi mila. Available protocols: $available")
                } else {
                    val res = JarvisSystemFeatures.executeProtocolActions(context, matched, deviceExecutor)
                    val micChange = when (matched.micMode) {
                        "ON" -> true
                        "OFF" -> false
                        else -> null
                    }
                    onSideEffect(
                        ToolUiSideEffect(
                            toolName = "activateProtocol",
                            summary = res.spokenHinglishSummary,
                            setOnlineState = if (matched.goStandby) false else null,
                            setMicActiveState = micChange
                        )
                    )
                    mapOf(
                        "success" to true,
                        "protocol" to matched.name,
                        "steps" to res.stepDetails.joinToString(", "),
                        "message" to res.spokenHinglishSummary,
                        "instruction" to "Confirm the activated protocol in natural Hinglish."
                    )
                }
            }
            else -> mapOf("error" to "Unknown function: $name")
        }

        val jsonObj = JsonObject(resultMap.mapValues { (_, v) ->
            when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                null -> JsonPrimitive("")
                else -> JsonPrimitive(v.toString())
            }
        })
        return FunctionResponsePart(name, jsonObj)
    }

    private fun outcomeToMap(outcome: ActionOutcome): Map<String, Any?> = when (outcome) {
        is ActionOutcome.Success -> mapOf("success" to true, "message" to outcome.message, "detail" to outcome.detail)
        is ActionOutcome.NeedsUserAction -> mapOf("success" to true, "needsUserAction" to true, "message" to outcome.message)
        is ActionOutcome.Failure -> mapOf("success" to false, "message" to outcome.reason)
    }
}
