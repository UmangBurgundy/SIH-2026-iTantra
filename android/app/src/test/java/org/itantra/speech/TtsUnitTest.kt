package org.itantra.speech

import org.itantra.speech.model.LanguageManager
import org.itantra.speech.tts.HindiMmsTTSBackend
import org.itantra.speech.tts.HindiTextNormalizer
import org.itantra.speech.tts.GujaratiMmsTTSBackend
import org.itantra.speech.tts.MarathiMmsTTSBackend
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for Phase 8.4 Native Android TTS architecture.
 *
 * Validates:
 * - HindiTextNormalizer Unicode NFC normalization
 * - Hindi digit expansion (0-99)
 * - Punctuation stripping and pause preservation
 * - VITS blank-token interleaving ([0, t1, 0, t2, 0, ..., tn, 0])
 * - Empty, whitespace-only, and unknown character handling
 * - Float to PCM16 little-endian conversion and clipping
 * - TTSBackend modular architecture and LanguageManager factory
 * - Graceful uninitialized synthesis failure (no crash)
 */
class TtsUnitTest {

    private lateinit var languageManager: LanguageManager

    @Before
    fun setUp() {
        languageManager = LanguageManager()
    }

    @Test
    fun testDigitExpansionSingleDigits() {
        assertEquals("शून्य", HindiTextNormalizer.expandDigits("0"))
        assertEquals("एक", HindiTextNormalizer.expandDigits("1"))
        assertEquals("पाँच", HindiTextNormalizer.expandDigits("5"))
        assertEquals("नौ", HindiTextNormalizer.expandDigits("9"))
    }

    @Test
    fun testDigitExpansionTwoDigits() {
        assertEquals("दस", HindiTextNormalizer.expandDigits("10"))
        assertEquals("पंद्रह", HindiTextNormalizer.expandDigits("15"))
        assertEquals("पच्चीस", HindiTextNormalizer.expandDigits("25"))
        assertEquals("तीस", HindiTextNormalizer.expandDigits("30"))
    }

    @Test
    fun testTextNormalizationBasic() {
        val input = "नमस्ते! आप कैसे हैं?"
        val normalized = HindiTextNormalizer.normalize(input)
        // Exclamation and question mark should be converted or stripped, text should remain clean Hindi
        assertTrue(normalized.contains("नमस्ते"))
        assertTrue(normalized.contains("आप"))
        assertTrue(normalized.contains("कैसे"))
        assertTrue(normalized.contains("हैं"))
        assertFalse(normalized.contains("!"))
        assertFalse(normalized.contains("?"))
    }

    @Test
    fun testTextNormalizationDigitsInSentence() {
        val input = "मेरे पास 2 सेब हैं।"
        val normalized = HindiTextNormalizer.normalize(input)
        assertTrue("Digit '2' should be expanded to 'दो'", normalized.contains("दो"))
        assertFalse("Raw digit '2' should not remain", normalized.contains("2"))
    }

    @Test
    fun testTokenInterleaving() {
        // Build mock vocab with simple mapping
        val mockVocab = mapOf(
            "न" to 10L,
            "म" to 11L,
            "स" to 12L,
            "्" to 13L,
            "त" to 14L,
            "े" to 15L
        )
        val normalizer = HindiTextNormalizer(mockVocab)
        val tokens = normalizer.tokenize("नमस्ते")

        // Interleaved should be: [0, 10, 0, 11, 0, 12, 0, 13, 0, 14, 0, 15, 0]
        // Length must be 2 * n + 1 = 2 * 6 + 1 = 13
        assertEquals(13, tokens.size)
        assertEquals(0L, tokens[0])
        assertEquals(10L, tokens[1])
        assertEquals(0L, tokens[2])
        assertEquals(11L, tokens[3])
        assertEquals(0L, tokens[tokens.size - 1])
    }

    @Test
    fun testEmptyAndWhitespaceInput() {
        val normalizer = HindiTextNormalizer(emptyMap())
        val emptyTokens = normalizer.tokenize("")
        assertTrue("Empty string should produce empty tokens", emptyTokens.isEmpty())

        val wsTokens = normalizer.tokenize("     ")
        assertTrue("Whitespace-only string should produce empty tokens", wsTokens.isEmpty())
    }

    @Test
    fun testFloatToPcm16Conversion() {
        val floatSamples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
        val pcm = HindiMmsTTSBackend.convertFloatToPcm16(floatSamples)

        // 5 samples * 2 bytes = 10 bytes
        assertEquals(10, pcm.size)

        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.toShort(), buffer.short)
        assertEquals((0.5 * 32767.0).toInt().toShort(), buffer.short)
        assertEquals((-0.5 * 32767.0).toInt().toShort(), buffer.short)
        assertEquals(32767.toShort(), buffer.short)
        assertEquals((-32767).toShort(), buffer.short)

        // Test peak normalization scaling
        val overRangeSamples = floatArrayOf(2.0f, -2.0f)
        val pcmScaled = HindiMmsTTSBackend.convertFloatToPcm16(overRangeSamples)
        val scaledBuffer = ByteBuffer.wrap(pcmScaled).order(ByteOrder.LITTLE_ENDIAN)
        // 2.0 scaled down by factor of 2.0 -> 1.0 -> 32767
        assertEquals(32767.toShort(), scaledBuffer.short)
        assertEquals((-32767).toShort(), scaledBuffer.short)
    }

    @Test
    fun testTTSBackendFactory() {
        val hindi = languageManager.supportedLanguages.first { it.code == "hi" }
        val hindiBackend = languageManager.createTTSBackendForLanguage(hindi)
        assertTrue("Hindi should create HindiMmsTTSBackend", hindiBackend is HindiMmsTTSBackend)
        assertEquals("Meta MMS-TTS Hindi VITS INT8", hindiBackend.modelName)

        val gujarati = languageManager.supportedLanguages.first { it.code == "gu" }
        val gujBackend = languageManager.createTTSBackendForLanguage(gujarati)
        assertTrue("Gujarati should create GujaratiMmsTTSBackend stub", gujBackend is GujaratiMmsTTSBackend)

        val marathi = languageManager.supportedLanguages.first { it.code == "mr" }
        val marBackend = languageManager.createTTSBackendForLanguage(marathi)
        assertTrue("Marathi should create MarathiMmsTTSBackend stub", marBackend is MarathiMmsTTSBackend)
    }

    @Test
    fun testUninitializedSynthesisGracefulFailure() {
        val backend = HindiMmsTTSBackend()
        assertFalse(backend.isLoaded)

        // Should return failure TTSResult instead of throwing crash
        val result = backend.synthesize("परीक्षण")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("not initialized"))
        assertTrue(result.pcmData.isEmpty())
    }

    @Test
    fun testEmptyInputSynthesisFailure() {
        val backend = HindiMmsTTSBackend()
        val result = backend.synthesize("")
        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessage)
    }
}
