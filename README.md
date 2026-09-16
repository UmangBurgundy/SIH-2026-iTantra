# iTantra (ई-तंत्र)
### Fully Offline, Low-Latency Multilingual Speech-to-Speech Communication System

Developed for the Smart India Hackathon (SIH) problem statement: High-fidelity, edge-deployed voice communication across Indian languages without internet connectivity, cloud APIs, or external server dependencies.

---

## Architecture & Pipeline

```
[Phone A Microphone]
        │
        ▼
   DualGateVAD (WebRTC C Mode 2 + Adaptive Energy Gate)
        │
        ▼
UtteranceSegmenter (Speech chunking & endpointing)
        │
        ▼
IndicConformer CTC INT8 STT (AI4Bharat / Sherpa-ONNX SIMD)
        │  [Devanagari Hindi Text: ~120 Bytes]
        ▼
Wi-Fi / Hotspot Socket Transport (TCP Port 8888, 4-byte framing)
        │  [Zero Audio Transmitted - 99.8% Bandwidth Savings]
        ▼
[Phone B Wi-Fi Receiver]
        │
        ▼
PriorityAudioScheduler
   ├── ALERT  ──► AlertAudioCache (<120 ms Pre-Synthesized PCM)
   └── NORMAL ──► Meta MMS-TTS Hindi VITS INT8 (ONNX Runtime Mobile)
        │
        ▼
   AudioTrackPlayer
        │
        ▼
[Phone B Speaker]
```

---

## Key Features

1. **Strictly Text-Only Transmission**: Transmits compact UTF-8 text frames instead of multi-megabyte audio streams, achieving >99.8% bandwidth savings over raw PCM.
2. **100% Offline & Private**: No cloud services, external servers, or internet connection required. Operates over direct mobile hotspot or local Wi-Fi router.
3. **Emergency Preemption & Priority Scheduling**: Immediate preemption of conversational TTS when safety alerts (Fire, Medical, Hazard, Notice) are received.
4. **Single-Peer Resilient Socket Layer**: Clean TCP abstraction (`TextTransport`) ready for future Bluetooth / BLE expansion.

---

## Quick Start (For Anyone Cloning This Repository)

### 1. Clone the Repository
```bash
git clone https://github.com/<your-username>/iTantra.git
cd iTantra
```

### 2. Download On-Device AI Models
Before building the Android APK, run the automated model downloader:
```bash
python scripts/download_models.py
```
This will automatically download and place the required quantized INT8 models (`indic-hi.int8.onnx`, `tiny-encoder.int8.onnx`, `tiny-decoder.int8.onnx`, and `mms-hin.int8.onnx`) directly into the `android/app/src/main/assets/models/` folder.

### 3. Build & Install Android APK
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Connect Two Phones (Completely Offline)
1. Turn on **Portable Hotspot** on Phone A (no internet/cellular data required).
2. Connect Phone B to Phone A's Wi-Fi.
3. Open **iTantra** on both phones.
4. Phone A: Tap **HOST SESSION** (note local IP shown, e.g. `192.168.43.1`).
5. Phone B: Enter Phone A's IP and tap **JOIN**.
6. Turn on **"Auto-Transmit Live STT Utterances to Peer"** to begin speaking!
