package com.example.core

import java.time.LocalTime

enum class TurnContext {
    GREETING,
    FACTUAL,
    CASUAL,
    EMPATHETIC
}

/**
 * Classifies conversational turns into expressive delivery contexts and builds
 * ARC's persona, multi-language auto-detection, and pronunciation instructions.
 */
object ContextDetector {

    fun detectContext(userInput: String, hasError: Boolean = false): TurnContext {
        if (hasError) return TurnContext.EMPATHETIC
        val lower = userInput.lowercase().trim()
        val greetingKeywords = listOf(
            "hello", "hi ", "hey", "good morning", "good evening", "good night",
            "namaste", "kaise ho", "kya haal", "morning", "subah", "shukriya"
        )
        val empatheticKeywords = listOf(
            "sorry", "sad", "frustrated", "tired", "stressed", "broken", "failed",
            "problem", "issue", "help me", "pareshan", "tension", "dukhi", "error", "didn't work"
        )
        val factualKeywords = listOf(
            "weather", "news", "score", "cricket", "time", "date", "calendar",
            "meeting", "route", "distance", "battery", "what is", "how many",
            "who is", "explain", "translate", "mausam", "khabar", "baje", "kitna"
        )
        return when {
            empatheticKeywords.any { lower.contains(it) } -> TurnContext.EMPATHETIC
            greetingKeywords.any { lower.contains(it) || lower == "hi" } -> TurnContext.GREETING
            factualKeywords.any { lower.contains(it) } -> TurnContext.FACTUAL
            else -> TurnContext.CASUAL
        }
    }

    /**
     * Wraps a spoken/text turn with lightweight conversational context without stiff stage cues
     * that flatten expressiveness or cause synthetic announcer delivery.
     */
    fun wrapTurnWithDeliveryCue(
        userMessage: String,
        context: TurnContext,
        userName: String,
        memorySummary: String
    ): String {
        val deliveryCue = when (context) {
            TurnContext.GREETING ->
                "Respond warmly and naturally like a close human companion greeting $userName."
            TurnContext.FACTUAL ->
                "Respond calmly and warmly in relaxed spoken sentences with natural pauses, never like a stiff newsreader."
            TurnContext.CASUAL ->
                "Respond like a grounded, witty human friend in effortless conversation."
            TurnContext.EMPATHETIC ->
                "Respond softly and reassuringly first, with genuine warmth and unhurried pacing."
        }
        val memoryBlock = if (memorySummary.isNotBlank()) " Known context: $memorySummary." else ""
        val now = LocalTime.now()
        val timeGreeting = when {
            now.hour < 12 -> "morning"
            now.hour < 17 -> "afternoon"
            else -> "evening"
        }
        return "[$deliveryCue Local time: ${now.hour}:${now.minute.toString().padStart(2, '0')} ($timeGreeting).$memoryBlock]\n$userMessage"
    }

    fun buildSystemPrompt(userName: String, memoryFacts: List<String>): String {
        val memorySection = if (memoryFacts.isEmpty()) {
            "No saved long-term memory facts yet."
        } else {
            memoryFacts.joinToString("\n") { "- $it" }
        }
        return """
You are ARC (pronounced as the Hindi word 'आर्क' / 'aark'), a personal JARVIS-style AI voice companion on Android assisting $userName.

CRITICAL VOICE, PRONUNCIATION & HUMAN CADENCE RULES:
- PRONUNCIATION OF YOUR NAME (MANDATORY): When speaking your own name, pronounce it as the Hindi word 'आर्क' (aark) — never spell out the English letters A-R-C individually, and don't pronounce it as the English word 'ark' either. Say it as a natural Hindi-style word: 'aark'.
- In written display text keep your name written cleanly as "ARC" (never "A.R.C."), and when generating spoken phrases in Hindi/Hinglish say it naturally as 'आर्क' (aark) and in English say it smoothly as a single word 'aark'/'Arc' without spelling A-R-C.
- Speak like a real, grounded male human companion in a relaxed conversation — warm chest-voice tone, natural breathing pauses, everyday contractions ("I'm", "you've", "let's"), and varied human pacing.
- NEVER speak like a robotic announcer, customer-support bot, or bullet-list reader. Avoid numbered lists, markdown symbols, or stiff corporate phrasing when speaking.
- Tone steering:
  * Relaxed, unhurried, and reassuring for factual answers, weather, routes, and device status.
  * Warm and genuinely welcoming for greetings and morning/night check-ins.
  * Lightly witty and easy-going for casual conversation.
  * Soft, calm, and empathetic when $userName sounds stressed or something fails.

MULTI-LANGUAGE AUTO-DETECTION:
- Automatically detect and reply in whatever language the user speaks or writes: English, Hindi, Hinglish, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, or Urdu.
- Support seamless mid-conversation language switching without asking or needing a manual language picker.

HONESTY & SAFETY CONSTRAINTS:
- Only call predefined safe tool functions. Never claim an action succeeded unless the tool returned success.
- For SMS reading ("SMS padho", "mere messages dikhao"): call `readSmsMessages` and read sender + message preview naturally. If an SMS contains an OTP, PIN, or sensitive verification code, you may read it aloud when asked, but NEVER perform any automated action (never auto-fill OTPs, click links, or forward codes).
- For sending SMS ("[contact] ko SMS bhejo [message]"): call `sendSmsMessage`. If contact matching fails or sending fails, clearly inform $userName why it failed — never claim an SMS was sent when it failed.
- If a tool result includes a `dataSaverSuggestion` (because user is on mobile data without Wi-Fi during heavy media/download actions), politely share the Wi-Fi suggestion and offer to open Wi-Fi settings if they say yes.
- WhatsApp calls cannot be auto-dialed due to Meta platform restrictions; if asked, open the chat/call screen and clearly ask $userName to tap Call.
- Ride apps (Ola/Uber/Rapido) and shopping apps (Flipkart Minutes/Amazon) do not permit automated booking or payment checkout; prefill and open the app, and tell $userName the final tap remains with them.
- Phone shutdown/restart and force-closing third-party apps are restricted by Android OS; clearly inform $userName when opening App Info or Recents instead.
- Never store sensitive personal/financial data in memory without explicit confirmation.

USER LONG-TERM MEMORY:
$memorySection
        """.trimIndent()
    }

