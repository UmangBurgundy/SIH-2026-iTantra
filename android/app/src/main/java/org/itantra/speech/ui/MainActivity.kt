package org.itantra.speech.ui

import org.itantra.speech.R
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import android.widget.EditText
import org.itantra.speech.alert.AlertAudioCache
import org.itantra.speech.alert.AlertTelemetry
import org.itantra.speech.alert.AudioMessage
import org.itantra.speech.alert.AudioPriority
import org.itantra.speech.alert.PlaybackState
import org.itantra.speech.alert.PredefinedAlert
import org.itantra.speech.alert.PriorityAudioScheduler
import org.itantra.speech.audio.AudioPreprocessor
import org.itantra.speech.audio.AudioRecorder
import org.itantra.speech.audio.AudioTrackPlayer
import org.itantra.speech.model.LanguageManager
import org.itantra.speech.stt.STTBackend
import org.itantra.speech.stt.SherpaOnnxSTTBackend
import org.itantra.speech.stt.IndicSTTBackend
import org.itantra.speech.tts.TTSBackend
import org.itantra.speech.tts.HindiMmsTTSBackend
import org.itantra.speech.transport.BluetoothTextTransport
import org.itantra.speech.transport.ConnectionState
import org.itantra.speech.transport.MessageProtocol
import org.itantra.speech.transport.NetworkUtils
import org.itantra.speech.transport.TextTransport
import org.itantra.speech.transport.WifiSocketTransport
import org.itantra.speech.utils.MemoryMonitor
import org.itantra.speech.vad.DualGateVad
import org.itantra.speech.vad.UtteranceSegmenter
import org.itantra.speech.interaction.PttStateMachine
import org.itantra.speech.interaction.ContinuousConversationController
import org.itantra.speech.pack.LanguagePackManifest
import org.itantra.speech.pack.LanguagePackRepository
import org.itantra.speech.pack.LanguagePackDownloader
import org.itantra.speech.pack.LanguagePackInstaller
import android.app.AlertDialog
import android.app.ProgressDialog
import android.view.MotionEvent

