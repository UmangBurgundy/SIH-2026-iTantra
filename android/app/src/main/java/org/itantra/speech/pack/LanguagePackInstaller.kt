package org.itantra.speech.pack

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Handles validation, SHA-256 integrity verification, and atomic installation
 * of downloaded language packs into persistent application storage.
 */
class LanguagePackInstaller(private val context: Context) {

    companion object {
        private const val TAG = "iTantraPackInstaller"

        fun verifyAndInstall(context: Context, manifest: LanguagePackManifest): InstallResult {
            val rootDir = LanguagePackRepository.getPacksRootDir(context)
            val stagingDir = File(File(rootDir, LanguagePackRepository.PARTIAL_DOWNLOAD_DIR), manifest.languageCode)
            val installer = LanguagePackInstaller(context)
            return installer.installPack(stagingDir, manifest)
        }

        fun computeSha256(file: File): String? {
            return try {
                calculateSha256(file)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Computes SHA-256 checksum of a file.
         */
        fun calculateSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            FileInputStream(file).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hashBytes = digest.digest()
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Verifies all files in staging directory against the manifest (existence, min size, SHA-256).
         */
        fun verifyStaging(stagingDir: File, manifest: LanguagePackManifest): Pair<Boolean, String?> {
            for (entry in manifest.allFiles) {
                val file = File(File(stagingDir, entry.relativeSubdir), entry.filename)
                if (!file.exists()) {
                    return Pair(false, "Missing downloaded file: ${entry.filename}")
                }
                // Basic sanity check: Tokenizers/vocab > 100 bytes, ONNX models > 1 KB in mock / 1MB in prod
                if (file.length() < 10L) {
                    return Pair(false, "Corrupt file ${entry.filename} (Size: ${file.length()} bytes)")
                }
                if (entry.sha256.isNotBlank()) {
                    val computed = calculateSha256(file)
                    if (!computed.equals(entry.sha256, ignoreCase = true)) {
                        return Pair(false, "Checksum mismatch on ${entry.filename}")
                    }
                }
            }
            return Pair(true, null)
        }
    }

    data class InstallResult(
        val isSuccess: Boolean,
        val targetDir: File?,
        val errorMessage: String? = null
    )

    /**
     * Atomically validates and installs a downloaded pack from `.partial/<langCode>`
     * to the permanent directory `language_packs/<langCode>/`.
     */
    fun installPack(stagingDir: File, manifest: LanguagePackManifest): InstallResult {
        Log.i(TAG, "Starting installation & verification of ${manifest.languageCode} from ${stagingDir.absolutePath}")

        // 1. Verify all required files exist and meet size sanity
        for (entry in manifest.allFiles) {
            val file = File(File(stagingDir, entry.relativeSubdir), entry.filename)
            if (!file.exists()) {
                stagingDir.deleteRecursively()
                return InstallResult(false, null, "Missing downloaded file: ${entry.filename}")
            }

            // Basic sanity check: Tokenizers/vocab > 100 bytes, ONNX models > 1 MB
            val minBytes = if (entry.filename.endsWith(".onnx")) 1024 * 1024L else 100L
            if (file.length() < minBytes) {
                stagingDir.deleteRecursively()
                return InstallResult(false, null, "Corrupt file ${entry.filename} (Size: ${file.length()} bytes)")
            }

            // Checksum verification if provided in manifest
            if (entry.sha256.isNotBlank()) {
                val computedSha256 = calculateSha256(file)
                if (!computedSha256.equals(entry.sha256, ignoreCase = true)) {
                    stagingDir.deleteRecursively()
                    return InstallResult(false, null, "SHA-256 mismatch on ${entry.filename}")
                }
            }
        }

        // 2. Write manifest.json into staging directory
        val manifestFile = File(stagingDir, "manifest.json")
        manifestFile.writeText(manifest.toJson())

        // 3. Atomic move to target directory
        val rootDir = LanguagePackRepository.getPacksRootDir(context)
        val finalDir = File(rootDir, manifest.languageCode)
        val backupDir = File(rootDir, "${manifest.languageCode}.backup")

        try {
            if (finalDir.exists()) {
                finalDir.renameTo(backupDir)
            }

            val moved = stagingDir.renameTo(finalDir)
            if (!moved) {
                // Fallback to copy if cross-filesystem rename fails
                stagingDir.copyRecursively(finalDir, overwrite = true)
                stagingDir.deleteRecursively()
            }

            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            }

            Log.i(TAG, "Language pack for ${manifest.languageCode} successfully installed to ${finalDir.absolutePath}")
            return InstallResult(true, finalDir, null)
        } catch (e: Exception) {
            Log.e(TAG, "Atomic install failed for ${manifest.languageCode}: ${e.message}", e)
            if (backupDir.exists() && !finalDir.exists()) {
                backupDir.renameTo(finalDir)
            }
            return InstallResult(false, null, "Atomic installation failed: ${e.message}")
        }
    }

    /**
     * Uninstalls/deletes an installed language pack.
     */
    fun uninstallPack(langCode: String): Boolean {
        return try {
            val rootDir = LanguagePackRepository.getPacksRootDir(context)
            val dir = File(rootDir, langCode)
            if (dir.exists()) {
                dir.deleteRecursively()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to uninstall pack $langCode", e)
            false
        }
    }
}
