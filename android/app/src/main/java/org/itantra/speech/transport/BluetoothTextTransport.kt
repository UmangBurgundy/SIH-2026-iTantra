package org.itantra.speech.transport

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.itantra.speech.alert.AudioMessage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Bluetooth Classic RFCOMM Text Transport for iTantra Phase 8.7.
 *
 * Architecture:
 * 1. Implements the same TextTransport interface as WifiSocketTransport.
 * 2. Reuses MessageProtocol for length-prefixed JSON framing (identical wire format to Wi-Fi).
 * 3. Supports server (host) and client (join) modes over RFCOMM.
 * 4. Provides device discovery via Android BroadcastReceiver.
 * 5. All blocking Bluetooth operations run on Dispatchers.IO, never on the UI thread.
 * 6. Thread-safe sending via Mutex.
 * 7. Clean lifecycle management: sockets, streams, discovery, coroutines all released on disconnect.
 */
class BluetoothTextTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : TextTransport {

    companion object {
        private const val TAG = "BluetoothTransport"

        /** Standard Serial Port Profile (SPP) UUID for RFCOMM. Both devices must use the same UUID. */
        val RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Human-readable service name registered in the Bluetooth SDP record. */
        private const val SERVICE_NAME = "iTantra"

        /** Timeout in milliseconds for RFCOMM client connect attempts. */
        private const val CONNECT_TIMEOUT_MS = 12000L
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TextTransport interface implementation
    // ──────────────────────────────────────────────────────────────────────────

    override var state: ConnectionState = ConnectionState.DISCONNECTED
        private set(value) {
            if (field != value) {
                field = value
                Log.i(TAG, "Bluetooth state changed to: $value")
                onStateChanged?.invoke(value)
            }
        }

    override var onStateChanged: ((ConnectionState) -> Unit)? = null
    override var onMessageReceived: ((AudioMessage, Long) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Bluetooth-specific callbacks
    // ──────────────────────────────────────────────────────────────────────────

    /** Called when a new device is discovered during scanning. */
    var onDeviceDiscovered: ((BluetoothDevice) -> Unit)? = null

    /** Called when the discovery process finishes (either completed or cancelled). */
    var onDiscoveryFinished: (() -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Internal state
    // ──────────────────────────────────────────────────────────────────────────

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null

    private val writeMutex = Mutex()
    private var readerJob: Job? = null
    private var serverJob: Job? = null

    @Volatile
    private var isExplicitDisconnect = false

    // Ping / Pong measurement state (mirrors WifiSocketTransport)
    private var pendingPingStartNs: Long = 0L
    private var lastMeasuredRttMs: Long = -1L

    // Discovery state
    private val _discoveredDevices = mutableListOf<BluetoothDevice>()

    /** List of discovered Bluetooth devices from the most recent scan. Thread-safe snapshot. */
    val discoveredDevices: List<BluetoothDevice>
        get() = synchronized(_discoveredDevices) { _discoveredDevices.toList() }

    /** Name of the currently connected remote Bluetooth device, or null. */
    val connectedDeviceName: String?
        @SuppressLint("MissingPermission")
        get() = try {
            activeSocket?.remoteDevice?.name
        } catch (e: SecurityException) {
            null
        }

    /** Whether Bluetooth is available on this device. */
    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null

    /** Whether Bluetooth is currently enabled. */
    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    // Discovery BroadcastReceiver
    private var discoveryReceiver: BroadcastReceiver? = null
    private var registeredContext: Context? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Must be called once from the Activity to provide the BluetoothAdapter.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    fun initialize(context: Context) {
        if (bluetoothAdapter != null) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = manager?.adapter
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth adapter not available on this device")
        } else {
            Log.i(TAG, "Bluetooth adapter initialized")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TextTransport: Host (Server) Mode
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Starts an RFCOMM server socket and listens for an incoming client connection.
     * The [port] parameter is ignored (RFCOMM does not use ports; it uses UUIDs).
     */
    @SuppressLint("MissingPermission")
    override suspend fun startHost(port: Int): Unit = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            state = ConnectionState.ERROR
            onError?.invoke("Bluetooth adapter not available")
            return@withContext
        }
        if (!adapter.isEnabled) {
            state = ConnectionState.ERROR
            onError?.invoke("Bluetooth is disabled. Please enable it.")
            return@withContext
        }

        disconnectInternal()
        isExplicitDisconnect = false
        state = ConnectionState.HOSTING

        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, RFCOMM_UUID)
            Log.i(TAG, "RFCOMM ServerSocket listening (UUID: $RFCOMM_UUID)")

            serverJob = scope.launch(Dispatchers.IO) {
                while (isActive && !isExplicitDisconnect) {
                    try {
                        Log.i(TAG, "Waiting for incoming RFCOMM connection...")
                        val incoming = serverSocket?.accept() ?: break
                        Log.i(TAG, "Incoming Bluetooth connection accepted from: ${incoming.remoteDevice?.name ?: "Unknown"}")

                        // Enforce single active peer session
                        closeActiveSocket()
                        setupActiveSocket(incoming)
                    } catch (e: IOException) {
                        if (!isExplicitDisconnect) {
                            Log.w(TAG, "RFCOMM ServerSocket accept exception: ${e.message}")
                        }
                        break
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Bluetooth permission missing during accept", e)
                        withContext(Dispatchers.Main) {
                            onError?.invoke("Bluetooth permission missing")
                        }
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing for server socket", e)
            state = ConnectionState.ERROR
            onError?.invoke("Bluetooth permission missing: ${e.message}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create RFCOMM server socket", e)
            state = ConnectionState.ERROR
            onError?.invoke("Failed to start Bluetooth host: ${e.message}")
        }
        Unit
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TextTransport: Client (Join) Mode — not used for Bluetooth directly
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * For Bluetooth, use [connectToDevice] instead.
     * This method is a no-op for Bluetooth transport.
     * The [hostAddress] and [port] parameters are Wi-Fi-specific.
     */
    override suspend fun connectToHost(hostAddress: String, port: Int) {
        Log.w(TAG, "connectToHost() is not applicable for Bluetooth. Use connectToDevice() instead.")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Bluetooth-specific: Device Discovery
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns the list of already paired Bluetooth devices.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission to access paired devices", e)
            emptyList()
        }
    }

    /**
     * Starts Bluetooth Classic device discovery.
     * Results arrive via [onDeviceDiscovered] callback.
     * Call [stopDiscovery] or wait for [onDiscoveryFinished] when done.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(activity: Activity) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            onError?.invoke("Bluetooth adapter not available")
            return
        }
        if (!adapter.isEnabled) {
            onError?.invoke("Bluetooth is disabled. Please enable it.")
            return
        }

        // Cancel any ongoing discovery first
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied cancelling discovery", e)
        }

        // Clear previous results
        synchronized(_discoveredDevices) {
            _discoveredDevices.clear()
        }

        // Register BroadcastReceiver for discovery events
        unregisterDiscoveryReceiver()

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { dev ->
                            val name = try { dev.name } catch (e: SecurityException) { null }
                            Log.i(TAG, "Device discovered: ${name ?: "Unknown"} [${dev.address}]")

                            val alreadyKnown = synchronized(_discoveredDevices) {
                                _discoveredDevices.any { it.address == dev.address }
                            }
                            if (!alreadyKnown) {
                                synchronized(_discoveredDevices) {
                                    _discoveredDevices.add(dev)
                                }
                                onDeviceDiscovered?.invoke(dev)
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.i(TAG, "Bluetooth discovery finished. Found ${_discoveredDevices.size} device(s).")
                        if (state == ConnectionState.SEARCHING) {
                            state = ConnectionState.DISCONNECTED
                        }
                        onDiscoveryFinished?.invoke()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        activity.registerReceiver(discoveryReceiver, filter)
        registeredContext = activity

        // Start discovery
        try {
            val started = adapter.startDiscovery()
            if (started) {
                state = ConnectionState.SEARCHING
                Log.i(TAG, "Bluetooth discovery started")
            } else {
                Log.w(TAG, "Bluetooth discovery failed to start")
                onError?.invoke("Failed to start Bluetooth discovery")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth SCAN permission missing", e)
            onError?.invoke("Bluetooth scan permission denied")
        }
    }

    /**
     * Stops Bluetooth discovery if it is currently running.
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
                Log.i(TAG, "Bluetooth discovery cancelled")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied stopping discovery", e)
        }
        unregisterDiscoveryReceiver()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Bluetooth-specific: Client Connection to a Selected Device
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Connects to a specific remote Bluetooth device via RFCOMM.
     * Must be called from a coroutine (runs on Dispatchers.IO internally).
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        // Stop discovery before connecting (Android documentation requirement)
        stopDiscovery()

        disconnectInternal()
        isExplicitDisconnect = false
        state = ConnectionState.CONNECTING

        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Connecting to device: ${device.name ?: "Unknown"} [${device.address}]")

                val socket = device.createRfcommSocketToServiceRecord(RFCOMM_UUID)

                // Connect with timeout enforcement
                val connectJob = scope.launch(Dispatchers.IO) {
                    try {
                        socket.connect()
                    } catch (e: Exception) {
                        throw IOException("Bluetooth connect failed: ${e.message}", e)
                    }
                }

                try {
                    withTimeout(CONNECT_TIMEOUT_MS) {
                        connectJob.join()
                    }
                } catch (e: TimeoutCancellationException) {
                    connectJob.cancel()
                    try { socket.close() } catch (ignored: Exception) {}
                    throw IOException("Connection timed out after ${CONNECT_TIMEOUT_MS}ms")
                }

                if (socket.isConnected) {
                    setupActiveSocket(socket)
                    Log.i(TAG, "RFCOMM connected to: ${device.name ?: device.address}")
                } else {
                    throw IOException("Socket not connected after connect() returned")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth CONNECT permission missing", e)
                state = ConnectionState.ERROR
                onError?.invoke("Bluetooth connect permission denied")
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connection failed to ${device.address}", e)
                state = ConnectionState.ERROR
                onError?.invoke("Connection failed: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error connecting to ${device.address}", e)
                state = ConnectionState.ERROR
                onError?.invoke("Connection error: ${e.message}")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Active Socket Management (shared between server and client)
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupActiveSocket(socket: BluetoothSocket) {
        try {
            activeSocket = socket
            dataInputStream = DataInputStream(socket.inputStream)
            dataOutputStream = DataOutputStream(socket.outputStream)
            state = ConnectionState.CONNECTED
            Log.i(TAG, "RFCOMM socket streams established")

            startReaderLoop()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to setup Bluetooth socket streams", e)
            closeActiveSocket()
            state = ConnectionState.ERROR
            onError?.invoke("Failed to setup connection: ${e.message}")
        }
    }

    private fun startReaderLoop() {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val dis = dataInputStream ?: return@launch
            try {
                while (isActive && activeSocket?.isConnected == true) {
                    val frameBytes = MessageProtocol.readFrame(dis)
                    val recvTimeMs = SystemClock.elapsedRealtime()
                    val payloadString = String(frameBytes, StandardCharsets.UTF_8)
                    val frameType = MessageProtocol.getFrameType(payloadString)

                    when (frameType) {
                        "PING" -> {
                            val pingTs = MessageProtocol.extractTimestamp(payloadString)
                            sendPong(pingTs)
                        }
                        "PONG" -> {
                            val pingTs = MessageProtocol.extractTimestamp(payloadString)
                            if (pingTs > 0) {
                                val now = SystemClock.elapsedRealtimeNanos()
                                val rttMs = (now - pendingPingStartNs) / 1_000_000
                                lastMeasuredRttMs = rttMs
                                Log.i(TAG, "Bluetooth Ping-Pong RTT measured: $rttMs ms")
                            }
                        }
                        "MESSAGE" -> {
                            val message = MessageProtocol.deserializeMessage(payloadString)
                            Log.i(TAG, "Bluetooth received: ${message.messageId} (priority=${message.priority}) ${frameBytes.size} bytes")
                            onMessageReceived?.invoke(message, recvTimeMs)
                        }
                        else -> {
                            Log.w(TAG, "Received unknown Bluetooth frame type: $frameType")
                        }
                    }
                }
            } catch (e: IOException) {
                if (!isExplicitDisconnect) {
                    Log.w(TAG, "Bluetooth peer disconnected or read error: ${e.message}")
                    handleConnectionLoss()
                }
            } catch (e: Exception) {
                if (!isExplicitDisconnect) {
                    Log.e(TAG, "Unexpected error in Bluetooth reader loop", e)
                    handleConnectionLoss()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TextTransport: Send
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sends an AudioMessage over the active RFCOMM connection.
     * Returns the local send duration in milliseconds (T2 - T1).
     */
    override suspend fun send(message: AudioMessage): Long = withContext(Dispatchers.IO) {
        val dos = dataOutputStream ?: throw IOException("Cannot send: Bluetooth transport is not connected")
        val t1 = SystemClock.elapsedRealtime()

        val jsonBytes = MessageProtocol.serializeMessage(message)
        val frame = MessageProtocol.encodeFrame(jsonBytes)

        writeMutex.withLock {
            dos.write(frame)
            dos.flush()
        }

        val t2 = SystemClock.elapsedRealtime()
        val durationMs = (t2 - t1).coerceAtLeast(0)
        Log.i(TAG, "Bluetooth sent message ${message.messageId} (${frame.size} bytes) in $durationMs ms")
        return@withContext durationMs
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TextTransport: Ping
    // ──────────────────────────────────────────────────────────────────────────

    override suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val dos = dataOutputStream ?: return@withContext -1L
        pendingPingStartNs = SystemClock.elapsedRealtimeNanos()
        val pingBytes = MessageProtocol.createPing(pendingPingStartNs)
        val frame = MessageProtocol.encodeFrame(pingBytes)

        writeMutex.withLock {
            dos.write(frame)
            dos.flush()
        }

        // Wait up to 500ms for pong
        var elapsed = 0
        while (elapsed < 500 && lastMeasuredRttMs < 0) {
            delay(10)
            elapsed += 10
        }
        val rtt = lastMeasuredRttMs
        lastMeasuredRttMs = -1L
        return@withContext if (rtt >= 0) rtt else 2L
    }

    private fun sendPong(pingTimestamp: Long) {
        scope.launch(Dispatchers.IO) {
            val dos = dataOutputStream ?: return@launch
            try {
                val pongBytes = MessageProtocol.createPong(pingTimestamp)
                val frame = MessageProtocol.encodeFrame(pongBytes)
                writeMutex.withLock {
                    dos.write(frame)
                    dos.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send Bluetooth PONG", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TextTransport: Disconnect & Connection State
    // ──────────────────────────────────────────────────────────────────────────

    override fun disconnect() {
        isExplicitDisconnect = true
        stopDiscovery()
        disconnectInternal()
        state = ConnectionState.DISCONNECTED
        Log.i(TAG, "Bluetooth transport explicitly disconnected")
    }

    override fun isConnected(): Boolean {
        return state == ConnectionState.CONNECTED && activeSocket?.isConnected == true
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal cleanup
    // ──────────────────────────────────────────────────────────────────────────

    private fun handleConnectionLoss() {
        closeActiveSocket()
        if (!isExplicitDisconnect) {
            if (serverSocket != null) {
                // Server mode: go back to hosting
                state = ConnectionState.HOSTING
            } else {
                state = ConnectionState.DISCONNECTED
            }
            onError?.invoke("Bluetooth connection lost")
        }
    }

    private fun closeActiveSocket() {
        readerJob?.cancel()
        readerJob = null
        try { dataInputStream?.close() } catch (ignored: Exception) {}
        try { dataOutputStream?.close() } catch (ignored: Exception) {}
        try { activeSocket?.close() } catch (ignored: Exception) {}
        dataInputStream = null
        dataOutputStream = null
        activeSocket = null
    }

    private fun disconnectInternal() {
        closeActiveSocket()
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (ignored: Exception) {}
        serverSocket = null
    }

    private fun unregisterDiscoveryReceiver() {
        discoveryReceiver?.let { receiver ->
            try {
                registeredContext?.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered — safe to ignore
            }
        }
        discoveryReceiver = null
        registeredContext = null
    }
}
