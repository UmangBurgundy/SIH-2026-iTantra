package org.itantra.speech

import org.itantra.speech.model.LanguageManager
import org.itantra.speech.model.ModelSource
import org.itantra.speech.stt.IndicSTTBackend
import org.itantra.speech.tts.HindiMmsTTSBackend
import org.itantra.speech.tts.MmsTTSBackend
import org.itantra.speech.utils.AudioUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests for Phase 9.3: Runtime Memory, Model Lifecycle & Engine Optimization.
 *
 * Validates:
 * 1. ModelSource resolution across asset paths and persistent filesystem directories
 * 2. Uninitialized backend safety and proper status flags
 * 3. Native session release safety (multiple idempotent calls)
 * 4. Audio buffer pooling in AudioUtils (reusing pre-allocated arrays)
 * 5. Single resident language model lifecycle policy
 */
class ModelLifecycleUnitTest {

    @Test
    fun testModelSourceResolutionForAsset() {
        val source = ModelSource.resolve(
            modelDir = null,
            subDir = "tts",
            fileNamePrefix = "mms-hin",
            extension = ".onnx",
            fallbackAssetPath = "models/tts/mms-hin.int8.onnx"
        )
        assertTrue("Null modelDir must resolve to ModelSource.Asset", source is ModelSource.Asset)
        assertEquals("models/tts/mms-hin.int8.onnx", (source as ModelSource.Asset).assetPath)
        assertEquals("asset:models/tts/mms-hin.int8.onnx", source.identifier)
    }

    @Test
    fun testModelSourceResolutionForFileSystem() {
        val tempDir = File.createTempFile("model_repo_test", "").apply {
            delete()
            mkdirs()
        }

        try {
            val ttsSubdir = File(tempDir, "tts").apply { mkdirs() }
            val fakeModel = File(ttsSubdir, "mms-tam.int8.onnx").apply {
                writeText("ONNX_HEADER_DUMMY_DATA_1234567890_MODEL")
            }

            val source = ModelSource.resolve(
                modelDir = tempDir.absolutePath,
                subDir = "tts",
                fileNamePrefix = "mms-",
                extension = ".onnx",
                fallbackAssetPath = "models/tts/mms-hin.int8.onnx"
            )

            assertTrue("Existing file in modelDir must resolve to ModelSource.FileSystem", source is ModelSource.FileSystem)
            assertEquals(fakeModel.absolutePath, (source as ModelSource.FileSystem).file.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testIndicSTTBackendLifecycleAndIdempotentRelease() {
        val backend = IndicSTTBackend(langCode = "ta")
        assertFalse("Backend must start uninitialized", backend.isLoaded)

        // Multiple idempotent releases must not throw or crash
        backend.release()
        backend.release()
        assertFalse(backend.isLoaded)

        // Transcription on released backend must fail gracefully without crash
        val fakePcm = ByteArray(3200)
        val result = backend.transcribe(fakePcm, "ta")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage?.contains("not initialized") == true)
    }

    @Test
    fun testMmsTTSBackendLifecycleAndIdempotentRelease() {
        val backend = MmsTTSBackend(modelName = "MMS-Tamil", langCode = "ta")
        assertFalse("TTS Backend must start uninitialized", backend.isLoaded)

        // Multiple idempotent releases must not crash
        backend.release()
        backend.release()
        assertFalse(backend.isLoaded)

        val result = backend.synthesize("வணக்கம்")
        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage?.contains("not initialized") == true)
    }

    @Test
    fun testAudioUtilsBufferPooling() {
        // Create 100ms 16kHz PCM audio = 1600 samples = 3200 bytes
        val pcm = ByteArray(3200) { (it % 120).toByte() }

        // Test with pre-allocated buffer
        val preallocatedBuffer = FloatArray(1600)
        val result = AudioUtils.pcm16ToFloatArray(pcm, preallocatedBuffer)

        assertSame("Returned float array must be identical instance to preallocated buffer", preallocatedBuffer, result)
        assertEquals(1600, result.size)

        // Test with null buffer (fallback to new allocation)
        val freshResult = AudioUtils.pcm16ToFloatArray(pcm, null)
        assertNotSame("Fresh result must be newly allocated", preallocatedBuffer, freshResult)
        assertEquals(1600, freshResult.size)
    }

    @Test
    fun testSingleActiveLanguageResidency() {
        val lm = LanguageManager()
        assertEquals("hi", lm.activeLanguage.code)

        // Switching language changes active language reference
        val guj = lm.supportedLanguages.first { it.code == "gu" }
        lm.setLanguage(guj)
        assertEquals("gu", lm.activeLanguage.code)

        // Creating backend produces corresponding engine without holding previous engines
        val backend = lm.createBackendForLanguage(guj)
        assertTrue(backend is IndicSTTBackend)
        assertEquals("gu", (backend as IndicSTTBackend).langCode)
    }
}