/**
 * Main Activity for iTantra Phase 8 speech communication testbed.
 *
 * Micro-pipeline:
 * AudioRecord Thread -> Frame Queue -> VAD Worker -> Utterance Segmenter -> STT Worker -> UI
 * Text Input -> HindiTextNormalizer -> HindiMmsTTSBackend (ONNX INT8) -> AudioTrackPlayer -> Speaker
 * Emergency Alerts -> AlertAudioCache (Pre-Synthesized) -> PriorityAudioScheduler -> AudioTrackPlayer
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "iTantraMain"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 101
        private const val PERMISSION_REQUEST_BLUETOOTH = 102
        private const val REQUEST_ENABLE_BT = 103
    }

    // UI elements
    private lateinit var spinnerLanguage: Spinner
    private lateinit var btnManagePacks: Button
    private var languageSpinnerAdapter: ArrayAdapter<LanguageManager.LanguageInfo>? = null
    private var previousLanguagePosition = 0
    private lateinit var tvEngineStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvMicStatus: TextView
    private lateinit var btnToggleListening: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvMetricLatency: TextView
    private lateinit var tvMetricRtf: TextView
    private lateinit var tvMetricAudioDuration: TextView
    private lateinit var tvMetricRam: TextView
    private lateinit var tvMetricNoiseFloor: TextView
    private lateinit var tvMetricSpeechThreshold: TextView
    private lateinit var tvLog: TextView

    // TTS UI elements
    private lateinit var tvTtsModelStatus: TextView
    private lateinit var etTtsInput: EditText
    private lateinit var btnTtsSpeak: Button
    private lateinit var btnTtsSpeakTranscript: Button
    private lateinit var btnTtsStop: Button
    private lateinit var tvTtsMetricLatency: TextView
    private lateinit var tvTtsMetricPlaybackDelay: TextView
    private lateinit var tvTtsMetricTotal: TextView
    private lateinit var tvTtsMetricRtf: TextView

    // Alert UI Elements (Phase 8.5)
    private lateinit var tvSchedulerState: TextView
    private lateinit var btnAlertFire: Button
    private lateinit var btnAlertMedical: Button
    private lateinit var btnAlertHazard: Button
    private lateinit var btnAlertEmergency: Button
    private lateinit var btnTestInterruption: Button
    private lateinit var tvAlertMetricLookup: TextView
    private lateinit var tvAlertMetricPrep: TextView
    private lateinit var tvAlertMetricTotal: TextView
    private lateinit var tvAlertMetricStatus: TextView

    // Core Pipeline Components
    private val languageManager = LanguageManager()
    private val audioRecorder = AudioRecorder()
    private val audioPreprocessor = AudioPreprocessor()
    private val dualGateVad = DualGateVad()
    private lateinit var utteranceSegmenter: UtteranceSegmenter
    private var sttBackend: STTBackend = IndicSTTBackend()

    // TTS Components
    private var ttsBackend: TTSBackend = HindiMmsTTSBackend()
    private val audioTrackPlayer = AudioTrackPlayer()
    private var wasRecordingBeforePlayback = false

    // Priority Alert Components (Phase 8.5)
    private lateinit var alertAudioCache: AlertAudioCache
    private lateinit var priorityScheduler: PriorityAudioScheduler

    // Wi-Fi Transport Components (Phase 8.6)
    private lateinit var tvWifiConnectionStatus: TextView
    private lateinit var tvLocalIp: TextView
    private lateinit var btnWifiHost: Button
    private lateinit var etPeerIp: EditText
    private lateinit var btnWifiJoin: Button
    private lateinit var btnWifiDisconnect: Button
    private lateinit var switchAutoSendStt: androidx.appcompat.widget.SwitchCompat
    private lateinit var btnWifiSendTest: Button
    private lateinit var btnWifiSendAlert: Button
    private lateinit var tvWifiMetricPayload: TextView
    private lateinit var tvWifiMetricLatency: TextView
    private lateinit var tvWifiChatLog: TextView
    private lateinit var wifiTransport: WifiSocketTransport

    // Bluetooth Transport Components (Phase 8.7)
    private lateinit var bluetoothTransport: BluetoothTextTransport
    private lateinit var rgTransportMode: RadioGroup
    private lateinit var tvBtConnectionStatus: TextView
    private lateinit var tvBtConnectedDevice: TextView
    private lateinit var btnBtHost: Button
    private lateinit var btnBtDiscover: Button
    private lateinit var btnBtDisconnect: Button
    private lateinit var layoutBtDeviceList: LinearLayout
    private lateinit var tvBtDeviceListHeader: TextView
    private lateinit var btnBtSendTest: Button
    private lateinit var btnBtSendAlert: Button
    private lateinit var tvBtMetricPayload: TextView
    private lateinit var tvBtMetricLatency: TextView
    private lateinit var tvBtChatLog: TextView

    /** Points to whichever transport is currently active (Wi-Fi or Bluetooth). */
    private var activeTransport: TextTransport? = null

    /** True when Bluetooth is the selected transport mode. */
    private var isBluetoothModeActive = false

    // Voice Input Modes (Phase 8.8 & 8.9)
    enum class VoiceInteractionMode {
        PUSH_TO_TALK,
        AUTO_TRANSMIT,
        CONTINUOUS_CONVERSATION
    }
    private var activeVoiceMode = VoiceInteractionMode.PUSH_TO_TALK

    // Push-to-Talk Components (Phase 8.8)
    private val pttStateMachine = PttStateMachine()
    private var isPttModeActive = true
    private var isPttUtterancePending = false

    // Continuous Conversation Components (Phase 8.9)
    private lateinit var conversationController: ContinuousConversationController
    private lateinit var rbModeContinuous: RadioButton

    private lateinit var rgVoiceMode: RadioGroup
    private lateinit var rbModePtt: RadioButton
    private lateinit var rbModeAuto: RadioButton
    private lateinit var tvPttTransportStatus: TextView
    private lateinit var tvPttTurnIndicator: TextView
    private lateinit var tvPttStateBanner: TextView
    private lateinit var btnPushToTalk: Button
    private lateinit var tvPttHint: TextView
    private lateinit var tvPttConversationLog: TextView
    private lateinit var btnPttClearLog: Button

    private var pipelineJob: Job? = null
    private var lastVadUiUpdateMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MemoryMonitor.getMemorySnapshot("App Launched")

        dualGateVad.initialize(applicationContext)
        utteranceSegmenter = UtteranceSegmenter(vad = dualGateVad)

        alertAudioCache = AlertAudioCache(applicationContext)
        priorityScheduler = PriorityAudioScheduler(
            context = applicationContext,
            audioCache = alertAudioCache,
            ttsBackend = ttsBackend,
            audioTrackPlayer = audioTrackPlayer
        )

        wifiTransport = WifiSocketTransport(lifecycleScope)
        bluetoothTransport = BluetoothTextTransport(lifecycleScope)
        bluetoothTransport.initialize(applicationContext)
        activeTransport = wifiTransport

        conversationController = ContinuousConversationController(lifecycleScope, settlingDelayMs = 200L)

        initViews()
        setupLanguageSpinner()
        checkPermissions()
        initializeSpeechModelsSequentially()
        setupPipelineCallbacks()
        setupSchedulerCallbacks()
        setupWifiCallbacks()
        setupBluetoothCallbacks()
        setupPttCallbacks()
        setupConversationCallbacks()
        setupPttTouchListener()
    }

    private fun initViews() {
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        btnManagePacks = findViewById(R.id.btnManagePacks)
        btnManagePacks.setOnClickListener {
            showLanguagePacksManagerDialog()
        }
        tvEngineStatus = findViewById(R.id.tvEngineStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvMicStatus = findViewById(R.id.tvMicStatus)
        btnToggleListening = findViewById(R.id.btnToggleListening)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvMetricLatency = findViewById(R.id.tvMetricLatency)
        tvMetricRtf = findViewById(R.id.tvMetricRtf)
        tvMetricAudioDuration = findViewById(R.id.tvMetricAudioDuration)
        tvMetricRam = findViewById(R.id.tvMetricRam)
        tvMetricNoiseFloor = findViewById(R.id.tvMetricNoiseFloor)
        tvMetricSpeechThreshold = findViewById(R.id.tvMetricSpeechThreshold)
        tvLog = findViewById(R.id.tvLog)
        val btnClearTranscript: Button = findViewById(R.id.btnClearTranscript)

        // Voice Interaction UI bindings (Phase 8.8 & 8.9)
        rgVoiceMode = findViewById(R.id.rgVoiceMode)
        rbModePtt = findViewById(R.id.rbModePtt)
        rbModeAuto = findViewById(R.id.rbModeAuto)
        rbModeContinuous = findViewById(R.id.rbModeContinuous)
        tvPttTransportStatus = findViewById(R.id.tvPttTransportStatus)
        tvPttTurnIndicator = findViewById(R.id.tvPttTurnIndicator)
        tvPttStateBanner = findViewById(R.id.tvPttStateBanner)
        btnPushToTalk = findViewById(R.id.btnPushToTalk)
        tvPttHint = findViewById(R.id.tvPttHint)
        tvPttConversationLog = findViewById(R.id.tvPttConversationLog)
        btnPttClearLog = findViewById(R.id.btnPttClearLog)

        btnPttClearLog.setOnClickListener {
            tvPttConversationLog.text = "Dialogue is empty. Hold button to speak."
        }

        rgVoiceMode.setOnCheckedChangeListener { _, checkedId ->
            // Safely stop any active interaction mode before switching
            if (conversationController.isConversationActive) {
                conversationController.stopConversation()
            }
            if (audioRecorder.isActive) {
                stopListening()
            }

            when (checkedId) {
                R.id.rbModePtt -> {
                    activeVoiceMode = VoiceInteractionMode.PUSH_TO_TALK
                    isPttModeActive = true
                    btnToggleListening.visibility = View.GONE
                    btnPushToTalk.visibility = View.VISIBLE
                    tvPttHint.text = getString(R.string.ptt_hint_hold)
                    updatePttUi(pttStateMachine.state, pttStateMachine.turn)
                    Log.i(TAG, "Voice interaction mode: Push-to-Talk")
                }
                R.id.rbModeAuto -> {
                    activeVoiceMode = VoiceInteractionMode.AUTO_TRANSMIT
                    isPttModeActive = false
                    btnToggleListening.visibility = View.VISIBLE
                    btnPushToTalk.visibility = View.GONE
                    tvPttHint.text = getString(R.string.ptt_hint_auto)
                    Log.i(TAG, "Voice interaction mode: Auto-Transmit")
                }
                R.id.rbModeContinuous -> {
                    activeVoiceMode = VoiceInteractionMode.CONTINUOUS_CONVERSATION
                    isPttModeActive = false
                    btnToggleListening.visibility = View.GONE
                    btnPushToTalk.visibility = View.VISIBLE
                    tvPttHint.text = getString(R.string.conv_hint)
                    updateContinuousUi(conversationController.state, conversationController.turn)
                    Log.i(TAG, "Voice interaction mode: Continuous Conversation")
                }
            }
        }
        // Initially PTT mode is primary
        btnToggleListening.visibility = View.GONE

        // TTS UI bindings
        tvTtsModelStatus = findViewById(R.id.tvTtsModelStatus)
        etTtsInput = findViewById(R.id.etTtsInput)
        btnTtsSpeak = findViewById(R.id.btnTtsSpeak)
        btnTtsSpeakTranscript = findViewById(R.id.btnTtsSpeakTranscript)
        btnTtsStop = findViewById(R.id.btnTtsStop)
        tvTtsMetricLatency = findViewById(R.id.tvTtsMetricLatency)
        tvTtsMetricPlaybackDelay = findViewById(R.id.tvTtsMetricPlaybackDelay)
        tvTtsMetricTotal = findViewById(R.id.tvTtsMetricTotal)
        tvTtsMetricRtf = findViewById(R.id.tvTtsMetricRtf)

        val btnQuick1: Button = findViewById(R.id.btnQuickPhrase1)
        val btnQuick2: Button = findViewById(R.id.btnQuickPhrase2)
        val btnQuick3: Button = findViewById(R.id.btnQuickPhrase3)

        btnQuick1.setOnClickListener { etTtsInput.setText("नमस्ते आप कैसे हैं") }
        btnQuick2.setOnClickListener { etTtsInput.setText("कृपया मेरी सहायता करें") }
        btnQuick3.setOnClickListener { etTtsInput.setText("आपातकालीन स्थिति है तुरंत मदद भेजें") }

        btnTtsSpeak.setOnClickListener {
            val text = etTtsInput.text.toString().trim()
            if (text.isNotEmpty()) {
                synthesizeAndPlay(text)
            } else {
                Toast.makeText(this, "कृपया बोलने के लिए टेक्स्ट दर्ज करें", Toast.LENGTH_SHORT).show()
            }
        }

        btnTtsSpeakTranscript.setOnClickListener {
            val text = tvTranscript.text.toString().trim()
            val placeholder = getString(R.string.default_transcript_placeholder).trim()
            if (text.isNotEmpty() && text != placeholder) {
                synthesizeAndPlay(text)
            } else {
                Toast.makeText(this, "कोई ट्रांसक्रिप्ट उपलब्ध नहीं है", Toast.LENGTH_SHORT).show()
            }
        }

        btnTtsStop.setOnClickListener {
            priorityScheduler.stop()
            btnTtsSpeak.isEnabled = true
            btnTtsSpeakTranscript.isEnabled = true
            btnTtsStop.isEnabled = false
            if (wasRecordingBeforePlayback && !audioRecorder.isActive) {
                wasRecordingBeforePlayback = false
                startListening()
            }
        }

        // Emergency Alert & Priority UI bindings (Phase 8.5)
        tvSchedulerState = findViewById(R.id.tvSchedulerState)
        btnAlertFire = findViewById(R.id.btnAlertFire)
        btnAlertMedical = findViewById(R.id.btnAlertMedical)
        btnAlertHazard = findViewById(R.id.btnAlertHazard)
        btnAlertEmergency = findViewById(R.id.btnAlertEmergency)
        btnTestInterruption = findViewById(R.id.btnTestInterruption)
        tvAlertMetricLookup = findViewById(R.id.tvAlertMetricLookup)
        tvAlertMetricPrep = findViewById(R.id.tvAlertMetricPrep)
        tvAlertMetricTotal = findViewById(R.id.tvAlertMetricTotal)
        tvAlertMetricStatus = findViewById(R.id.tvAlertMetricStatus)

        btnAlertFire.setOnClickListener {
            priorityScheduler.enqueue(AudioMessage.fromPredefined(PredefinedAlert.FIRE_EVACUATION))
        }

        btnAlertMedical.setOnClickListener {
            priorityScheduler.enqueue(AudioMessage.fromPredefined(PredefinedAlert.MEDICAL_ASSISTANCE))
        }

        btnAlertHazard.setOnClickListener {
            priorityScheduler.enqueue(AudioMessage.fromPredefined(PredefinedAlert.HAZARD_WARNING))
        }

        btnAlertEmergency.setOnClickListener {
            priorityScheduler.enqueue(AudioMessage.fromPredefined(PredefinedAlert.EMERGENCY_NOTIFICATION))
        }

        btnTestInterruption.setOnClickListener {
            Toast.makeText(this, "Testing: Playing Normal TTS -> Alert Preemption", Toast.LENGTH_SHORT).show()
            // 1. Enqueue long normal speech message
            priorityScheduler.enqueue(
                AudioMessage.createNormal("यहाँ सामान्य बातचीत चल रही है और यह संदेश काफी लंबा है ताकि आपातकालीन अलर्ट इसे बीच में रोक सके।")
            )
            // 2. Enqueue alert after brief delay to observe interruption
            lifecycleScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(400)
                priorityScheduler.enqueue(AudioMessage.fromPredefined(PredefinedAlert.FIRE_EVACUATION))
            }
        }

        btnClearTranscript.setOnClickListener {
            tvTranscript.text = getString(R.string.default_transcript_placeholder)
        }

        btnToggleListening.setOnClickListener {
            if (audioRecorder.isActive) {
                stopListening()
            } else {
                startListening()
            }
        }

        // Wi-Fi Text Transport UI bindings (Phase 8.6)
        tvWifiConnectionStatus = findViewById(R.id.tvWifiConnectionStatus)
        tvLocalIp = findViewById(R.id.tvLocalIp)
        btnWifiHost = findViewById(R.id.btnWifiHost)
        etPeerIp = findViewById(R.id.etPeerIp)
        btnWifiJoin = findViewById(R.id.btnWifiJoin)
        btnWifiDisconnect = findViewById(R.id.btnWifiDisconnect)
        switchAutoSendStt = findViewById(R.id.switchAutoSendStt)
        btnWifiSendTest = findViewById(R.id.btnWifiSendTest)
        btnWifiSendAlert = findViewById(R.id.btnWifiSendAlert)
        tvWifiMetricPayload = findViewById(R.id.tvWifiMetricPayload)
        tvWifiMetricLatency = findViewById(R.id.tvWifiMetricLatency)
        tvWifiChatLog = findViewById(R.id.tvWifiChatLog)

        // Refresh local IP dynamically
        updateLocalIpDisplay()

        btnWifiHost.setOnClickListener {
            lifecycleScope.launch {
                updateLocalIpDisplay()
                tvWifiConnectionStatus.text = "🟡 Hosting..."
                tvWifiConnectionStatus.setTextColor(getColor(R.color.accent))
                appendChatLog("Starting ServerSocket on port ${WifiSocketTransport.DEFAULT_PORT}...")
                wifiTransport.startHost(WifiSocketTransport.DEFAULT_PORT)
            }
        }

        btnWifiJoin.setOnClickListener {
            val peerIp = etPeerIp.text.toString().trim()
            if (peerIp.isEmpty()) {
                Toast.makeText(this, "कृपया पीयर आईपी दर्ज करें (उदा. 192.168.43.1)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                tvWifiConnectionStatus.text = "🟡 Connecting..."
                tvWifiConnectionStatus.setTextColor(getColor(R.color.accent))
                appendChatLog("Connecting to peer at $peerIp:${WifiSocketTransport.DEFAULT_PORT}...")
                wifiTransport.connectToHost(peerIp, WifiSocketTransport.DEFAULT_PORT)
            }
        }

        btnWifiDisconnect.setOnClickListener {
            wifiTransport.disconnect()
            tvWifiConnectionStatus.text = "🔴 Disconnected"
            tvWifiConnectionStatus.setTextColor(0xFFEF4444.toInt())
            appendChatLog("Transport stopped by user.")
        }

        btnWifiSendTest.setOnClickListener {
            if (!wifiTransport.isConnected()) {
                Toast.makeText(this, "वाइ-फाई ट्रांसपोर्ट कनेक्ट नहीं है", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val testMessage = AudioMessage.createNormal("नमस्ते, यह iTantra टेक्स्ट संदेश है।")
            sendAudioMessageOverWifi(testMessage, estimatedDurationSec = 2.5f)
        }

        btnWifiSendAlert.setOnClickListener {
            if (!wifiTransport.isConnected()) {
                Toast.makeText(this, "वाइ-फाई ट्रांसपोर्ट कनेक्ट नहीं है", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val alertMessage = AudioMessage.fromPredefined(PredefinedAlert.FIRE_EVACUATION)
            sendAudioMessageOverWifi(alertMessage, estimatedDurationSec = 2.9f)
        }

        // ────────────────────────────────────────────────────────────
        // Bluetooth Text Transport UI bindings (Phase 8.7)
        // ────────────────────────────────────────────────────────────
        rgTransportMode = findViewById(R.id.rgTransportMode)
        tvBtConnectionStatus = findViewById(R.id.tvBtConnectionStatus)
        tvBtConnectedDevice = findViewById(R.id.tvBtConnectedDevice)
        btnBtHost = findViewById(R.id.btnBtHost)
        btnBtDiscover = findViewById(R.id.btnBtDiscover)
        btnBtDisconnect = findViewById(R.id.btnBtDisconnect)
        layoutBtDeviceList = findViewById(R.id.layoutBtDeviceList)
        tvBtDeviceListHeader = findViewById(R.id.tvBtDeviceListHeader)
        btnBtSendTest = findViewById(R.id.btnBtSendTest)
        btnBtSendAlert = findViewById(R.id.btnBtSendAlert)
        tvBtMetricPayload = findViewById(R.id.tvBtMetricPayload)
        tvBtMetricLatency = findViewById(R.id.tvBtMetricLatency)
        tvBtChatLog = findViewById(R.id.tvBtChatLog)

        // Transport Mode Selector
        rgTransportMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbWifi -> {
                    isBluetoothModeActive = false
                    activeTransport = wifiTransport
                    val isConn = wifiTransport.isConnected()
                    updatePttTransportDisplay("🌐 Wi-Fi", isConn)
                    pttStateMachine.setTransportConnected(isConn)
                    conversationController.setTransportConnected(isConn)
                    Log.i(TAG, "Transport mode switched to Wi-Fi (connected=$isConn)")
                }
                R.id.rbBluetooth -> {
                    isBluetoothModeActive = true
                    activeTransport = bluetoothTransport
                    val isConn = bluetoothTransport.isConnected()
                    val dev = if (isConn) bluetoothTransport.connectedDeviceName else null
                    val label = if (dev != null) "📶 BT ($dev)" else "📶 Bluetooth"
                    updatePttTransportDisplay(label, isConn)
                    pttStateMachine.setTransportConnected(isConn)
                    conversationController.setTransportConnected(isConn)
                    Log.i(TAG, "Transport mode switched to Bluetooth (connected=$isConn)")
                }
            }
        }

        // Bluetooth Host
        btnBtHost.setOnClickListener {
            if (!checkBluetoothPermissions()) return@setOnClickListener
            if (!bluetoothTransport.isBluetoothAvailable) {
                Toast.makeText(this, getString(R.string.bt_no_adapter), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!bluetoothTransport.isBluetoothEnabled) {
                requestEnableBluetooth()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                appendBtChatLog("Starting Bluetooth RFCOMM host...")
                bluetoothTransport.startHost()
            }
        }

        // Bluetooth Discover
        btnBtDiscover.setOnClickListener {
            if (!checkBluetoothPermissions()) return@setOnClickListener
            if (!bluetoothTransport.isBluetoothAvailable) {
                Toast.makeText(this, getString(R.string.bt_no_adapter), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!bluetoothTransport.isBluetoothEnabled) {
                requestEnableBluetooth()
                return@setOnClickListener
            }
            startBluetoothDiscovery()
        }

        // Bluetooth Disconnect
        btnBtDisconnect.setOnClickListener {
            bluetoothTransport.disconnect()
            tvBtConnectionStatus.text = getString(R.string.bt_status_disconnected)
            tvBtConnectionStatus.setTextColor(0xFFEF4444.toInt())
            tvBtConnectedDevice.text = "Device: Not connected"
            appendBtChatLog("Bluetooth transport stopped by user.")
        }

        // Bluetooth Send Test
        btnBtSendTest.setOnClickListener {
            if (!bluetoothTransport.isConnected()) {
                Toast.makeText(this, getString(R.string.bt_not_connected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val testMessage = AudioMessage.createNormal("नमस्ते, यह iTantra ब्लूटूथ टेक्स्ट संदेश है।")
            sendAudioMessageOverBluetooth(testMessage, estimatedDurationSec = 2.5f)
        }

        // Bluetooth Send Alert
        btnBtSendAlert.setOnClickListener {
            if (!bluetoothTransport.isConnected()) {
                Toast.makeText(this, getString(R.string.bt_not_connected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val alertMessage = AudioMessage.fromPredefined(PredefinedAlert.FIRE_EVACUATION)
            sendAudioMessageOverBluetooth(alertMessage, estimatedDurationSec = 2.9f)
        }
    }

    private fun setupLanguageSpinner() {
        languageManager.refreshStatuses(applicationContext)
        languageSpinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languageManager.supportedLanguages
        )
        languageSpinnerAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = languageSpinnerAdapter

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLang = languageManager.supportedLanguages[position]
                if (position == previousLanguagePosition) return

                val status = languageManager.getLanguageStatus(selectedLang.code)
                if (status == LanguageManager.LanguageStatus.MODEL_NOT_INSTALLED || status == LanguageManager.LanguageStatus.NOT_YET_VALIDATED) {
                    // Show download prompt and revert selection if not installed
                    showLanguagePackDownloadDialog(selectedLang, onCancel = {
                        spinnerLanguage.setSelection(previousLanguagePosition)
                    })
                    return
                }

                previousLanguagePosition = position
                applyLanguage(selectedLang)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyLanguage(selectedLang: LanguageManager.LanguageInfo) {
        // Prevent language switching during active speech capture or playback
        if (audioRecorder.isActive || priorityScheduler.currentState != PlaybackState.IDLE || conversationController.isConversationActive) {
            Toast.makeText(this, "Cannot change language while conversation or playback is active!", Toast.LENGTH_SHORT).show()
            val currentPos = languageManager.supportedLanguages.indexOfFirst { it.code == languageManager.activeLanguage.code }
            if (currentPos >= 0) {
                spinnerLanguage.setSelection(currentPos)
            }
            return
        }

        languageManager.setLanguage(selectedLang)
        Log.i(TAG, "Selected language changed to: ${selectedLang.englishName} (${selectedLang.code})")

        val engineName = if (selectedLang.recommendedEngine == LanguageManager.STTEngineType.WHISPER_TINY) {
            "WhisperTinyAndroidBackend"
        } else {
            "IndicSTTBackend"
        }
        tvEngineStatus.text = "STT Engine: $engineName"
        tvModelStatus.text = "Model: Switching..."
        tvTtsModelStatus.text = "TTS: Switching..."

        // Safely release previous backends from native RAM before instantiating new models
        sttBackend.release()
        ttsBackend.release()
        System.gc() // Hint GC to reclaim Java metadata wrappers

        val packDir = LanguagePackRepository.getInstalledPackDirectory(applicationContext, selectedLang.code)?.absolutePath

        sttBackend = languageManager.createBackendForLanguage(selectedLang)
        ttsBackend = languageManager.createTTSBackendForLanguage(selectedLang)
        priorityScheduler.ttsBackend = ttsBackend

        // Sequentially initialize STT then TTS to prevent concurrent RAM allocation collision
        initializeSpeechModelsSequentially(packDir)
    }

    private fun initializeSpeechModelsSequentially(modelDir: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Initialize STT
            withContext(Dispatchers.Main) { tvModelStatus.text = "Model: Loading STT..." }
            val sttSuccess = sttBackend.initialize(applicationContext, modelDir)
            val sttMem = MemoryMonitor.getMemorySnapshot("STT Initialized (${languageManager.activeLanguage.code})")

            withContext(Dispatchers.Main) {
                val engineName = if (sttBackend is IndicSTTBackend) "IndicSTTBackend" else "WhisperTinyAndroidBackend"
                tvEngineStatus.text = "STT Engine: $engineName"
                if (sttSuccess) {
                    tvModelStatus.text = "Model: ${sttBackend.modelName} (Loaded in ${sttBackend.loadDurationMs}ms)"
                } else {
                    tvModelStatus.text = "Model: STT Initialization Failed"
                }
                updateRamMetric(sttMem.totalPssMb)
                tvTtsModelStatus.text = "TTS: Loading TTS..."
            }

            // 2. Initialize TTS
            val ttsSuccess = ttsBackend.initialize(applicationContext, modelDir)
            val ttsMem = MemoryMonitor.getMemorySnapshot("TTS Initialized (${languageManager.activeLanguage.code})")

            withContext(Dispatchers.Main) {
                if (ttsSuccess) {
                    tvTtsModelStatus.text = "TTS: ${ttsBackend.modelName} (Loaded in ${ttsBackend.loadDurationMs}ms)"
                } else {
                    tvTtsModelStatus.text = "TTS: Initialization Failed"
                }
                updateRamMetric(ttsMem.totalPssMb)
            }
        }
    }

    private fun showLanguagePackDownloadDialog(lang: LanguageManager.LanguageInfo, onCancel: (() -> Unit)? = null) {
        val manifest = LanguagePackRepository.getPackManifest(lang.code)
        if (manifest == null) {
            Toast.makeText(this, "Language pack metadata not found for ${lang.englishName}", Toast.LENGTH_SHORT).show()
            onCancel?.invoke()
            return
        }

        val requiredMb = manifest.totalSizeBytes / (1024 * 1024)
        val availableMb = LanguagePackRepository.getAvailableDiskSpace(applicationContext) / (1024 * 1024)

        val message = "Language Pack: ${manifest.englishName} (${manifest.languageCode.uppercase()})\n" +
                "Version: ${manifest.version}\n" +
                "Required Storage: ${requiredMb} MB\n" +
                "Available Storage: ${availableMb} MB\n\n" +
                "Download and install this pack for full offline STT and TTS?"

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pack_dialog_title, manifest.englishName))
            .setMessage(message)
            .setPositiveButton(getString(R.string.pack_btn_download)) { _, _ ->
                downloadAndInstallPack(manifest, onSuccess = {
                    runOnUiThread {
                        languageManager.refreshStatuses(applicationContext)
                        languageSpinnerAdapter?.notifyDataSetChanged()
                        val pos = languageManager.supportedLanguages.indexOfFirst { it.code == lang.code }
                        if (pos >= 0) {
                            previousLanguagePosition = pos
                            spinnerLanguage.setSelection(pos)
                            applyLanguage(lang)
                        }
                    }
                })
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onCancel?.invoke()
            }
            .setOnCancelListener {
                onCancel?.invoke()
            }
            .show()
    }

    private fun showLanguagePacksManagerDialog() {
        languageManager.refreshStatuses(applicationContext)
        val packs = LanguagePackRepository.getAllPacks()
        val items: Array<CharSequence> = packs.map { pack ->
            val status = languageManager.getLanguageStatus(pack.languageCode)
            val statusStr = when (status) {
                LanguageManager.LanguageStatus.INSTALLED,
                LanguageManager.LanguageStatus.VALIDATED -> "✅ Available Offline"
                LanguageManager.LanguageStatus.DOWNLOADING -> "⬇ Downloading..."
                else -> "⬇ Download (${pack.totalSizeBytes / (1024 * 1024)} MB)"
            }
            "${pack.englishName} (${pack.languageCode}) - $statusStr" as CharSequence
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pack_btn_manage))
            .setItems(items) { _, which ->
                val selectedPack = packs[which]
                val status = languageManager.getLanguageStatus(selectedPack.languageCode)
                if (status == LanguageManager.LanguageStatus.INSTALLED || status == LanguageManager.LanguageStatus.VALIDATED) {
                    Toast.makeText(this, "${selectedPack.englishName} is already installed and 100% offline.", Toast.LENGTH_SHORT).show()
                } else {
                    val lang = languageManager.supportedLanguages.firstOrNull { it.code == selectedPack.languageCode }
                    if (lang != null) {
                        showLanguagePackDownloadDialog(lang)
                    }
                }
            }
            .setNeutralButton("Download All (SIH Demo)") { _, _ ->
                downloadAllLanguagePacksBatch()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun downloadAllLanguagePacksBatch() {
        val missingPacks = LanguagePackRepository.getAllPacks().filter {
            !LanguagePackRepository.isPackInstalled(applicationContext, it.languageCode)
        }
        if (missingPacks.isEmpty()) {
            Toast.makeText(this, "All language packs are already installed offline!", Toast.LENGTH_LONG).show()
            return
        }

        val totalBytes = missingPacks.sumOf { it.totalSizeBytes }
        val totalMb = totalBytes / (1024 * 1024)
        val availableMb = LanguagePackRepository.getAvailableDiskSpace(applicationContext) / (1024 * 1024)

        if (!LanguagePackRepository.hasSufficientSpace(applicationContext, totalBytes)) {
            Toast.makeText(this, "Insufficient storage! Need ${totalMb} MB, available: ${availableMb} MB", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Download All 8 Language Packs")
            .setMessage("Total download size: ${totalMb} MB across ${missingPacks.size} packs.\nAvailable: ${availableMb} MB.\n\nProceed to download and install all packs for SIH demonstration?")
            .setPositiveButton("Download All") { _, _ ->
                lifecycleScope.launch {
                    for (pack in missingPacks) {
                        downloadAndInstallPack(pack)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstallPack(manifest: LanguagePackManifest, onSuccess: (() -> Unit)? = null) {
        if (!LanguagePackRepository.hasSufficientSpace(applicationContext, manifest.totalSizeBytes)) {
            Toast.makeText(this, "Insufficient disk space for ${manifest.englishName} pack!", Toast.LENGTH_LONG).show()
            return
        }

        val progressDialog = ProgressDialog(this).apply {
            setTitle("Downloading ${manifest.englishName} Pack")
            setMessage("Preparing download...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        languageManager.setLanguageStatus(manifest.languageCode, LanguageManager.LanguageStatus.DOWNLOADING)

        lifecycleScope.launch(Dispatchers.IO) {
            val downloader = LanguagePackDownloader(applicationContext)
            val downloadResult = downloader.downloadPack(manifest) { progress ->
                runOnUiThread {
                    progressDialog.progress = progress.percent
                    val readMb = progress.bytesDownloaded / (1024 * 1024)
                    val totalMb = progress.totalBytes / (1024 * 1024)
                    progressDialog.setMessage("Downloading ${progress.currentFileName}: $readMb MB / $totalMb MB (${progress.percent}%)")
                }
            }

            if (!downloadResult.isSuccess) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    languageManager.setLanguageStatus(manifest.languageCode, LanguageManager.LanguageStatus.MODEL_NOT_INSTALLED)
                    Toast.makeText(this@MainActivity, "Download failed: ${downloadResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                progressDialog.setMessage("Verifying checksums and installing atomically...")
            }

            val installResult = LanguagePackInstaller.verifyAndInstall(applicationContext, manifest)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (installResult.isSuccess) {
                    languageManager.setLanguageStatus(manifest.languageCode, LanguageManager.LanguageStatus.INSTALLED)
                    Toast.makeText(this@MainActivity, "✅ ${manifest.englishName} pack verified and installed offline!", Toast.LENGTH_LONG).show()
                    onSuccess?.invoke()
                } else {
                    languageManager.setLanguageStatus(manifest.languageCode, LanguageManager.LanguageStatus.MODEL_NOT_INSTALLED)
                    Toast.makeText(this@MainActivity, "❌ Installation failed: ${installResult.errorMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return if (permission != PackageManager.PERMISSION_GRANTED) {
            tvMicStatus.text = "Microphone: Permission Required (Requesting...)"
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO
            )
            false
        } else {
            tvMicStatus.text = "Microphone: Ready (16 kHz, Mono, 30 ms)"
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    tvMicStatus.text = "Microphone: Permission Granted (Ready)"
                    Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    tvMicStatus.text = "Microphone: Permission DENIED"
                    btnToggleListening.isEnabled = false
                    showError("Microphone permission is required for speech capture.")
                }
            }
            PERMISSION_REQUEST_BLUETOOTH -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Log.i(TAG, "Bluetooth permissions granted")
                    Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "Bluetooth permissions denied")
                    Toast.makeText(this, getString(R.string.bt_permission_denied), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "Bluetooth enabled by user")
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "User declined to enable Bluetooth")
                Toast.makeText(this, getString(R.string.bt_disabled), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initializeModel(modelDir: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = sttBackend.initialize(applicationContext, modelDir)
            val mem = MemoryMonitor.getMemorySnapshot("STT Model Initialized")

            withContext(Dispatchers.Main) {
                val engineName = if (sttBackend is IndicSTTBackend) "IndicSTTBackend" else "WhisperTinyAndroidBackend"
                tvEngineStatus.text = "STT Engine: $engineName"
                if (success) {
                    tvModelStatus.text = "Model: ${sttBackend.modelName} (Loaded in ${sttBackend.loadDurationMs}ms)"
                } else {
                    tvModelStatus.text = "Model: Initialization Failed"
                }
                updateRamMetric(mem.totalPssMb)
            }
        }
    }

    private fun initializeTtsModel(modelDir: String? = null) {
        tvTtsModelStatus.text = "TTS: Loading..."
        lifecycleScope.launch(Dispatchers.IO) {
            val success = ttsBackend.initialize(applicationContext, modelDir)
            val mem = MemoryMonitor.getMemorySnapshot("TTS Model Initialized")

            withContext(Dispatchers.Main) {
                if (success) {
                    tvTtsModelStatus.text = "TTS: ${ttsBackend.modelName} (Loaded in ${ttsBackend.loadDurationMs}ms)"

                    // Trigger background pre-synthesis of alerts
                    lifecycleScope.launch(Dispatchers.IO) {
                        alertAudioCache.preSynthesizeAlerts(ttsBackend) { done, total ->
                            runOnUiThread {
                                tvSchedulerState.text = "Scheduler: IDLE | Cache: $done/$total Ready"
                            }
                        }
                        withContext(Dispatchers.Main) {
                            val count = alertAudioCache.cachedCount
                            val sizeKb = alertAudioCache.totalDiskSizeBytes / 1024
                            tvSchedulerState.text = "Scheduler: IDLE | Cache: $count/4 Ready (${sizeKb}KB)"
                            tvAlertMetricStatus.text = "Cache: $count/4 Ready (${sizeKb}KB) | Dedup: Active"
                        }
                    }
                } else {
                    tvTtsModelStatus.text = "TTS: Initialization Failed"
                }
                updateRamMetric(mem.totalPssMb)
            }
        }
    }

    private fun setupSchedulerCallbacks() {
        priorityScheduler.onStateChanged = { state ->
            runOnUiThread {
                val count = alertAudioCache.cachedCount
                val sizeKb = alertAudioCache.totalDiskSizeBytes / 1024
                tvSchedulerState.text = "Scheduler: $state | Cache: $count/4 Ready (${sizeKb}KB)"

                // If scheduler starts playing, pause listening and lock out mic to avoid acoustic feedback
                if (state != PlaybackState.IDLE) {
                    if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                        conversationController.onRemoteTtsStarted()
                    } else {
                        pttStateMachine.transitionTo(PttStateMachine.State.PLAYING)
                        if (audioRecorder.isActive) {
                            wasRecordingBeforePlayback = true
                            stopListening()
                        }
                    }
                } else {
                    // Resumed to IDLE
                    if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                        conversationController.onRemoteTtsFinished()
                    } else {
                        pttStateMachine.transitionTo(
                            if (pttStateMachine.isTransportConnected) PttStateMachine.State.IDLE
                            else PttStateMachine.State.DISCONNECTED
                        )
                        if (wasRecordingBeforePlayback && !audioRecorder.isActive && !isPttModeActive) {
                            wasRecordingBeforePlayback = false
                            startListening()
                        }
                    }
                }
            }
        }

        priorityScheduler.onAlertTelemetry = { telemetry ->
            runOnUiThread {
                tvAlertMetricLookup.text = "Lookup: ${telemetry.lookupLatencyMs} ms"
                tvAlertMetricPrep.text = "Prep: ${telemetry.prepLatencyMs} ms"
                tvAlertMetricTotal.text = "Total T3-T0: ${telemetry.alertToPlaybackLatencyMs} ms"
                val hitStr = if (telemetry.isCacheHit) "HIT (Cache)" else "MISS (Synthesized)"
                tvAlertMetricStatus.text = "$hitStr (%.2fs) | Dedup: Active".format(telemetry.audioDurationSec)
            }
        }

        priorityScheduler.onDuplicateAlertSuppressed = { alertId ->
            runOnUiThread {
                Toast.makeText(this, "Duplicate alert suppressed: $alertId", Toast.LENGTH_SHORT).show()
                tvAlertMetricStatus.text = "Duplicate alert '$alertId' suppressed!"
            }
        }

        priorityScheduler.onNormalInterrupted = { _ ->
            runOnUiThread {
                Toast.makeText(this, "Normal TTS interrupted and discarded by emergency alert!", Toast.LENGTH_SHORT).show()
            }
        }

        priorityScheduler.onError = { err ->
            runOnUiThread { showError(err) }
        }
    }

    private fun synthesizeAndPlay(text: String) {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "TTS requested at T0=$t0 for text: '$text'")

        // Prevent acoustic feedback loop while playing through loudspeaker
        if (audioRecorder.isActive) {
            wasRecordingBeforePlayback = true
            stopListening()
        }

        btnTtsSpeak.isEnabled = false
        btnTtsSpeakTranscript.isEnabled = false
        btnTtsStop.isEnabled = true

        lifecycleScope.launch(Dispatchers.Default) {
            val t1 = SystemClock.elapsedRealtime()
            val result = ttsBackend.synthesize(text)
            val t2 = SystemClock.elapsedRealtime()
            val mem = MemoryMonitor.getMemorySnapshot("TTS Post-Inference")

            withContext(Dispatchers.Main) {
                updateRamMetric(mem.totalPssMb)
                if (result.isSuccess && result.pcmData != null) {
                    val pcm = result.pcmData
                    tvTtsMetricLatency.text = "Synth: ${result.latencyMs} ms"
                    tvTtsMetricRtf.text = "RTF: %.3f".format(result.rtf)

                    audioTrackPlayer.play(
                        pcmData = pcm,
                        onPlaybackStarted = {
                            val t3 = SystemClock.elapsedRealtime()
                            val playbackDelay = t3 - t2
                            val totalLatency = t3 - t0
                            Log.i(TAG, "TTS Playback Started: T0=$t0, T1=$t1, T2=$t2, T3=$t3. PlaybackDelay=${playbackDelay}ms, Total=${totalLatency}ms")
                            runOnUiThread {
                                tvTtsMetricPlaybackDelay.text = "Play Delay: ${playbackDelay} ms"
                                tvTtsMetricTotal.text = "Total T3-T0: ${totalLatency} ms"
                            }
                        },
                        onPlaybackFinished = {
                            runOnUiThread {
                                btnTtsSpeak.isEnabled = true
                                btnTtsSpeakTranscript.isEnabled = true
                                btnTtsStop.isEnabled = false

                                // Resume listening if it was active before TTS
                                if (wasRecordingBeforePlayback && !audioRecorder.isActive) {
                                    wasRecordingBeforePlayback = false
                                    startListening()
                                }
                            }
                        }
                    )
                } else {
                    showError(result.errorMessage ?: "TTS synthesis failed")
                    btnTtsSpeak.isEnabled = true
                    btnTtsSpeakTranscript.isEnabled = true
                    btnTtsStop.isEnabled = false

                    if (wasRecordingBeforePlayback && !audioRecorder.isActive) {
                        wasRecordingBeforePlayback = false
                        startListening()
                    }
                }
            }
        }
    }

    private fun setupPipelineCallbacks() {
        utteranceSegmenter.onVADDecision = { decision ->
            val now = System.currentTimeMillis()
            if (now - lastVadUiUpdateMs > 150) {
                lastVadUiUpdateMs = now
                runOnUiThread {
                    tvMetricNoiseFloor.text = "Noise Floor: %.0f".format(decision.noiseFloorRms)
                    tvMetricSpeechThreshold.text = "Speech Thresh: %.0f".format(decision.thresholdRms)
                }
            }
        }

        utteranceSegmenter.onSpeechStateChanged = { isSpeech ->
            runOnUiThread {
                if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                    conversationController.onSpeechDetected(isSpeech)
                }
                if (audioRecorder.isActive) {
                    if (isSpeech) {
                        tvStatus.text = getString(R.string.status_speech_detected)
                        tvStatus.setBackgroundColor(getColor(R.color.accent))
                    } else {
                        tvStatus.text = getString(R.string.status_listening)
                        tvStatus.setBackgroundColor(getColor(R.color.card_bg))
                    }
                }
            }
        }

        utteranceSegmenter.onUtteranceFinalized = { utterance ->
            Log.i(TAG, "Utterance finalized: ${utterance.durationMs}ms (${utterance.pcmData.size} bytes). Transcribing...")
            if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                conversationController.onUtteranceFinalized()
            }

            // STT inference worker runs off UI thread
            lifecycleScope.launch(Dispatchers.Default) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = getString(R.string.status_transcribing)
                    tvStatus.setBackgroundColor(getColor(R.color.primary))
                }

                val langCode = languageManager.activeLanguage.code
                val result = sttBackend.transcribe(utterance.pcmData, langCode)
                val mem = MemoryMonitor.getMemorySnapshot("Post-Inference")

                withContext(Dispatchers.Main) {
                    if (audioRecorder.isActive) {
                        if (utteranceSegmenter.isSpeechActive) {
                            tvStatus.text = getString(R.string.status_speech_detected)
                            tvStatus.setBackgroundColor(getColor(R.color.accent))
                        } else {
                            tvStatus.text = getString(R.string.status_listening)
                            tvStatus.setBackgroundColor(getColor(R.color.card_bg))
                        }
                    }

                    if (result.isSuccess) {
                        if (result.text.isNotBlank()) {
                            val currentText = tvTranscript.text.toString().trim()
                            val placeholder = getString(R.string.default_transcript_placeholder).trim()
                            val isPlaceholder = currentText.isEmpty() || currentText == placeholder

                            val newFullText = if (isPlaceholder) {
                                result.text
                            } else {
                                "$currentText\n${result.text}"
                            }
                            tvTranscript.text = newFullText

                            when (activeVoiceMode) {
                                VoiceInteractionMode.CONTINUOUS_CONVERSATION -> {
                                    Log.i(TAG, "Conversation: STT complete: '${result.text}'. Transmitting...")
                                    conversationController.onSttComplete(hasValidText = true)
                                    sendContinuousMessage(result.text, result.audioDurationSec.toFloat())
                                }
                                VoiceInteractionMode.PUSH_TO_TALK -> {
                                    isPttUtterancePending = false
                                    Log.i(TAG, "PTT: STT complete: '${result.text}'. Sending...")
                                    pttStateMachine.transitionTo(PttStateMachine.State.SENDING)
                                    sendPttMessage(result.text, result.audioDurationSec.toFloat())
                                }
                                VoiceInteractionMode.AUTO_TRANSMIT -> {
                                    if (switchAutoSendStt.isChecked) {
                                        val sttMessage = AudioMessage.createNormal(result.text, langCode)
                                        sendViaActiveTransport(sttMessage, result.audioDurationSec.toFloat())
                                    }
                                }
                            }
                        } else {
                            if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                                conversationController.onSttComplete(hasValidText = false)
                            } else if (isPttModeActive || isPttUtterancePending) {
                                isPttUtterancePending = false
                                pttStateMachine.transitionTo(PttStateMachine.State.IDLE)
                            }
                        }
                        tvMetricLatency.text = "STT Latency: ${result.latencyMs} ms"
                        tvMetricRtf.text = "RTF: %.3f".format(result.rtf)
                        tvMetricAudioDuration.text = "Audio: %.2f s".format(result.audioDurationSec)
                    } else {
                        if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                            conversationController.onSttComplete(hasValidText = false)
                        } else if (isPttModeActive || isPttUtterancePending) {
                            isPttUtterancePending = false
                            pttStateMachine.transitionTo(PttStateMachine.State.IDLE)
                        }
                        showError(result.errorMessage ?: "Transcription error")
                    }
                    updateRamMetric(mem.totalPssMb)
                }
            }
        }
    }

    private fun startListening() {
        if (!checkPermissions()) return

        val started = audioRecorder.start { err ->
            runOnUiThread { showError(err) }
        }

        if (started) {
            btnToggleListening.text = getString(R.string.btn_stop_listening)
            btnToggleListening.setBackgroundColor(getColor(R.color.accent_red))
            tvStatus.text = getString(R.string.status_listening)
            tvStatus.setBackgroundColor(getColor(R.color.card_bg))
            audioPreprocessor.reset()
            utteranceSegmenter.reset()

            MemoryMonitor.getMemorySnapshot("Audio Capture Started")

            // Start VAD processing worker thread
            pipelineJob = lifecycleScope.launch(Dispatchers.Default) {
                while (isActive && audioRecorder.isActive) {
                    val rawFrame = audioRecorder.frameQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (rawFrame != null) {
                        val processed = audioPreprocessor.process(rawFrame)
                        utteranceSegmenter.processFrame(rawFrame, vadPcm = processed.filteredPcm, precomputedRms = processed.rms)
                    }
                }
            }
        }
    }

    private fun stopListening() {
        audioRecorder.stop()
        pipelineJob?.cancel()
        pipelineJob = null
        audioPreprocessor.reset()
        utteranceSegmenter.reset()

        btnToggleListening.text = getString(R.string.btn_start_listening)
        btnToggleListening.setBackgroundColor(getColor(R.color.accent))
        tvStatus.text = getString(R.string.status_idle)
        tvStatus.setBackgroundColor(getColor(R.color.card_bg))

        val mem = MemoryMonitor.getMemorySnapshot("Audio Capture Stopped")
        updateRamMetric(mem.totalPssMb)

        Log.i(TAG, "Stopped listening. Total captured: ${audioRecorder.totalCapturedFrames.get()}, Dropped: ${audioRecorder.droppedFrames.get()}")
    }

    private fun updateLocalIpDisplay() {
        val ip = NetworkUtils.getLocalIpAddress()
        tvLocalIp.text = if (ip != null) "My IP: $ip" else "My IP: Not connected"
    }

    private fun appendChatLog(entry: String) {
        val current = tvWifiChatLog.text.toString()
        val lines = current.split("\n").takeLast(5)
        val updated = (lines + entry).joinToString("\n")
        tvWifiChatLog.text = updated
    }

    private fun setupWifiCallbacks() {
        wifiTransport.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    ConnectionState.DISCONNECTED -> {
                        tvWifiConnectionStatus.text = "🔴 Disconnected"
                        tvWifiConnectionStatus.setTextColor(0xFFEF4444.toInt())
                        btnWifiHost.isEnabled = true
                        btnWifiJoin.isEnabled = true
                        btnWifiDisconnect.isEnabled = false
                        appendChatLog("[State] Disconnected")
                        if (!isBluetoothModeActive) {
                            updatePttTransportDisplay("🌐 Wi-Fi", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.HOSTING -> {
                        tvWifiConnectionStatus.text = "🟡 Hosting (Port ${WifiSocketTransport.DEFAULT_PORT})"
                        tvWifiConnectionStatus.setTextColor(getColor(R.color.accent))
                        btnWifiHost.isEnabled = false
                        btnWifiJoin.isEnabled = false
                        btnWifiDisconnect.isEnabled = true
                        appendChatLog("[State] Hosting... Waiting for peer")
                        if (!isBluetoothModeActive) {
                            updatePttTransportDisplay("🌐 Wi-Fi (Hosting)", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.CONNECTING -> {
                        tvWifiConnectionStatus.text = "🟡 Connecting..."
                        tvWifiConnectionStatus.setTextColor(getColor(R.color.accent))
                        btnWifiHost.isEnabled = false
                        btnWifiJoin.isEnabled = false
                        btnWifiDisconnect.isEnabled = true
                        appendChatLog("[State] Connecting to peer...")
                        if (!isBluetoothModeActive) {
                            updatePttTransportDisplay("🌐 Wi-Fi (Connecting)", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        tvWifiConnectionStatus.text = "🟢 Connected"
                        tvWifiConnectionStatus.setTextColor(0xFF22C55E.toInt())
                        btnWifiHost.isEnabled = false
                        btnWifiJoin.isEnabled = false
                        btnWifiDisconnect.isEnabled = true
                        appendChatLog("[State] 🟢 Peer Connected! Socket session active.")
                        if (!isBluetoothModeActive) {
                            updatePttTransportDisplay("🌐 Wi-Fi", connected = true)
                            pttStateMachine.setTransportConnected(true)
                            conversationController.setTransportConnected(true)
                        }

                        // Perform initial ping-pong transport measurement
                        lifecycleScope.launch {
                            val rttMs = wifiTransport.ping()
                            tvWifiMetricLatency.text = "Sender: -- ms | RTT: ${rttMs} ms | Receiver: -- ms"
                        }
                    }
                    ConnectionState.ERROR -> {
                        tvWifiConnectionStatus.text = "❌ Error"
                        tvWifiConnectionStatus.setTextColor(0xFFEF4444.toInt())
                        btnWifiHost.isEnabled = true
                        btnWifiJoin.isEnabled = true
                        btnWifiDisconnect.isEnabled = false
                        appendChatLog("[State] Error encountered")
                        if (!isBluetoothModeActive) {
                            updatePttTransportDisplay("🌐 Wi-Fi", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.SEARCHING -> {
                        // Not used by Wi-Fi transport — no-op
                    }
                }
            }
        }

        wifiTransport.onMessageReceived = { message, recvTimeMs ->
            runOnUiThread {
                val t3 = recvTimeMs
                val t4 = SystemClock.elapsedRealtime()

                appendChatLog("📥 Received [${message.priority}]: ${message.text.take(40)}...")
                appendConversationLog("Remote: ${message.text}")

                // Enqueue directly into PriorityAudioScheduler (Phase 8.5)
                priorityScheduler.enqueue(message)

                val schedEntryLatency = (t4 - t3).coerceAtLeast(0)
                tvWifiMetricLatency.text = "Receiver recv->sched: ${schedEntryLatency} ms | Frame: ${message.text.length} chars"

                // Dynamic comparison calculation
                val payloadBytes = MessageProtocol.serializeMessage(message).size
                val savings = MessageProtocol.calculateSavings(payloadBytes, 2.5f)
                tvWifiMetricPayload.text = savings.formattedSummary
            }
        }

        wifiTransport.onError = { error ->
            runOnUiThread {
                Toast.makeText(this, "Wi-Fi Error: $error", Toast.LENGTH_SHORT).show()
                appendChatLog("⚠️ $error")
            }
        }
    }

    private fun sendAudioMessageOverWifi(message: AudioMessage, estimatedDurationSec: Float) {
        lifecycleScope.launch {
            try {
                val sendDurationMs = wifiTransport.send(message)
                val serializedBytes = MessageProtocol.serializeMessage(message).size
                val savings = MessageProtocol.calculateSavings(serializedBytes, estimatedDurationSec)

                runOnUiThread {
                    appendChatLog("📤 Sent [${message.priority}]: ${message.text.take(40)}...")
                    tvWifiMetricPayload.text = savings.formattedSummary
                    tvWifiMetricLatency.text = "Sender send: ${sendDurationMs} ms (Local T1->T2) | Frame: ${serializedBytes} B"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message over Wi-Fi", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    appendChatLog("❌ Send failed: ${e.message}")
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Phase 8.7: Bluetooth Transport Integration
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Checks and requests Bluetooth runtime permissions.
     * Returns true if all required permissions are already granted.
     */
    private fun checkBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            val neededPermissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (neededPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_BLUETOOTH)
                return false
            }
        } else {
            // Pre-Android 12: check ACCESS_FINE_LOCATION (needed for discovery)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_BLUETOOTH
                )
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        // Show device list area
        layoutBtDeviceList.visibility = View.VISIBLE
        // Clear previously displayed device buttons (keep header)
        val childCount = layoutBtDeviceList.childCount
        if (childCount > 1) {
            layoutBtDeviceList.removeViews(1, childCount - 1)
        }

        // First, show already-paired devices
        val pairedDevices = bluetoothTransport.getPairedDevices()
        if (pairedDevices.isNotEmpty()) {
            tvBtDeviceListHeader.text = "Paired Devices:"
            for (device in pairedDevices) {
                val name = try { device.name } catch (e: SecurityException) { null }
                addDeviceButton(device, name ?: device.address, isPaired = true)
            }
        }

        appendBtChatLog(getString(R.string.bt_discovery_started))
        bluetoothTransport.startDiscovery(this)
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceButton(device: android.bluetooth.BluetoothDevice, label: String, isPaired: Boolean) {
        val btn = Button(this).apply {
            text = if (isPaired) "📌 $label" else "📡 $label"
            setTextColor(0xFFF8FAFC.toInt())
            setBackgroundColor(0xFF334155.toInt())
            textSize = 12f
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 4
            layoutParams = lp

            setOnClickListener {
                appendBtChatLog("Connecting to: $label...")
                bluetoothTransport.connectToDevice(device)
            }
        }
        layoutBtDeviceList.addView(btn)
    }

    private fun setupBluetoothCallbacks() {
        bluetoothTransport.onStateChanged = { btState ->
            runOnUiThread {
                when (btState) {
                    ConnectionState.DISCONNECTED -> {
                        tvBtConnectionStatus.text = getString(R.string.bt_status_disconnected)
                        tvBtConnectionStatus.setTextColor(0xFFEF4444.toInt())
                        tvBtConnectedDevice.text = "Device: Not connected"
                        btnBtHost.isEnabled = true
                        btnBtDiscover.isEnabled = true
                        btnBtDisconnect.isEnabled = false
                        appendBtChatLog("[State] Disconnected")
                        if (isBluetoothModeActive) {
                            updatePttTransportDisplay("📶 Bluetooth", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.SEARCHING -> {
                        tvBtConnectionStatus.text = getString(R.string.bt_status_searching)
                        tvBtConnectionStatus.setTextColor(getColor(R.color.bluetooth_accent))
                        btnBtHost.isEnabled = false
                        btnBtDiscover.isEnabled = false
                        btnBtDisconnect.isEnabled = true
                        appendBtChatLog("[State] Searching for devices...")
                        if (isBluetoothModeActive) {
                            updatePttTransportDisplay("📶 Bluetooth (Searching)", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.HOSTING -> {
                        tvBtConnectionStatus.text = getString(R.string.bt_status_hosting)
                        tvBtConnectionStatus.setTextColor(getColor(R.color.bluetooth_accent))
                        btnBtHost.isEnabled = false
                        btnBtDiscover.isEnabled = false
                        btnBtDisconnect.isEnabled = true
                        appendBtChatLog("[State] Hosting RFCOMM... Waiting for peer")
                        if (isBluetoothModeActive) {
                            updatePttTransportDisplay("📶 Bluetooth (Hosting)", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.CONNECTING -> {
                        tvBtConnectionStatus.text = getString(R.string.bt_status_connecting)
                        tvBtConnectionStatus.setTextColor(getColor(R.color.bluetooth_accent))
                        btnBtHost.isEnabled = false
                        btnBtDiscover.isEnabled = false
                        btnBtDisconnect.isEnabled = true
                        appendBtChatLog("[State] Connecting to peer...")
                        if (isBluetoothModeActive) {
                            updatePttTransportDisplay("📶 Bluetooth (Connecting)", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        tvBtConnectionStatus.text = getString(R.string.bt_status_connected)
                        tvBtConnectionStatus.setTextColor(0xFF22C55E.toInt())
                        val deviceName = bluetoothTransport.connectedDeviceName ?: "Unknown"
                        tvBtConnectedDevice.text = "Device: $deviceName"
                        btnBtHost.isEnabled = false
                        btnBtDiscover.isEnabled = false
                        btnBtDisconnect.isEnabled = true
                        layoutBtDeviceList.visibility = View.GONE
                        appendBtChatLog("[State] 🟢 Bluetooth Connected to $deviceName!")
                        if (isBluetoothModeActive) {
                            updatePttTransportDisplay("📶 BT ($deviceName)", connected = true)
                            pttStateMachine.setTransportConnected(true)
                            conversationController.setTransportConnected(true)
                        }

                        // Perform initial ping-pong transport measurement
                        lifecycleScope.launch {
                            val rttMs = bluetoothTransport.ping()
                            tvBtMetricLatency.text = "Sender: -- ms | RTT: ${rttMs} ms | Receiver: -- ms"
                        }
                    }
                    ConnectionState.ERROR -> {
                        tvBtConnectionStatus.text = getString(R.string.bt_status_error)
                        tvBtConnectionStatus.setTextColor(0xFFEF4444.toInt())
                        btnBtHost.isEnabled = true
                        btnBtDiscover.isEnabled = true
                        btnBtDisconnect.isEnabled = false
                        appendBtChatLog("[State] Bluetooth error")
                        if (isBluetoothModeActive) {
                            updatePttTransportDisplay("📶 Bluetooth", connected = false)
                            pttStateMachine.setTransportConnected(false)
                            conversationController.setTransportConnected(false)
                        }
                    }
                }
            }
        }

        bluetoothTransport.onMessageReceived = { message, recvTimeMs ->
            runOnUiThread {
                val t3 = recvTimeMs
                val t4 = SystemClock.elapsedRealtime()

                appendBtChatLog("📥 BT Received [${message.priority}]: ${message.text.take(40)}...")
                appendConversationLog("Remote: ${message.text}")

                // Enqueue into PriorityAudioScheduler for TTS playback
                priorityScheduler.enqueue(message)

                val schedEntryLatency = (t4 - t3).coerceAtLeast(0)
                tvBtMetricLatency.text = "Receiver recv->sched: ${schedEntryLatency} ms | Frame: ${message.text.length} chars"

                val payloadBytes = MessageProtocol.serializeMessage(message).size
                val savings = MessageProtocol.calculateSavings(payloadBytes, 2.5f)
                tvBtMetricPayload.text = savings.formattedSummary
            }
        }

        bluetoothTransport.onError = { error ->
            runOnUiThread {
                Toast.makeText(this, "Bluetooth Error: $error", Toast.LENGTH_SHORT).show()
                appendBtChatLog("⚠️ $error")
            }
        }

        bluetoothTransport.onDeviceDiscovered = { device ->
            runOnUiThread {
                val name = getBluetoothDeviceName(device)
                val label = name ?: device.address
                appendBtChatLog("Found: $label")
                addDeviceButton(device, label, isPaired = false)
            }
        }

        bluetoothTransport.onDiscoveryFinished = {
            runOnUiThread {
                val count = bluetoothTransport.discoveredDevices.size
                appendBtChatLog("Discovery finished. $count new device(s) found.")
                if (count == 0 && layoutBtDeviceList.childCount <= 1) {
                    Toast.makeText(this, getString(R.string.bt_no_devices), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendAudioMessageOverBluetooth(message: AudioMessage, estimatedDurationSec: Float) {
        lifecycleScope.launch {
            try {
                val sendDurationMs = bluetoothTransport.send(message)
                val serializedBytes = MessageProtocol.serializeMessage(message).size
                val savings = MessageProtocol.calculateSavings(serializedBytes, estimatedDurationSec)

                runOnUiThread {
                    appendBtChatLog("📤 BT Sent [${message.priority}]: ${message.text.take(40)}...")
                    tvBtMetricPayload.text = savings.formattedSummary
                    tvBtMetricLatency.text = "Sender send: ${sendDurationMs} ms (Local T1->T2) | Frame: ${serializedBytes} B"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message over Bluetooth", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "BT Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    appendBtChatLog("❌ BT Send failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Routes a message through whichever transport is currently active.
     * Used by STT auto-send. Falls back gracefully if the active transport is not connected.
     */
    private fun sendViaActiveTransport(message: AudioMessage, estimatedDurationSec: Float) {
        val transport = activeTransport
        if (transport == null || !transport.isConnected()) {
            Log.d(TAG, "Active transport not connected, skipping auto-send")
            return
        }
        appendConversationLog("You (Auto): ${message.text}")
        if (transport is WifiSocketTransport) {
            sendAudioMessageOverWifi(message, estimatedDurationSec)
        } else if (transport is BluetoothTextTransport) {
            sendAudioMessageOverBluetooth(message, estimatedDurationSec)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Phase 8.8: Push-to-Talk Implementation & Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun setupPttCallbacks() {
        pttStateMachine.onStateChanged = { state, turn ->
            runOnUiThread {
                updatePttUi(state, turn)
            }
        }
    }

    private fun updatePttUi(state: PttStateMachine.State, turn: PttStateMachine.ConversationTurn) {
        if (activeVoiceMode != VoiceInteractionMode.PUSH_TO_TALK) return
        // Turn Indicator Pill
        when (turn) {
            PttStateMachine.ConversationTurn.YOUR_TURN -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_your_turn)
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent_green))
            }
            PttStateMachine.ConversationTurn.YOU_SPEAKING -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_speaking)
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent_red))
            }
            PttStateMachine.ConversationTurn.PROCESSING -> {
                tvPttTurnIndicator.text = "⏳ Processing"
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent))
            }
            PttStateMachine.ConversationTurn.SENDING -> {
                tvPttTurnIndicator.text = "📤 Sending"
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent))
            }
            PttStateMachine.ConversationTurn.REMOTE_SPEAKER -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_remote)
                tvPttTurnIndicator.setTextColor(0xFFF59E0B.toInt())
            }
            PttStateMachine.ConversationTurn.OFFLINE -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_offline)
                tvPttTurnIndicator.setTextColor(0xFFEF4444.toInt())
            }
            PttStateMachine.ConversationTurn.ERROR -> {
                tvPttTurnIndicator.text = "❌ Error"
                tvPttTurnIndicator.setTextColor(0xFFEF4444.toInt())
            }
        }

        // State Banner & PTT Button
        when (state) {
            PttStateMachine.State.DISCONNECTED -> {
                tvPttStateBanner.text = "State: DISCONNECTED (Connect Wi-Fi or Bluetooth)"
                tvPttStateBanner.setBackgroundColor(0xFF1E293B.toInt())
                btnPushToTalk.text = getString(R.string.ptt_hold_to_speak)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF475569.toInt())
                btnPushToTalk.isEnabled = true
            }
            PttStateMachine.State.IDLE -> {
                tvPttStateBanner.text = "State: IDLE (Ready to speak)"
                tvPttStateBanner.setBackgroundColor(0xFF1E293B.toInt())
                btnPushToTalk.text = getString(R.string.ptt_hold_to_speak)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ptt_idle_bg))
                btnPushToTalk.isEnabled = true
            }
            PttStateMachine.State.LISTENING -> {
                tvPttStateBanner.text = "🔴 State: LISTENING (Release to send)"
                tvPttStateBanner.setBackgroundColor(0xFF991B1B.toInt())
                btnPushToTalk.text = getString(R.string.ptt_listening)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ptt_listening_bg))
                btnPushToTalk.isEnabled = true
            }
            PttStateMachine.State.PROCESSING -> {
                tvPttStateBanner.text = "⏳ State: PROCESSING (Transcribing speech...)"
                tvPttStateBanner.setBackgroundColor(0xFF312E81.toInt())
                btnPushToTalk.text = getString(R.string.ptt_processing)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4338CA.toInt())
                btnPushToTalk.isEnabled = false
            }
            PttStateMachine.State.SENDING -> {
                tvPttStateBanner.text = "📤 State: SENDING (Transmitting text to peer...)"
                tvPttStateBanner.setBackgroundColor(0xFF065F46.toInt())
                btnPushToTalk.text = getString(R.string.ptt_sending)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF047857.toInt())
                btnPushToTalk.isEnabled = false
            }
            PttStateMachine.State.PLAYING -> {
                tvPttStateBanner.text = "🔊 State: PLAYING (Remote speaker active - mic locked)"
                tvPttStateBanner.setBackgroundColor(0xFF78350F.toInt())
                btnPushToTalk.text = getString(R.string.ptt_remote_playing)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ptt_playing_bg))
                btnPushToTalk.isEnabled = false
            }
            PttStateMachine.State.ERROR -> {
                tvPttStateBanner.text = "❌ State: ERROR"
                tvPttStateBanner.setBackgroundColor(0xFF7F1D1D.toInt())
                btnPushToTalk.text = getString(R.string.ptt_hold_to_speak)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF475569.toInt())
                btnPushToTalk.isEnabled = true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttTouchListener() {
        btnPushToTalk.setOnTouchListener { _, event ->
            // Phase 8.9: Continuous Hands-Free Conversation Mode tap toggle
            if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (conversationController.isConversationActive) {
                        conversationController.stopConversation()
                    } else {
                        if (!conversationController.isTransportConnected) {
                            Toast.makeText(this, getString(R.string.ptt_not_connected), Toast.LENGTH_SHORT).show()
                        } else if (!checkPermissions()) {
                            // Permission requested by checkPermissions()
                        } else {
                            val started = conversationController.startConversation()
                            if (!started) {
                                Toast.makeText(this, getString(R.string.ptt_not_connected), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.i(TAG, "PTT: Button Pressed")
                    if (!pttStateMachine.isTransportConnected) {
                        Toast.makeText(this, getString(R.string.ptt_not_connected), Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (pttStateMachine.isPlaying) {
                        Toast.makeText(this, "Remote speaker is active. Please wait.", Toast.LENGTH_SHORT).show()
                        return@setOnTouchListener true
                    }
                    if (!checkPermissions()) {
                        return@setOnTouchListener true
                    }

                    val transitioned = pttStateMachine.transitionTo(PttStateMachine.State.LISTENING)
                    if (transitioned) {
                        isPttUtterancePending = true
                        Log.i(TAG, "PTT: Listening started")
                        if (!audioRecorder.isActive) {
                            startListening()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pttStateMachine.isListening) {
                        Log.i(TAG, "PTT: Button Released")
                        pttStateMachine.transitionTo(PttStateMachine.State.PROCESSING)

                        // Speech Cutoff Prevention (Section 9):
                        // Grace period allows trailing phoneme frames to arrive in queue before forcing finalization
                        lifecycleScope.launch(Dispatchers.Default) {
                            kotlinx.coroutines.delay(120)
                            val wasSpeechActive = utteranceSegmenter.isSpeechActive
                            val finalized = utteranceSegmenter.forceFinalize(allowShortUtterance = true)
                            Log.i(TAG, "PTT: Released. wasSpeechActive=$wasSpeechActive, finalized=$finalized")

                            if (!finalized) {
                                // No valid speech was buffered during this press
                                isPttUtterancePending = false
                                withContext(Dispatchers.Main) {
                                    if (pttStateMachine.state == PttStateMachine.State.PROCESSING) {
                                        pttStateMachine.transitionTo(PttStateMachine.State.IDLE)
                                    }
                                    if (isPttModeActive && audioRecorder.isActive) {
                                        stopListening()
                                    }
                                }
                            } else {
                                Log.i(TAG, "PTT: Speech finalized. Handing off to STT...")
                                if (isPttModeActive && audioRecorder.isActive) {
                                    stopListening()
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun sendPttMessage(text: String, audioDurationSec: Float) {
        val transport = activeTransport
        val langCode = languageManager.activeLanguage.code
        val message = AudioMessage.createNormal(text, langCode)

        if (transport == null || !transport.isConnected()) {
            Log.w(TAG, "PTT: Send failed - transport disconnected")
            appendConversationLog("⚠ [Not Sent] You: $text (Transport disconnected)")
            pttStateMachine.transitionTo(PttStateMachine.State.IDLE)
            return
        }

        val transportName = if (transport is WifiSocketTransport) "Wi-Fi" else "Bluetooth"
        Log.i(TAG, "PTT: Sending via $transportName")

        lifecycleScope.launch {
            try {
                val sendDurationMs = transport.send(message)
                val serializedBytes = MessageProtocol.serializeMessage(message).size
                val savings = MessageProtocol.calculateSavings(serializedBytes, audioDurationSec)

                Log.i(TAG, "PTT: Message sent in ${sendDurationMs}ms ($serializedBytes bytes)")
                runOnUiThread {
                    appendConversationLog("You: $text")
                    // Also update the respective transport chat logs so existing views remain synchronized
                    if (transport is WifiSocketTransport) {
                        appendChatLog("📤 [PTT] ${message.text.take(40)}...")
                        tvWifiMetricPayload.text = savings.formattedSummary
                        tvWifiMetricLatency.text = "Sender send: ${sendDurationMs} ms | Frame: ${serializedBytes} B"
                    } else if (transport is BluetoothTextTransport) {
                        appendBtChatLog("📤 BT [PTT] ${message.text.take(40)}...")
                        tvBtMetricPayload.text = savings.formattedSummary
                        tvBtMetricLatency.text = "Sender send: ${sendDurationMs} ms | Frame: ${serializedBytes} B"
                    }
                    pttStateMachine.transitionTo(PttStateMachine.State.IDLE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "PTT: Send failed", e)
                runOnUiThread {
                    appendConversationLog("⚠ [Failed] You: $text (${e.message})")
                    pttStateMachine.transitionTo(PttStateMachine.State.IDLE)
                }
            }
        }
    }

    private fun appendConversationLog(entry: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val current = tvPttConversationLog.text.toString()
        val placeholder = "Dialogue is empty. Hold button to speak."
        val cleanCurrent = if (current == placeholder) "" else current
        val lines = if (cleanCurrent.isEmpty()) emptyList() else cleanCurrent.split("\n").takeLast(6)
        val formatted = "[$time] $entry"
        val updated = (lines + formatted).joinToString("\n")
        tvPttConversationLog.text = updated
    }

    private fun updatePttTransportDisplay(transportName: String, connected: Boolean) {
        if (connected) {
            tvPttTransportStatus.text = "$transportName: 🟢 Connected"
            tvPttTransportStatus.setTextColor(0xFF22C55E.toInt())
        } else {
            tvPttTransportStatus.text = "$transportName: 🔴 Disconnected"
            tvPttTransportStatus.setTextColor(0xFFEF4444.toInt())
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Phase 8.9: Continuous Conversation Implementation & Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun setupConversationCallbacks() {
        conversationController.onStateChanged = { state, turn ->
            runOnUiThread {
                if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                    updateContinuousUi(state, turn)
                }
            }
        }

        conversationController.onRequestResumeListening = {
            runOnUiThread {
                if (activeVoiceMode == VoiceInteractionMode.CONTINUOUS_CONVERSATION) {
                    if (!audioRecorder.isActive) {
                        startListening()
                    }
                }
            }
        }

        conversationController.onRequestPauseListening = {
            runOnUiThread {
                if (audioRecorder.isActive) {
                    stopListening()
                }
            }
        }
    }

    private fun updateContinuousUi(
        state: ContinuousConversationController.State,
        turn: ContinuousConversationController.Turn
    ) {
        if (activeVoiceMode != VoiceInteractionMode.CONTINUOUS_CONVERSATION) return

        // Turn Indicator Pill
        when (turn) {
            ContinuousConversationController.Turn.YOUR_TURN -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_your_turn)
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent_green))
            }
            ContinuousConversationController.Turn.YOU_SPEAKING -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_speaking)
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent_red))
            }
            ContinuousConversationController.Turn.PROCESSING -> {
                tvPttTurnIndicator.text = "⏳ Processing"
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent))
            }
            ContinuousConversationController.Turn.SENDING -> {
                tvPttTurnIndicator.text = "📤 Sending"
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent))
            }
            ContinuousConversationController.Turn.WAITING_FOR_PEER -> {
                tvPttTurnIndicator.text = getString(R.string.conv_turn_waiting)
                tvPttTurnIndicator.setTextColor(getColor(R.color.accent))
            }
            ContinuousConversationController.Turn.REMOTE_SPEAKING -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_remote)
                tvPttTurnIndicator.setTextColor(0xFFF59E0B.toInt())
            }
            ContinuousConversationController.Turn.OFFLINE -> {
                tvPttTurnIndicator.text = getString(R.string.ptt_turn_offline)
                tvPttTurnIndicator.setTextColor(0xFFEF4444.toInt())
            }
            ContinuousConversationController.Turn.ERROR -> {
                tvPttTurnIndicator.text = "❌ Error"
                tvPttTurnIndicator.setTextColor(0xFFEF4444.toInt())
            }
        }

        // State Banner & Action Button
        when (state) {
            ContinuousConversationController.State.DISCONNECTED -> {
                tvPttStateBanner.text = "State: DISCONNECTED (Connect Wi-Fi or Bluetooth)"
                tvPttStateBanner.setBackgroundColor(0xFF1E293B.toInt())
                btnPushToTalk.text = getString(R.string.conv_start)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF475569.toInt())
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.IDLE -> {
                tvPttStateBanner.text = "State: IDLE (Tap to start hands-free conversation)"
                tvPttStateBanner.setBackgroundColor(0xFF1E293B.toInt())
                btnPushToTalk.text = getString(R.string.conv_start)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF0284C7.toInt())
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.LISTENING -> {
                tvPttStateBanner.text = "🎙️ State: LISTENING (Speak naturally, auto-detects pauses)"
                tvPttStateBanner.setBackgroundColor(0xFF047857.toInt())
                btnPushToTalk.text = getString(R.string.conv_stop)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.SPEECH_DETECTED -> {
                tvPttStateBanner.text = "🔴 State: SPEECH DETECTED (Capturing utterance...)"
                tvPttStateBanner.setBackgroundColor(0xFF991B1B.toInt())
                btnPushToTalk.text = getString(R.string.conv_stop)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.PROCESSING_STT -> {
                tvPttStateBanner.text = "⏳ State: PROCESSING (Transcribing speech on-device...)"
                tvPttStateBanner.setBackgroundColor(0xFF312E81.toInt())
                btnPushToTalk.text = getString(R.string.conv_stop)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.SENDING -> {
                tvPttStateBanner.text = "📤 State: SENDING (Transmitting text to peer...)"
                tvPttStateBanner.setBackgroundColor(0xFF065F46.toInt())
                btnPushToTalk.text = getString(R.string.conv_stop)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.WAITING_REMOTE -> {
                tvPttStateBanner.text = "⏳ State: WAITING FOR PEER (Listening ready)"
                tvPttStateBanner.setBackgroundColor(0xFF1E293B.toInt())
                btnPushToTalk.text = getString(R.string.conv_stop)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.PLAYING_TTS -> {
                tvPttStateBanner.text = "🔊 State: PLAYING (Remote speaker active - mic locked)"
                tvPttStateBanner.setBackgroundColor(0xFF78350F.toInt())
                btnPushToTalk.text = getString(R.string.conv_stop)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ptt_playing_bg))
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.RETURNING_TO_LISTEN -> {
                tvPttStateBanner.text = "⏳ State: SETTLING (Resuming listening in 200ms...)"
                tvPttStateBanner.setBackgroundColor(0xFF334155.toInt())
                btnPushToTalk.text = getString(R.string.conv_stop)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_red))
                btnPushToTalk.isEnabled = true
            }
            ContinuousConversationController.State.ERROR -> {
                tvPttStateBanner.text = "❌ State: ERROR"
                tvPttStateBanner.setBackgroundColor(0xFF7F1D1D.toInt())
                btnPushToTalk.text = getString(R.string.conv_start)
                btnPushToTalk.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF475569.toInt())
                btnPushToTalk.isEnabled = true
            }
        }
    }

    private fun sendContinuousMessage(text: String, audioDurationSec: Float) {
        val transport = activeTransport
        val langCode = languageManager.activeLanguage.code
        val message = AudioMessage.createNormal(text, langCode)

        if (transport == null || !transport.isConnected()) {
            Log.w(TAG, "Conversation: Send failed - transport unavailable")
            appendConversationLog("⚠ [Not Sent] You: $text (Transport disconnected)")
            conversationController.onSttComplete(hasValidText = false)
            return
        }

        val transportName = if (transport is WifiSocketTransport) "Wi-Fi" else "Bluetooth"
        Log.i(TAG, "Conversation: Sending via $transportName")

        lifecycleScope.launch {
            try {
                val sendDurationMs = transport.send(message)
                val serializedBytes = MessageProtocol.serializeMessage(message).size
                val savings = MessageProtocol.calculateSavings(serializedBytes, audioDurationSec)

                Log.i(TAG, "Conversation: Message sent in ${sendDurationMs}ms ($serializedBytes bytes)")
                runOnUiThread {
                    appendConversationLog("You: $text")
                    if (transport is WifiSocketTransport) {
                        appendChatLog("📤 [Conv] ${message.text.take(40)}...")
                        tvWifiMetricPayload.text = savings.formattedSummary
                        tvWifiMetricLatency.text = "Sender send: ${sendDurationMs} ms | Frame: ${serializedBytes} B"
                    } else if (transport is BluetoothTextTransport) {
                        appendBtChatLog("📤 BT [Conv] ${message.text.take(40)}...")
                        tvBtMetricPayload.text = savings.formattedSummary
                        tvBtMetricLatency.text = "Sender send: ${sendDurationMs} ms | Frame: ${serializedBytes} B"
                    }
                    conversationController.onMessageSent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Conversation: Send exception", e)
                runOnUiThread {
                    appendConversationLog("⚠ [Failed] You: $text (${e.message})")
                    conversationController.onSttComplete(hasValidText = false)
                }
            }
        }
    }

    private fun appendBtChatLog(entry: String) {
        val current = tvBtChatLog.text.toString()
        val lines = current.split("\n").takeLast(5)
        val updated = (lines + entry).joinToString("\n")
        tvBtChatLog.text = updated
    }

    @SuppressLint("MissingPermission")
    private fun getBluetoothDeviceName(device: android.bluetooth.BluetoothDevice): String? {
        return try {
            device.name
        } catch (e: SecurityException) {
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Shared Utilities
    // ════════════════════════════════════════════════════════════════════════

    private fun updateRamMetric(pssMb: Double) {
        tvMetricRam.text = "RAM: %.1f MB (Peak: %.1f MB)".format(pssMb, MemoryMonitor.getPeakMemoryMb())
    }

    private fun showError(message: String) {
        tvLog.visibility = View.VISIBLE
        tvLog.text = message
        Log.e(TAG, message)
    }

    override fun onDestroy() {
        super.onDestroy()
        conversationController.stopConversation()
        stopListening()
        sttBackend.release()
        audioTrackPlayer.release()
        ttsBackend.release()
        priorityScheduler.release()
        wifiTransport.disconnect()
        bluetoothTransport.disconnect()
    }
}
