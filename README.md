# iTantra (ई-तंत्र)

> **Fully Offline, Edge-Deployed Multilingual Speech-to-Speech Communication System**  
> *Developed for Smart India Hackathon (SIH) — Zero Cloud Dependencies, Zero Internet Connectivity, Complete Data Sovereignty.*

[![Platform](https://img.shields.io/badge/Platform-Android_14%2B_%7C_API_34-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Engine](https://img.shields.io/badge/Inference-ONNX_Runtime_Mobile-005CED.svg)](https://onnxruntime.ai/)
[![Languages](https://img.shields.io/badge/Indic_Languages-10_Scheduled-FF6F00.svg)](https://ai4bharat.iitm.ac.in/)
[![Bandwidth](https://img.shields.io/badge/Bandwidth_Savings-99.8%25_vs_Raw_Audio-10B981.svg)](#text-only-over-the-air-transmission)
[![Status](https://img.shields.io/badge/SIH_Hardening-Phase_9.5_Complete-blue.svg)](PHASE_9_5_SIH_DEPLOYMENT_REPORT.md)

---

## 📌 Problem Statement & Solution Overview

In emergency response operations, defense tactical communications, disaster relief zones, and rural remote terrains, cellular connectivity and cloud infrastructure are frequently degraded or completely absent. Standard speech-to-speech translation solutions rely on massive cloud APIs (Google Cloud Speech, Azure Cognitive Services, OpenAI Whisper APIs), rendering them inoperative in network-denied environments.

**iTantra (ई-तंत्र)** solves this challenge by implementing an **end-to-end, on-device AI speech-to-speech communication pipeline**:
- **100% Offline**: Operates fully in **Airplane Mode** with zero cellular, cloud, or external server dependencies.
- **Ultra-Lean Over-the-Air Footprint**: Employs an intelligent **Text-Only Over-the-Air Transmission architecture**. Instead of streaming bandwidth-heavy raw audio (megabytes per minute), iTantra transcribes speech locally, transmits compact UTF-8 JSON payloads (~120 bytes) over local peer-to-peer radio, and synthesizes speech on the recipient device — slashing bandwidth by **>99.8%**.
- **10 Scheduled Indian Languages**: Native support for **Hindi, English, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, and Bengali**.
- **Safety-Critical Preemption**: Immediate preemption of active conversational speech playback whenever emergency alerts (Fire, Medical, Hazard, Evacuate) are received.

---

## 🏗️ System Architecture & Data Flow

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 TRANSMITTING PEER (DEVICE A)                           │
│                                                                                        │
│   [User Speech]                                                                        │
│         │                                                                              │
│         ▼                                                                              │
│   DualGateVAD ───► WebRTC C VAD (Mode 2) + Adaptive Energy Gate                        │
│         │                                                                              │
│         ▼                                                                              │
│   UtteranceSegmenter ───► Dynamic onset (90ms) & barge-in onset (120ms)                │
│         │                                                                              │
│         ▼                                                                              │
│   Sherpa-ONNX Conformer CTC (INT8 SIMD) / Whisper-Tiny ───► Local STT Transcription    │
│         │                                                                              │
│         ▼                                                                              │
│   UTF-8 JSON Payload Generation (Text + Priority Flag + Lang ID) [~120 Bytes]          │
└─────────────────────────────────────────┬──────────────────────────────────────────────┘
                                          │
                  Zero Audio Streaming!   │ Local Wi-Fi Hotspot (TCP:8888)
                  99.8% Bandwidth Savings │             OR
                  Sub-5ms Packet Transfer │ Bluetooth Classic SPP (RFCOMM)
                                          │
┌─────────────────────────────────────────▼──────────────────────────────────────────────┐
│                                  RECEIVING PEER (DEVICE B)                             │
│                                                                                        │
│   Transport Layer (TextTransport Socket Receiver)                                      │
│         │                                                                              │
│         ▼                                                                              │
│   PriorityAudioScheduler                                                               │
│      ├── EMERGENCY ALERT ──► AlertAudioCache (<120ms Pre-Synthesized Alert Audio)      │
│      └── NORMAL SPEECH   ──► Meta MMS-TTS VITS INT8 On-Device Synthesis (16kHz PCM)    │
│                                     │                                                  │
│                                     ▼                                                  │
│                              AudioTrackPlayer                                          │
│                                     │                                                  │
│                                     ▼                                                  │
│                              [Device Speaker]                                          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## ✨ Core Capabilities

### 1. 10-Language Modular Offline Architecture
- **Pre-installed Core**: Hindi (`hi`) and English (`en`) models are bundled directly with the application baseline.
- **Dynamic On-Demand Language Packs**: 8 regional language packs (Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali) can be downloaded and cached on-device.
- **Pack Verification**: Every downloaded pack is verified via SHA-256 checksums and installed atomically (`.tmp` -> verified extraction) to prevent corrupted runtime states.
- **Zero-OOM Swappable Memory Policy**: To maintain a strict `<300 MB` RAM budget on budget Android hardware, only **one language pack resides in active RAM at any time**. Switching languages safely releases JNI sessions, deallocates native buffers, and triggers garbage collection before loading the target pack.

### 2. Four Flexible Interaction Modes
| Interaction Mode | Operational Description | Ideal Use Case |
| :--- | :--- | :--- |
| **Push-to-Talk (PTT)** | Half-duplex walkie-talkie mode. Hold button to speak; release to transcribe and transmit instantly. | High-noise tactical scenarios, security teams, mission coordination. |
| **Auto-Transmit** | Hands-free continuous listening. Automatically detects voice onset, segments speech, and transmits upon silence detection. | Casual hands-busy communication, medical responders. |
| **Continuous Conversation** | Duplex conversational loop with configurable settling delay (200 ms) preventing acoustic feedback loops. | Direct back-and-forth consultations. |
| **Phone Call Mode with Barge-In** | Full phone-call experience with intelligent interruption. When remote speech is playing through speaker, the user can speak to barge in. Elevated onset detection (120 ms vs 90 ms) prevents echo false-triggers. | Natural real-time conversations, command updates. |

### 3. Dual Offline Transport Engines
1. **Wi-Fi Hotspot Socket Transport**:
   - Peer-to-peer TCP socket over port `8888`.
   - Length-prefixed 4-byte framing protocol with automatic keep-alive heartbeats.
   - Operates over portable phone hotspot or local wireless routers with zero internet backhaul.
2. **Bluetooth Classic SPP Transport**:
   - Dedicated RFCOMM socket over standard Serial Port Profile (UUID: `00001101-0000-1000-8000-00805F9B34FB`).
   - Enables direct device-to-device communication when Wi-Fi is disabled or restricted.

### 4. Safety-Critical Priority Audio Preemption
- Incoming payloads carry a priority enumeration: `NORMAL` vs `ALERT`.
- Alerts (`ALERT_FIRE`, `ALERT_MEDICAL`, `ALERT_HAZARD`, `ALERT_NOTICE`) immediately pause or abort conversational TTS playback.
- Pre-cached alert audio synthesizes and outputs in `<120 ms`, ensuring immediate notification of critical hazards.

---

## 📊 Benchmark & Performance Metrics

*Benchmarked on physical hardware: OnePlus Nord CE4 (Qualcomm Snapdragon 7 Gen 3, Android 14).*

| Metric | Measured Value | Standard Target | Status |
| :--- | :--- | :--- | :---: |
| **STT Latency (Hindi Conformer CTC INT8)** | **142 ms** (1.5s audio) | < 300 ms | **PASS** ✅ |
| **TTS Latency (MMS Hindi VITS INT8)** | **218 ms** (5 words) | < 400 ms | **PASS** ✅ |
| **Real-Time Factor (RTF)** | **0.18** | < 0.30 | **PASS** ✅ |
| **Total Turnaround (Speech-to-Speech)** | **~380 ms** | < 800 ms | **PASS** ✅ |
| **Bandwidth Consumption per Turn** | **~120 Bytes** | < 1 KB | **PASS** ✅ (99.8% savings vs PCM) |
| **Active Memory (RAM) Footprint** | **~268 MB** | < 350 MB | **PASS** ✅ |
| **Clean Uninstall Storage Footprint** | **0 KB residual** | < 50 KB | **PASS** ✅ |
| **10-Language Switching Cycle Memory Stability** | **Zero memory leaks / 0 SIGSEGV** | Zero crash | **PASS** ✅ |

---

## 📂 Repository Structure

```
iTantra/
├── android/                             # Android Studio Project Root
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── assets/models/           # Quantized INT8 ONNX models & vocab files
│   │   │   ├── java/org/itantra/speech/
│   │   │   │   ├── audio/               # AudioRecord & AudioTrackPlayer engines
│   │   │   │   ├── interaction/         # PTT, Continuous, and PhoneCall controllers
│   │   │   │   ├── model/               # ModelSource & LanguageManager controllers
│   │   │   │   ├── pack/                # Dynamic Language Pack downloader & installer
│   │   │   │   ├── stt/                 # IndicConformer & Whisper ONNX backends
│   │   │   │   ├── transport/           # Wi-Fi TCP & Bluetooth SPP text transports
│   │   │   │   ├── tts/                 # Meta MMS-TTS VITS ONNX synthesis backend
│   │   │   │   ├── ui/                  # MainActivity & view bindings
│   │   │   │   ├── utils/               # Audio converters, CRC & WAV utilities
│   │   │   │   └── vad/                 # DualGateVad, UtteranceSegmenter, Noise floor
│   │   │   └── res/                     # Layouts, themes, drawables, strings
│   │   └── src/test/                    # 60+ JVM Unit Tests (VAD, Packs, Controllers)
│   └── build.gradle.kts                 # NDK ABI filters, R8 ProGuard rules, dependencies
├── model_optimization/                  # AI Model compression, quantization & audit scripts
│   ├── audit_apk_contents.py            # Automated APK asset & library size auditor
│   ├── benchmark_latency.py             # STT/TTS latency profiler
│   └── benchmarks/                      # JSON performance records & graph audits
├── scripts/
│   └── download_models.py               # Automated on-device model fetcher from HF
├── PHASE_9_0_BASELINE_AUDIT.md          # Architectural baseline analysis report
├── PHASE_9_1_CLEANUP_REPORT.md          # Redundant model stripping & APK optimization
├── PHASE_9_2_OFFLINE_LANGUAGE_PACK_REPORT.md # 10-language dynamic repository spec
├── PHASE_9_3_RUNTIME_OPTIMIZATION_REPORT.md  # Low-memory execution & buffer pooling
├── PHASE_9_4_MODEL_COMPRESSION_REPORT.md # Conformer CTC INT8 quantization benchmarks
├── PHASE_9_5_SIH_DEPLOYMENT_REPORT.md   # SIH final deployment hardening & verification
└── README.md                            # Main project documentation
```

---

## 🚀 Quick Start Guide

### Prerequisites
- **Android Studio** (Ladybug | Jellyfish | Koala or newer)
- **Android SDK**: API level 26 minimum (Android 8.0), API level 34 targeted (Android 14)
- **JDK**: Java 17
- **Python**: 3.10 or newer (for model setup scripts)
- Two Android test devices (physical hardware recommended for audio and Bluetooth testing)

---

### Step 1: Clone the Repository
```bash
git clone https://github.com/UmangBurgundy/SIH-2026-iTantra.git
cd SIH-2026-iTantra
```

---

### Step 2: Download On-Device AI Models
Execute the automated model downloader script to retrieve the baseline quantized models directly into the Android assets directory:
```bash
python scripts/download_models.py
```
This script downloads and verifies:
1. `indic-hi.int8.onnx` (AI4Bharat IndicConformer Hindi CTC INT8)
2. `tiny-encoder.int8.onnx` & `tiny-decoder.int8.onnx` (Whisper-Tiny English STT INT8)
3. `mms-hin.int8.onnx` (Meta MMS-TTS Hindi VITS INT8)

---

### Step 3: Build & Install Android APK
You can build directly from the terminal or open the `android/` directory in Android Studio:

```bash
cd android
./gradlew assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

### Step 4: Connecting Two Phones (100% Offline)

#### Option A: Wi-Fi Hotspot Mode (Recommended for fastest connection)
1. **Device A (Host)**:
   - Turn on **Portable Hotspot** (cellular data / internet connection is **NOT** needed).
   - Open **iTantra**.
   - Under Transport Mode, select **Wi-Fi**.
   - Tap **HOST SESSION**. The local IP will be displayed (e.g., `192.168.43.1`).
2. **Device B (Client)**:
   - Connect to Device A's Wi-Fi network.
   - Open **iTantra**.
   - Under Transport Mode, select **Wi-Fi**.
   - Enter Device A's IP address and tap **JOIN**.
3. **Start Communicating**:
   - Both screens will reflect an active connection.
   - Select your preferred Voice Interaction Mode (**Push-to-Talk**, **Auto-Transmit**, **Continuous Conversation**, or **Phone Call**).
   - Speak in your chosen language — your voice will be transcribed, transmitted as text, and synthesized on the other phone in real time!

#### Option B: Bluetooth Classic SPP Mode (Zero Wi-Fi Needed)
1. Pair Device A and Device B in standard Android Bluetooth settings.
2. In **iTantra** on both phones, switch Transport Mode to **Bluetooth**.
3. Device A: Tap **LISTEN (SERVER)**.
4. Device B: Select Device A from the paired devices list and tap **CONNECT (CLIENT)**.

---

### Step 5: Managing Offline Language Packs
1. Open the **Language Selection** menu in the top bar.
2. Select any of the 8 optional Indic languages (e.g., Tamil, Gujarati, Bengali).
3. If not already installed, tap **Download Language Pack**.
4. The pack will download, verify via SHA-256, and extract into the app's protected internal storage (`context.filesDir/language_packs/`).
5. Once installed, the pack is permanently available offline without ever requiring an internet connection again.

---

## 🧪 Automated Testing & Verification

Run the entire suite of automated unit tests covering VAD, language pack repositories, model lifecycle safety, and state machine controllers:

```bash
cd android
./gradlew testDebugUnitTest --rerun-tasks
```

All 60 unit tests validate:
- **`PhoneCallControllerTest`**: Barge-in state transitions, interruption callbacks, settling delays.
- **`DeploymentHardeningUnitTest`**: Sequential 10-language switching, corrupted pack rejection, resume offsets.
- **`ModelCompressionUnitTest`**: Checksum validation, manifest integrity, storage threshold assertions.
- **`ModelLifecycleUnitTest`**: Buffer recycling, idempotent session release, single resident model enforcement.

---

## 🏆 Smart India Hackathon (SIH) Compliance Highlights

- [x] **No Cloud APIs**: Zero usage of Google Cloud, Azure, AWS, or OpenAI endpoints.
- [x] **Airplane Mode Operational**: Verified completely functional under Android OS Airplane Mode.
- [x] **Data Privacy & Sovereignty**: Utterances never leave the local edge device or direct peer-to-peer radio link.
- [x] **Optimized for Indian Hardware**: Engineered to run reliably on resource-constrained devices with < 300 MB available RAM.
- [x] **Low-Latency Edge Execution**: End-to-end speech turnaround under 400 ms.

---

## 📄 License & Attribution

- **iTantra Codebase**: Open-source under the [Apache License 2.0](LICENSE).
- **IndicConformer CTC Models**: Developed by [AI4Bharat](https://ai4bharat.iitm.ac.in/), distributed under MIT License / Open Data Commons.
- **MMS-TTS Models**: Developed by [Meta AI Research](https://github.com/facebookresearch/fairseq/tree/main/examples/mms), distributed under CC-BY-NC 4.0.
- **Sherpa-ONNX**: Maintained by [Next-Gen Kaldi / k2-fsa](https://github.com/k2-fsa/sherpa-onnx), distributed under Apache 2.0.
