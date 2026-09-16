package org.itantra.speech.model

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Validates on-device model file integrity, size, and existence
 * before instantiating native C++ inference runtimes.
 */
object ModelValidator {

    private const val TAG = "iTantraModelVal"

    data class ValidationResult(
        val isValid: Boolean,
        val message: String,
        val totalSizeBytes: Long = 0
    )

    /**
     * Checks whether all required model files exist in the target directory
     * and meet minimum file size sanity checks.
     */
    fun validateModelDirectory(modelDir: File, requiredFiles: List<String>): ValidationResult {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return ValidationResult(false, "Directory does not exist: ${modelDir.absolutePath}")
        }

        var totalSize = 0L
        for (filename in requiredFiles) {
            val file = File(modelDir, filename)
            if (!file.exists()) {
                return ValidationResult(false, "Missing required model file: $filename")
            }
            if (file.length() < 1024) { // Less than 1 KB is an invalid model file
                return ValidationResult(false, "Corrupt or empty model file: $filename (${file.length()} bytes)")
            }
            totalSize += file.length()
        }

        val sizeMb = totalSize.toDouble() / (1024 * 1024)
        Log.i(TAG, "Model validation passed: ${requiredFiles.size} files verified, total size: %.2f MB".format(sizeMb))
        return ValidationResult(true, "Validation passed (%.2f MB)".format(sizeMb), totalSize)
    }

    /**
     * Checks whether model assets are bundled in the APK assets/ directory.
     */
    fun checkAssetAvailability(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use {
                it.available() > 0
            }
        } catch (e: Exception) {
            false
        }
    }
}
