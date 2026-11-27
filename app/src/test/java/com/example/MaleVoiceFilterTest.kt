package com.example

import com.example.ai.FallbackTtsManager
import com.example.data.prefs.MaleVoicePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaleVoiceFilterTest {

    @Test
    fun `google tts male voice codes are accepted and female voice codes are rejected`() {
        assertTrue(FallbackTtsManager.isKnownMaleVoice("en-in-x-end-local"))
        assertTrue(FallbackTtsManager.isKnownMaleVoice("en-in-x-ene-local"))
        assertTrue(FallbackTtsManager.isKnownMaleVoice("hi-in-x-hid-local"))
        assertTrue(FallbackTtsManager.isKnownMaleVoice("hi-in-x-hie-local"))
        assertTrue(FallbackTtsManager.isKnownMaleVoice("en-gb-x-gbb-local"))
        assertTrue(FallbackTtsManager.isKnownMaleVoice("en-us-x-iom-local"))
        assertTrue(FallbackTtsManager.isKnownMaleVoice("custom-voice", setOf("gender=male")))

        assertFalse(FallbackTtsManager.isKnownMaleVoice("en-in-x-ena-local"))
        assertFalse(FallbackTtsManager.isKnownMaleVoice("hi-in-x-hia-local"))
        assertFalse(FallbackTtsManager.isKnownMaleVoice("en-gb-x-gba-local"))
        assertFalse(FallbackTtsManager.isKnownMaleVoice("en-us-x-iog-local"))
        assertFalse(FallbackTtsManager.isKnownMaleVoice("custom-voice", setOf("gender=female")))
    }

    @Test
    fun `three male presets map cleanly to male gemini voices`() {
        assertEquals(3, MaleVoicePreset.entries.size)
        assertEquals("Orus", MaleVoicePreset.WARM_COMPANION.geminiVoiceName)
        assertEquals("Charon", MaleVoicePreset.BRITISH_JARVIS.geminiVoiceName)
        assertEquals("Algenib", MaleVoicePreset.DEEP_BARITONE.geminiVoiceName)
    }
}
