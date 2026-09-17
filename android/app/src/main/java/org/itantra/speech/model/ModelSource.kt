package org.itantra.speech.model

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Unified ModelSource abstraction for iTantra speech models.
 *
 * Unifies bundled APK assets (Hindi, English) and on-demand persistent filesystem
 * language packs (Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali).
 *
 * Provides safe direct filesystem resolution, avoiding redundant JVM byte array allocations
 * (`readBytes()`) when loading native ONNX Runtime or Sherpa-ONNX sessions.
 */
sealed class ModelSource {
    abstract val identifier: String
    abstract fun exists(context: Context): Boolean
    abstract fun openStream(context: Context): InputStream
    abstract fun getFilePathOrExtract(context: Context, cacheSubdir: String = "model_cache"): String

    /**
     * Represents a model bundled inside Android APK assets (e.g. `models/indic-hi.int8.onnx`).
     */
    data class Asset(val assetPath: String) : ModelSource() {
        override val identifier: String get() = "asset:$assetPath"

        override fun exists(context: Context): Boolean {
            return try {
                context.assets.open(assetPath).use { true }
            } catch (e: Exception) {
                false
            }
        }

        override fun openStream(context: Context): InputStream {
            return context.assets.open(assetPath)
        }

        /**
         * Resolves a direct filesystem path for native runtimes.
         * Extracts once to app-private cache only if necessary (verified by length),
         * allowing direct native memory-mapping instead of huge JVM byte[] allocations.
         */
        override fun getFilePathOrExtract(context: Context, cacheSubdir: String): String {
            val cacheDir = File(context.cacheDir, cacheSubdir).apply { if (!exists()) mkdirs() }
            val fileName = File(assetPath).name
            val cachedFile = File(cacheDir, fileName)

            // Validate if already extracted
            if (cachedFile.exists() && cachedFile.length() > 1024) {
                return cachedFile.absolutePath
            }

            Log.i("ModelSource", "Extracting asset $assetPath to private cache for zero-copy native loading: ${cachedFile.absolutePath}")
            val tempFile = File(cacheDir, "$fileName.tmp")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            tempFile.renameTo(cachedFile)
            return cachedFile.absolutePath
        }
    }

    /**
     * Represents a model stored in persistent app-private storage
     * (e.g. `files/language_packs/<lang>/stt/indic-<lang>.int8.onnx`).
     */
    data class FileSystem(val file: File) : ModelSource() {
        override val identifier: String get() = "file:${file.absolutePath}"

        override fun exists(context: Context): Boolean = file.exists() && file.length() > 100

        override fun openStream(context: Context): InputStream = file.inputStream()

        override fun getFilePathOrExtract(context: Context, cacheSubdir: String): String = file.absolutePath
    }

    companion object {
        /**
         * Resolves a ModelSource from either a filesystem directory or an asset path fallback.
         */
        fun resolve(
            modelDir: String?,
            subDir: String,
            fileNamePrefix: String,
            extension: String,
            fallbackAssetPath: String
        ): ModelSource {
            if (modelDir != null) {
                val dir = File(modelDir)
                val targetSubdir = File(dir, subDir)
                val searchDir = if (targetSubdir.exists()) targetSubdir else dir
                val match = searchDir.listFiles { _, name ->
                    name.startsWith(fileNamePrefix) && name.endsWith(extension)
                }?.firstOrNull() ?: File(searchDir, "$fileNamePrefix$extension")

                if (match.exists()) {
                    return FileSystem(match)
                }
            }
            return Asset(fallbackAssetPath)
        }
    }
}
