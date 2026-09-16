package org.itantra.speech.utils

import android.os.Debug
import android.util.Log

/**
 * Monitors and logs physical process memory metrics on Android hardware.
 *
 * Distinguishes:
 * - Java Heap Used (MB)
 * - Native C++ Heap Used (MB)
 * - Total PSS (Proportional Set Size in MB)
 */
object MemoryMonitor {

    private const val TAG = "iTantraRAM"

    data class MemorySnapshot(
        val totalPssMb: Double,
        val nativeHeapMb: Double,
        val javaHeapMb: Double,
        val stateLabel: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        override fun toString(): String =
            "[$stateLabel] PSS: %.1f MB | Native: %.1f MB | Java: %.1f MB".format(totalPssMb, nativeHeapMb, javaHeapMb)
    }

    private var peakPssMb: Double = 0.0

    fun getMemorySnapshot(stateLabel: String): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / (1024 * 1024)
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize().toDouble() / (1024 * 1024)

        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        val totalPssMb = memInfo.totalPss.toDouble() / 1024.0

        if (totalPssMb > peakPssMb) {
            peakPssMb = totalPssMb
        }

        val snapshot = MemorySnapshot(
            totalPssMb = totalPssMb,
            nativeHeapMb = nativeHeapMb,
            javaHeapMb = javaHeapMb,
            stateLabel = stateLabel
        )

        Log.i(TAG, snapshot.toString())
        return snapshot
    }

    fun getPeakMemoryMb(): Double = peakPssMb
}
