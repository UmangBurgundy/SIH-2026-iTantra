package org.itantra.speech.alert

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.itantra.speech.tts.TTSBackend
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Result data class for an alert audio cache lookup.
 */
data class CachedAlertAudio(
    val alertKey: String,
    val pcmData: ByteArray,
    val sampleRate: Int = 16000,
    val durationSec: Double,
    val isCacheHit: Boolean,
    val lookupLatencyMs: Long
)

/**
 * Two-tier offline persistent audio cache for emergency alert messages.
 *
 * Tier 1: In-memory ConcurrentHashMap for sub-millisecond instant retrieval.
 * Tier 2: Persistent internal disk storage (`context.filesDir/alert_cache/<key>.pcm`).
 */
class AlertAudioCache(private val context: Context) {

    companion object {
        private const val TAG = "iTantraAlertCache"
        private const val CACHE_SUBDIR = "alert_cache"
        const val SAMPLE_RATE = 16000
    }

    private val cacheDir: File by lazy {
        val dir = File(context.filesDir, CACHE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    // In-memory audio cache: key -> PCM byte array
    private val memoryCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Checks if a given alert key is present in memory or on disk.
     */
    fun contains(alertKey: String): Boolean {
        if (memoryCache.containsKey(alertKey)) return true
        val file = File(cacheDir, "${alertKey}.pcm")
        return file.exists() && file.length() > 0
    }

    /**
     * Deterministic cache lookup with high-resolution latency measurement.
     *
     * @param alertKey Key identifying the alert (e.g. alertId or text hash)
     * @return CachedAlertAudio if found in memory or disk, or null if cache miss
     */
    fun get(alertKey: String): CachedAlertAudio? {
        val t1 = SystemClock.elapsedRealtimeNanos()

        // 1. Check in-memory cache (Tier 1: < 1 ms)
        val memAudio = memoryCache[alertKey]
        if (memAudio != null) {
            val t2 = SystemClock.elapsedRealtimeNanos()
            val lookupLatencyMs = (t2 - t1) / 1_000_000
            val durationSec = memAudio.size.toDouble() / (SAMPLE_RATE * 2).toDouble()
            Log.i(TAG, "Cache HIT (Memory) for '$alertKey': ${memAudio.size} bytes (%.2fs) in ${lookupLatencyMs}ms".format(durationSec))
            return CachedAlertAudio(
                alertKey = alertKey,
                pcmData = memAudio,
                sampleRate = SAMPLE_RATE,
                durationSec = durationSec,
                isCacheHit = true,
                lookupLatencyMs = lookupLatencyMs
            )
        }

        // 2. Check persistent disk cache (Tier 2: ~2-5 ms)
        val file = File(cacheDir, "${alertKey}.pcm")
        if (file.exists() && file.length() > 0) {
            try {
                val bytes = file.readBytes()
                memoryCache[alertKey] = bytes
                val t2 = SystemClock.elapsedRealtimeNanos()
                val lookupLatencyMs = (t2 - t1) / 1_000_000
                val durationSec = bytes.size.toDouble() / (SAMPLE_RATE * 2).toDouble()
                Log.i(TAG, "Cache HIT (Disk) for '$alertKey': ${bytes.size} bytes (%.2fs) in ${lookupLatencyMs}ms".format(durationSec))
                return CachedAlertAudio(
                    alertKey = alertKey,
                    pcmData = bytes,
                    sampleRate = SAMPLE_RATE,
                    durationSec = durationSec,
                    isCacheHit = true,
                    lookupLatencyMs = lookupLatencyMs
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Failed reading disk cache file for '$alertKey': ${e.message}")
            }
        }

        val t2 = SystemClock.elapsedRealtimeNanos()
        val lookupLatencyMs = (t2 - t1) / 1_000_000
        Log.i(TAG, "Cache MISS for '$alertKey' in ${lookupLatencyMs}ms")
        return null
    }

    /**
     * Stores PCM audio into both in-memory cache and persistent disk storage.
     */
    fun put(alertKey: String, pcmData: ByteArray) {
        if (pcmData.isEmpty()) return

        // Tier 1: In-memory
        memoryCache[alertKey] = pcmData

        // Tier 2: Disk
        try {
            val file = File(cacheDir, "${alertKey}.pcm")
            FileOutputStream(file).use { it.write(pcmData) }
            Log.i(TAG, "Saved '${alertKey}' to disk cache (${pcmData.size} bytes at ${file.absolutePath})")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed saving alert audio to disk: ${e.message}")
        }
    }

    /**
     * Pre-synthesizes all catalog alerts using the provided TTSBackend if not already cached.
     *
     * Runs asynchronously on Dispatchers.IO.
     *
     * @param ttsBackend Initialized TTS backend
     * @param onProgress Callback with (completedCount, totalCount)
     * @return Number of newly pre-synthesized alerts
     */
    suspend fun preSynthesizeAlerts(
        ttsBackend: TTSBackend,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val alerts = PredefinedAlert.values()
        var newlySynthesized = 0

        Log.i(TAG, "Starting alert pre-synthesis check for ${alerts.size} predefined alerts...")

        for ((index, alert) in alerts.withIndex()) {
            val key = alert.alertId

            if (contains(key)) {
                // Ensure loaded in memory
                get(key)
                onProgress?.invoke(index + 1, alerts.size)
                continue
            }

            Log.i(TAG, "Pre-synthesizing alert '${alert.title}' [${alert.alertId}]...")
            val result = ttsBackend.synthesize(alert.hindiText)
            if (result.isSuccess && result.pcmData.isNotEmpty()) {
                put(key, result.pcmData)
                newlySynthesized++
                Log.i(TAG, "Successfully pre-synthesized and cached '${alert.title}' (${result.pcmData.size} bytes)")
            } else {
                Log.e(TAG, "Failed pre-synthesizing alert '${alert.title}': ${result.errorMessage}")
            }
            onProgress?.invoke(index + 1, alerts.size)
        }

        Log.i(TAG, "Pre-synthesis complete. Total cached: ${memoryCache.size}/${alerts.size} (New: $newlySynthesized)")
        newlySynthesized
    }

    /**
     * Number of items currently in the in-memory cache.
     */
    val cachedCount: Int get() = memoryCache.size

    /**
     * Total storage footprint in bytes of the persistent disk cache.
     */
    val totalDiskSizeBytes: Long
        get() {
            var size = 0L
            cacheDir.listFiles()?.forEach { if (it.isFile) size += it.length() }
            return size
        }
}
