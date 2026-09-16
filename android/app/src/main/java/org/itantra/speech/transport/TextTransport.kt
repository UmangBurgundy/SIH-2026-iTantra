package org.itantra.speech.transport

import org.itantra.speech.alert.AudioMessage

/**
 * High-level connection state for local network transports.
 */
enum class ConnectionState {
    DISCONNECTED,
    HOSTING,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Abstraction layer for text-only communication.
 * Keeps Wi-Fi (Phase 8.6) and future Bluetooth (Phase 8.7) strictly decoupled
 * from speech capture, STT, TTS, and the priority audio scheduler.
 */
interface TextTransport {
    val state: ConnectionState
    var onStateChanged: ((ConnectionState) -> Unit)?
    var onMessageReceived: ((AudioMessage, Long) -> Unit)? // (message, localReceiveTimestampNs)
    var onError: ((String) -> Unit)?

    /**
     * Starts listening as a host server on the specified port.
     */
    suspend fun startHost(port: Int = 8888)

    /**
     * Connects to a peer host at the specified IPv4 address and port.
     */
    suspend fun connectToHost(hostAddress: String, port: Int = 8888)

    /**
     * Transmits an AudioMessage to the connected peer.
     * Returns the sender transmission duration in milliseconds (T2 - T1).
     */
    suspend fun send(message: AudioMessage): Long

    /**
     * Sends a ping to measure round-trip transport latency.
     */
    suspend fun ping(): Long

    /**
     * Disconnects the active peer session and stops server listening.
     */
    fun disconnect()

    /**
     * Returns true if there is an active connected peer socket.
     */
    fun isConnected(): Boolean
}
