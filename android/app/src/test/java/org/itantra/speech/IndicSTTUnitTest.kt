package org.itantra.speech

import org.itantra.speech.model.LanguageManager
import org.itantra.speech.stt.IndicSTTBackend
import org.itantra.speech.stt.STTResult
import org.itantra.speech.stt.SherpaOnnxSTTBackend
import org.itantra.speech.utils.AudioUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Phase 8.2 Native Indic STT architecture.
 *
 * Validates:
 * - Indic model path & asset constants
 * - Hindi language selection & engine recommendation
 * - Dynamic language switching & model lifecycle management
 * - Honest validation statuses across all 10 target languages
 * - PCM16 to float audio conversion accuracy & boundary conditions
 * - Empty audio & uninitialized inference safety
 * - Model release & cleanup
 */
class IndicSTTUnitTest {

    private lateinit var languageManager: LanguageManager

    @Before
    fun setUp() {
        languageManager = LanguageManager()
    }

    @Test
    fun testHindiLanguageSelection() {
        val success = languageManager.setLanguageByCode("hi")
        assertTrue("Should successfully select Hindi by code 'hi'", success)

        val active = languageManager.activeLanguage
        assertEquals("hi", active.code)
        assertEquals("हिन्दी", active.nativeName)
        assertEquals("Hindi", active.englishName)
        assertEquals(LanguageManager.STTEngineType.INDIC_CONFORMER, active.recommendedEngine)
        assertEquals(LanguageManager.LanguageStatus.VALIDATED, active.status)
    }

    @Test
    fun testEnglishLanguageSelection() {
        val success = languageManager.setLanguageByCode("en")
        assertTrue("Should successfully select English by code 'en'", success)

        val active = languageManager.activeLanguage
        assertEquals("en", active.code)
        assertEquals(LanguageManager.STTEngineType.WHISPER_TINY, active.recommendedEngine)
        assertEquals(LanguageManager.LanguageStatus.VALIDATED, active.status)
    }

    @Test
    fun testHonestLanguageStatuses() {
        // Requirement 14: Never mark unvalidated Indic languages as VALIDATED
        val unvalidatedCodes = listOf("gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
        for (code in unvalidatedCodes) {
            val lang = languageManager.supportedLanguages.find { it.code == code }
            assertNotNull("Language '$code' should be present in 10-language catalog", lang)
            assertEquals(
                "Language '$code' must honestly be marked NOT_YET_VALIDATED until tested",
                LanguageManager.LanguageStatus.NOT_YET_VALIDATED,
                lang?.status
            )
            assertEquals(
                "Language '$code' must use INDIC_CONFORMER engine",
                LanguageManager.STTEngineType.INDIC_CONFORMER,
                lang?.recommendedEngine
            )
        }
    }

    @Test
    fun testBackendFactoryForLanguages() {
        val hiLang = languageManager.supportedLanguages.first { it.code == "hi" }
        val hiBackend = languageManager.createBackendForLanguage(hiLang)
        assertTrue("Hindi must create an IndicSTTBackend instance", hiBackend is IndicSTTBackend)

        val enLang = languageManager.supportedLanguages.first { it.code == "en" }
        val enBackend = languageManager.createBackendForLanguage(enLang)
        assertTrue("English must create a SherpaOnnxSTTBackend instance", enBackend is SherpaOnnxSTTBackend)
    }

    @Test
    fun testModelFileValidationConstants() {
        assertEquals("models/indic-hi.int8.onnx", IndicSTTBackend.DEFAULT_HINDI_MODEL)
        assertEquals("models/indic-tokens.txt", IndicSTTBackend.DEFAULT_INDIC_TOKENS)
    }

    @Test
    fun testPcm16ConversionEmptyAndNormal() {
        // Empty bytes
        val emptyFloats = AudioUtils.pcm16ToFloatArray(ByteArray(0))
        assertEquals(0, emptyFloats.size)

        // Single sample: Max positive (32767 -> ~1.0)
        val maxSampleBytes = byteArrayOf(0xFF.toByte(), 0x7F.toByte())
        val maxFloats = AudioUtils.pcm16ToFloatArray(maxSampleBytes)
        assertEquals(1, maxFloats.size)
        assertTrue("Sample should be close to 1.0", maxFloats[0] > 0.99f && maxFloats[0] <= 1.0f)

        // Single sample: Min negative (-32768 -> -1.0)
        val minSampleBytes = byteArrayOf(0x00.toByte(), 0x80.toByte())
        val minFloats = AudioUtils.pcm16ToFloatArray(minSampleBytes)
        assertEquals(1, minFloats.size)
        assertEquals(-1.0f, minFloats[0], 0.001f)

        // Single sample: Zero (0 -> 0.0)
        val zeroSampleBytes = byteArrayOf(0x00.toByte(), 0x00.toByte())
        val zeroFloats = AudioUtils.pcm16ToFloatArray(zeroSampleBytes)
        assertEquals(1, zeroFloats.size)
        assertEquals(0.0f, zeroFloats[0], 0.0001f)
    }

    @Test
    fun testUninitializedInferenceSafety() {
        val backend = IndicSTTBackend()
        assertFalse("Backend should start in uninitialized state", backend.isLoaded)

        // Attempting to transcribe before initialization should fail safely without crashing
        val fakeAudio = ByteArray(3200) // 100ms at 16kHz
        val result = backend.transcribe(fakeAudio, "hi")

        assertFalse("Transcribe on uninitialized backend should report isSuccess=false", result.isSuccess)
        assertEquals("", result.text)
        assertNotNull("Error message should be present", result.errorMessage)
    }

    @Test
    fun testEmptyAudioInferenceHandling() {
        val backend = IndicSTTBackend()
        // Empty audio should return empty result without crashing
        val result = backend.transcribe(ByteArray(0), "hi")
        assertFalse("Uninitialized backend returns failure", result.isSuccess)
    }

    @Test
    fun testModelReleaseState() {
        val backend = IndicSTTBackend()
        backend.release()
        assertFalse(backend.isLoaded)
    }

    @Test
    fun testSTTResultMetricsMath() {
        val result = STTResult(
            text = "नमस्ते",
            latencyMs = 150,
            audioDurationSec = 3.0,
            rtf = 0.050,
            language = "hi",
            isSuccess = true
        )

        assertEquals("नमस्ते", result.text)
        assertEquals(150L, result.latencyMs)
        assertEquals(3.0, result.audioDurationSec, 0.001)
        assertEquals(0.050, result.rtf, 0.001)
        assertEquals("hi", result.language)
        assertTrue(result.isSuccess)
        assertNull(result.errorMessage)
    }
}
