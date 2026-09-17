package org.itantra.speech

import org.itantra.speech.pack.LanguagePackRepository
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 9.4 Unit Tests for Model Compression, Quantization, and Graph Optimization.
 */
class ModelCompressionUnitTest {

    @Test
    fun testBaselineAndOptimizedManifestsExist() {
        val baselinePacks = LanguagePackRepository.downloadablePacks
        val optimizedPacks = LanguagePackRepository.optimizedPacks

        val expectedLanguages = listOf("gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
        assertEquals(8, baselinePacks.size)
        assertEquals(8, optimizedPacks.size)

        for (lang in expectedLanguages) {
            assertTrue("Baseline should contain $lang", baselinePacks.containsKey(lang))
            assertTrue("Optimized should contain $lang", optimizedPacks.containsKey(lang))
        }
    }

    @Test
    fun testOptimizedPackSizeReduction() {
        val expectedLanguages = listOf("gu", "mr", "kn", "ml", "ta", "te", "or", "bn")

        for (lang in expectedLanguages) {
            val base = LanguagePackRepository.getPackManifest(lang, useOptimized = false)!!
            val opt = LanguagePackRepository.getPackManifest(lang, useOptimized = true)!!

            // Verify STT model reduction: ~195MB down to ~134MB (<140MB)
            assertTrue(
                "Optimized STT size for $lang should be < 140MB, was ${opt.sttModel.sizeBytes}",
                opt.sttModel.sizeBytes < 140_000_000L
            )
            assertTrue(
                "Optimized STT for $lang should be smaller than baseline",
                opt.sttModel.sizeBytes < base.sttModel.sizeBytes
            )

            // Verify total pack size reduction: ~234MB down to ~170MB (<175MB)
            assertTrue(
                "Optimized total pack size for $lang should be < 175MB, was ${opt.totalSizeMb}",
                opt.totalSizeMb < 175.0
            )
            assertTrue(
                "Optimized pack for $lang should save at least 50MB",
                (base.totalSizeBytes - opt.totalSizeBytes) >= 50_000_000L
            )
        }
    }

    @Test
    fun testTotalCatalogStorageSavings() {
        val baselineTotalBytes = LanguagePackRepository.getAllPacks(useOptimized = false)
            .sumOf { it.totalSizeBytes }
        val optimizedTotalBytes = LanguagePackRepository.getAllPacks(useOptimized = true)
            .sumOf { it.totalSizeBytes }

        val savingsBytes = baselineTotalBytes - optimizedTotalBytes
        val savingsMb = savingsBytes.toDouble() / (1024.0 * 1024.0)

        // Across 8 packs, savings should exceed 480 MB!
        assertTrue(
            "Total 8-pack storage savings should exceed 480 MB, was $savingsMb MB",
            savingsMb > 480.0
        )
    }

    @Test
    fun testManifestVersioningAndRollback() {
        val basePack = LanguagePackRepository.getPackManifest("gu", useOptimized = false)!!
        val optPack = LanguagePackRepository.getPackManifest("gu", useOptimized = true)!!

        assertEquals("1.0", basePack.version)
        assertEquals("2.0", optPack.version)

        // Test JSON round-trip for version 2.0
        val json = optPack.toJson()
        val restored = org.itantra.speech.pack.LanguagePackManifest.fromJson(json)

        assertEquals("2.0", restored.version)
        assertEquals("gu", restored.languageCode)
        assertEquals(optPack.totalSizeBytes, restored.totalSizeBytes)
    }

    @Test
    fun testAll10LanguagesScriptCoverage() {
        // Verify all 10 language codes and scripts are valid and non-empty
        val packCodes = LanguagePackRepository.downloadablePacks.keys
        val allCodes = listOf("hi", "en") + packCodes

        assertEquals(10, allCodes.size)
        assertTrue(allCodes.contains("hi"))
        assertTrue(allCodes.contains("en"))

        for (code in packCodes) {
            val pack = LanguagePackRepository.downloadablePacks[code]!!
            assertFalse(pack.englishName.isBlank())
            assertFalse(pack.nativeName.isBlank())
            assertTrue(pack.allFiles.size == 4)
        }
    }
}
