package org.itantra.speech.ui

import org.itantra.speech.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
import org.itantra.speech.transport.ConnectionState
import org.itantra.speech.transport.MessageProtocol
import org.itantra.speech.transport.NetworkUtils
import org.itantra.speech.transport.TextTransport
import org.itantra.speech.transport.WifiSocketTransport
import org.itantra.speech.utils.MemoryMonitor
import org.itantra.speech.vad.DualGateVad
import org.itantra.speech.vad.UtteranceSegmenter

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
    }

    // UI elements
    private lateinit var spinnerLanguage: Spinner
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

        initViews()
        setupLanguageSpinner()
        checkPermissions()
        initializeModel()
        initializeTtsModel()
        setupPipelineCallbacks()
        setupSchedulerCallbacks()
        setupWifiCallbacks()
    }

    private fun initViews() {
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
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
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languageManager.supportedLanguages
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLang = languageManager.supportedLanguages[position]
                languageManager.setLanguage(selectedLang)
                Log.i(TAG, "Selected language changed to: ${selectedLang.englishName} (${selectedLang.code})")

                // Release previous backends from native RAM before loading new backends
                sttBackend.release()
                val engineName = if (selectedLang.recommendedEngine == LanguageManager.STTEngineType.WHISPER_TINY) {
                    "WhisperTinyAndroidBackend"
                } else {
                    "IndicSTTBackend"
                }
                tvEngineStatus.text = "STT Engine: $engineName"
                tvModelStatus.text = "Model: Loading..."

                sttBackend = languageManager.createBackendForLanguage(selectedLang)
                initializeModel()

                // Update TTS backend for newly selected language
                ttsBackend.release()
                ttsBackend = languageManager.createTTSBackendForLanguage(selectedLang)
                priorityScheduler.ttsBackend = ttsBackend
                initializeTtsModel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tvMicStatus.text = "Microphone: Permission Granted (Ready)"
                Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
            } else {
                tvMicStatus.text = "Microphone: Permission DENIED"
                btnToggleListening.isEnabled = false
                showError("Microphone permission is required for speech capture.")
            }
        }
    }

    private fun initializeModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = sttBackend.initialize(applicationContext)
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

    private fun initializeTtsModel() {
        tvTtsModelStatus.text = "TTS: Loading..."
        lifecycleScope.launch(Dispatchers.IO) {
            val success = ttsBackend.initialize(applicationContext)
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

                // If scheduler starts playing, pause listening to avoid acoustic feedback
                if (state != PlaybackState.IDLE) {
                    if (audioRecorder.isActive) {
                        wasRecordingBeforePlayback = true
                        stopListening()
                    }
                } else {
                    // Resumed to IDLE
                    if (wasRecordingBeforePlayback && !audioRecorder.isActive) {
                        wasRecordingBeforePlayback = false
                        startListening()
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

                            // Phase 8.6: Live STT Utterance Transmission over Wi-Fi
                            if (switchAutoSendStt.isChecked && wifiTransport.isConnected()) {
                                val sttMessage = AudioMessage.createNormal(result.text, langCode)
                                sendAudioMessageOverWifi(sttMessage, result.audioDurationSec.toFloat())
                            }
                        }
                        tvMetricLatency.text = "STT Latency: ${result.latencyMs} ms"
                        tvMetricRtf.text = "RTF: %.3f".format(result.rtf)
                        tvMetricAudioDuration.text = "Audio: %.2f s".format(result.audioDurationSec)
                    } else {
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
                    }
                    ConnectionState.HOSTING -> {
                        tvWifiConnectionStatus.text = "🟡 Hosting (Port ${WifiSocketTransport.DEFAULT_PORT})"
                        tvWifiConnectionStatus.setTextColor(getColor(R.color.accent))
                        btnWifiHost.isEnabled = false
                        btnWifiJoin.isEnabled = false
                        btnWifiDisconnect.isEnabled = true
                        appendChatLog("[State] Hosting... Waiting for peer")
                    }
                    ConnectionState.CONNECTING -> {
                        tvWifiConnectionStatus.text = "🟡 Connecting..."
                        tvWifiConnectionStatus.setTextColor(getColor(R.color.accent))
                        btnWifiHost.isEnabled = false
                        btnWifiJoin.isEnabled = false
                        btnWifiDisconnect.isEnabled = true
                        appendChatLog("[State] Connecting to peer...")
                    }
                    ConnectionState.CONNECTED -> {
                        tvWifiConnectionStatus.text = "🟢 Connected"
                        tvWifiConnectionStatus.setTextColor(0xFF22C55E.toInt())
                        btnWifiHost.isEnabled = false
                        btnWifiJoin.isEnabled = false
                        btnWifiDisconnect.isEnabled = true
                        appendChatLog("[State] 🟢 Peer Connected! Socket session active.")

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
                    }
                }
            }
        }

        wifiTransport.onMessageReceived = { message, recvTimeMs ->
            runOnUiThread {
                val t3 = recvTimeMs
                val t4 = SystemClock.elapsedRealtime()

                appendChatLog("📥 Received [${message.priority}]: ${message.text.take(40)}...")

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
        stopListening()
        sttBackend.release()
        audioTrackPlayer.release()
        ttsBackend.release()
        priorityScheduler.release()
        wifiTransport.disconnect()
    }
}