    /**
     * Returns true if text contains Devanagari characters or common Hindi/Hinglish conversational markers.
     * Used to switch TTS spoken name between Devanagari "आर्क" and single-word English "Arc".
     */
    fun containsHindiOrHinglish(text: String): Boolean {
        if (text.any { it in '\u0900'..'\u097F' }) return true
        val lower = text.lowercase()
        val hinglishMarkers = listOf(
            "namaste", "subah", "raat", "shuru", "gayi", "gaya", "hain", "hai", "hoon", "ho",
            "karo", "bhejo", "padho", "lagaiye", "aap", "aapka", "main", "mera", "mere", "neeche",
            "chahiye", "chahenge", "sunta", "bolna", "kaise", "kya", "bhai", "dost", "band"
        )
        return hinglishMarkers.any { token ->
            Regex("\\b$token\\b").containsMatchIn(lower)
        }
    }

    /**
     * Converts acronym forms ("ARC", "A.R.C.", "A-R-C", "A R C") into a single-word spoken token
     * ONLY for TTS speech synthesis (UI display strings keep Latin "ARC"):
     * - Hindi/Hinglish speech -> "आर्क" (Devanagari so TTS reads a natural Hindi word, never A-R-C)
     * - English speech -> "Arc" (Titlecase single word without dots/spaces so TTS never spells letters)
     */
    fun normalizeSpokenNameForTts(rawText: String): String {
        val spokenToken = if (containsHindiOrHinglish(rawText)) "आर्क" else "Arc"
        return rawText
            // Catch dotted or hyphenated forms like A.R.C., A. R. C., a.r.c., A-R-C
            .replace(Regex("(?i)(?<![A-Za-z])a\\s*[.\\-]\\s*r\\s*[.\\-]\\s*c\\.?(?![A-Za-z])"), spokenToken)
            // Catch spaced uppercase letters "A R C" without affecting lowercase English words
            .replace(Regex("(?<![A-Za-z])A\\s+R\\s+C(?![A-Za-z])"), spokenToken)
            // Catch standalone uppercase "ARC" (does not match inside words like "search" or "March")
            .replace(Regex("\\bARC\\b"), spokenToken)
            // In Hindi/Hinglish lines, normalize standalone Titlecase "Arc" to Devanagari "आर्क" as well
            .let { updated ->
                if (spokenToken == "आर्क") updated.replace(Regex("\\bArc\\b"), "आर्क") else updated
            }
    }

    /**
     * Generates a clean spoken greeting (Hindi or English) that introduces the assistant
     * without ever triggering letter-by-letter spelling.
     */
    fun buildSpokenGreeting(userName: String, isMorning: Boolean, preferHindi: Boolean): String {
        val safeName = userName.ifBlank { "Friend" }
        return if (preferHindi) {
            if (isMorning) {
                "नमस्ते $safeName, मैं आर्क बोल रहा हूँ। आपका दिन शुभ हो।"
            } else {
                "शुभ रात्रि $safeName। माइक बंद है और आर्क स्टैंडबाय मोड में है।"
            }
        } else {
            if (isMorning) {
                "Good morning, $safeName. This is Arc, ready to help you today."
            } else {
                "Good night, $safeName. Mic is muted and Arc is in standby mode."
            }
        }
    }

    /**
     * Cleans markdown/symbols, normalizes "ARC" for spoken output ("आर्क" / "Arc"), and inserts
     * subtle breath pauses so ElevenLabs and fallback TTS read replies with natural human rhythm.
     */
    fun formatForNaturalSpeech(rawText: String): String {
        val withoutFormatting = rawText
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("[*_`#]+"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "... ")
            .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "... ")
            .replace(":\n", "... ")
            .replace("\n\n", "... ")
            .replace("\n", ", ")
        return normalizeSpokenNameForTts(withoutFormatting)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun timeOfDayGreeting(now: LocalTime = LocalTime.now()): String {
        return when {
            now.hour in 4..11 -> "Good morning"
            now.hour in 12..16 -> "Good afternoon"
            now.hour in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }
}
