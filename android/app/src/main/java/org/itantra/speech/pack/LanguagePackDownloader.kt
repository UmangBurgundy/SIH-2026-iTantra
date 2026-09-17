package org.itantra.speech.pack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads on-demand language pack components into a staging directory
 * with live progress callbacks, HTTP Range resume support, and cancellation safety.
 */
class LanguagePackDownloader(private val context: Context) {

    companion object {
        private const val TAG = "iTantraPackDownloader"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB chunk
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000
    }

    data class DownloadProgress(
        val languageCode: String,
        val currentFileName: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int
    )

    /**
     * Downloads all files specified in the manifest into the staging directory:
     * `language_packs/.partial/<langCode>/`
     */
    suspend fun downloadPack(
        manifest: LanguagePackManifest,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val rootDir = LanguagePackRepository.getPacksRootDir(context)
        val stagingDir = File(File(rootDir, LanguagePackRepository.PARTIAL_DOWNLOAD_DIR), manifest.languageCode)

        try {
            if (!stagingDir.exists()) stagingDir.mkdirs()

            val totalBytes = manifest.totalSizeBytes
            var totalBytesDownloaded = 0L

            // Calculate already downloaded bytes for resume
            manifest.allFiles.forEach { entry ->
                val targetFile = File(File(stagingDir, entry.relativeSubdir), entry.filename)
                if (targetFile.exists()) {
                    totalBytesDownloaded += targetFile.length()
                }
            }

            for ((index, entry) in manifest.allFiles.withIndex()) {
                if (!isActive) {
                    return@withContext Result.failure(Exception("Download cancelled by user"))
                }

                val subDir = File(stagingDir, entry.relativeSubdir)
                if (!subDir.exists()) subDir.mkdirs()

                val targetFile = File(subDir, entry.filename)
                val existingBytes = if (targetFile.exists()) targetFile.length() else 0L

                // If already fully downloaded, skip to next file
                if (existingBytes >= entry.sizeBytes && entry.sizeBytes > 0) {
                    Log.i(TAG, "File ${entry.filename} already downloaded (${existingBytes}B). Skipping.")
                    continue
                }

                downloadSingleFile(
                    urlStr = entry.downloadUrl,
                    destFile = targetFile,
                    expectedSize = entry.sizeBytes,
                    existingBytes = existingBytes
                ) { fileBytesRead ->
                    val overallDownloaded = (totalBytesDownloaded + fileBytesRead).coerceAtMost(totalBytes)
                    val percent = if (totalBytes > 0) ((overallDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                    onProgress(
                        DownloadProgress(
                            languageCode = manifest.languageCode,
                            currentFileName = entry.filename,
                            fileIndex = index + 1,
                            totalFiles = manifest.allFiles.size,
                            bytesDownloaded = overallDownloaded,
                            totalBytes = totalBytes,
                            percent = percent
                        )
                    )
                }

                totalBytesDownloaded += (targetFile.length() - existingBytes)
            }

            Log.i(TAG, "Language pack for ${manifest.languageCode} downloaded successfully to ${stagingDir.absolutePath}")
            Result.success(stagingDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed downloading pack for ${manifest.languageCode}: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun downloadSingleFile(
        urlStr: String,
        destFile: File,
        expectedSize: Long,
        existingBytes: Long,
        onFileProgress: (Long) -> Unit
    ) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true

        // HTTP Range resume header
        var isResuming = false
        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=$existingBytes-")
            isResuming = true
        }

        try {
            conn.connect()
            val responseCode = conn.responseCode

            val append = isResuming && (responseCode == HttpURLConnection.HTTP_PARTIAL)
            var currentBytes = if (append) existingBytes else 0L

            val inputStream: InputStream = conn.inputStream
            val outputStream = FileOutputStream(destFile, append)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int

            outputStream.use { out ->
                inputStream.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        currentBytes += bytesRead
                        onFileProgress(currentBytes)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Cleans up any staging partial downloads for a language.
     */
    fun cleanPartial(langCode: String) {
        try {
            val rootDir = LanguagePackRepository.getPacksRootDir(context)
            val stagingDir = File(File(rootDir, LanguagePackRepository.PARTIAL_DOWNLOAD_DIR), langCode)
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean partial download for $langCode", e)
        }
    }
}
