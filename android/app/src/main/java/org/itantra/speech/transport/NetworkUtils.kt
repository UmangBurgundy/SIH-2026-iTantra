package org.itantra.speech.transport

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Utility to discover active local IPv4 addresses dynamically on Android devices.
 *
 * Avoids hardcoded hotspot IP assumptions (such as 192.168.43.1) which can vary across
 * Android versions, device manufacturers, tethering configurations, and local Wi-Fi networks.
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Priority interface prefixes for Wi-Fi and Hotspot on Android.
     * Order of preference:
     * 1. wlan (Standard Wi-Fi STA / Hotspot)
     * 2. ap (Access Point / Hotspot mode on many chipsets)
     * 3. swlan (SoftAP / Virtual Wi-Fi)
     * 4. p2p (Wi-Fi Direct)
     * 5. rndis / eth (Tethering / Ethernet fallback)
     */
    private val INTERFACE_PRIORITIES = listOf("wlan", "ap", "swlan", "p2p", "rndis", "eth")

    /**
     * Discovers the actual usable non-loopback local IPv4 address.
     *
     * @return IPv4 address string (e.g. "192.168.43.1" or "192.168.1.105") or null if no active network.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val candidateIps = mutableListOf<Pair<String, String>>() // Pair<interfaceName, ipAddress>

            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            candidateIps.add(Pair(networkInterface.name.lowercase(), ip))
                        }
                    }
                }
            }

            if (candidateIps.isEmpty()) {
                Log.w(TAG, "No usable non-loopback IPv4 address found")
                return null
            }

            // Prioritize by interface name
            for (prefix in INTERFACE_PRIORITIES) {
                val match = candidateIps.firstOrNull { (name, _) -> name.startsWith(prefix) }
                if (match != null) {
                    Log.i(TAG, "Selected local IP ${match.second} from interface ${match.first}")
                    return match.second
                }
            }

            // Fallback to the first available candidate
            val fallback = candidateIps.first()
            Log.i(TAG, "Selected fallback local IP ${fallback.second} from interface ${fallback.first}")
            return fallback.second

        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating network interfaces", e)
            return null
        }
    }

    /**
     * Returns a map of all detected active non-loopback interfaces and their IPv4 addresses.
     */
    fun getAllLocalIps(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in Collections.list(networkInterface.inetAddresses)) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            result[networkInterface.name] = ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing all local IPs", e)
        }
        return result
    }
}
