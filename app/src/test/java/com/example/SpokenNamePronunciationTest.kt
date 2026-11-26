package com.example

import com.example.core.ContextDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies ARC spoken-name pronunciation normalization across Hindi, Hinglish, and English greetings
 * as well as the explicit Gemini Live system prompt instruction.
 */
class SpokenNamePronunciationTest {

    @Test
    fun hindiGreeting_usesDevanagariAark_neverSpellsLetters() {
        val raw = "नमस्ते मयंक, मैं ARC हूँ। आज मौसम अच्छा है।"
        val spoken = ContextDetector.formatForNaturalSpeech(raw)

        assertTrue("Hindi TTS text should contain आर्क", spoken.contains("आर्क"))
        assertFalse("Hindi TTS text must not keep all-caps ARC", spoken.contains("ARC"))
        assertFalse("Hindi TTS text must not contain dotted A.R.C.", spoken.contains("A.R.C."))

        val built = ContextDetector.buildSpokenGreeting(userName = "Mayank", isMorning = true, preferHindi = true)
        assertTrue(built.contains("आर्क"))
        assertFalse(built.contains("ARC"))
    }

    @Test
    fun hinglishGreeting_convertsArcToDevanagariAark() {
        val raw = "Namaste dost, main ARC bol raha hoon. Aapka kaam shuru karo."
        val spoken = ContextDetector.formatForNaturalSpeech(raw)

        assertTrue("Hinglish TTS text should convert ARC -> आर्क", spoken.contains("आर्क"))
        assertFalse(spoken.contains("ARC"))
    }

    @Test
    fun englishGreeting_usesSingleWordArc_neverAllCapsOrDottedLetters() {
        val raw = "Good morning, Alex. This is ARC, your A.R.C. companion! A-R-C is ready."
        val spoken = ContextDetector.formatForNaturalSpeech(raw)

        assertEquals(
            "Good morning, Alex. This is Arc, your Arc companion! Arc is ready.",
            spoken
        )
        assertFalse("English TTS text must not contain all-caps ARC", Regex("\\bARC\\b").containsMatchIn(spoken))

        val built = ContextDetector.buildSpokenGreeting(userName = "Alex", isMorning = false, preferHindi = false)
        assertTrue(built.contains("Arc"))
        assertFalse(built.contains("ARC"))
    }

    @Test
    fun normalWordsContainingArcSubstring_areUntouched() {
        val raw = "Please search in March archives for ARC reminders."
        val spoken = ContextDetector.formatForNaturalSpeech(raw)

        assertTrue(spoken.contains("search"))
        assertTrue(spoken.contains("March"))
        assertTrue(spoken.contains("archives"))
        assertTrue(spoken.contains("Arc reminders"))
    }

    @Test
    fun systemPrompt_containsExplicitHindiAarkPronunciationRule() {
        val prompt = ContextDetector.buildSystemPrompt("Mayank", emptyList())
        assertTrue(prompt.contains("आर्क"))
        assertTrue(prompt.contains("aark"))
        assertTrue(prompt.contains("never spell out the English letters A-R-C individually"))
    }
}
