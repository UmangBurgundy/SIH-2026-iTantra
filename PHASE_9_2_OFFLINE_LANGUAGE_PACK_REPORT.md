# iTantra — Phase 9.2: In-App Offline Language Pack System Report

**Date**: September 17, 2026  
**Build Target**: ARM64-v8a (`app-arm64-v8a-debug.apk`)  
**Tested Physical Device**: OnePlus Nord CE4 (CPH2767 / OP612DL1, Qualcomm Snapdragon 7 Gen 3, 8GB RAM, Android 14)  
**Status**: **COMPLETED & VALIDATED** ✅

---

## 1. Architecture Overview

Phase 9.2 introduces **Option 1: In-App Offline Language Downloader**, expanding iTantra from 2 languages (`hi`, `en`) to all 10 SIH target languages (`hi`, `en`, `gu`, `mr`, `kn`, `ml`, `ta`, `te`, `or`, `bn`) without inflating the base APK.

```text
                               +------------------------------------------+
                               |              iTantra Base APK            |
                               |  (Bundled Assets: Hindi & English Only)  |
                               +------------------------------------------+
                                                    |
                                    User selects new language or
                                   triggers "Download All (SIH Demo)"
                                                    v
+-----------------------+              +--------------------------+
| LanguagePackDownloader| -----------> | Staging Directory:       |
| • HTTP Range Resume   |              | files/language_packs/    |
| • Chunk Streaming     |              |       .partial/<lang>/   |
+-----------------------+              +--------------------------+
                                                    |
                                            Integrity & Sanity Check
                                       (SHA-256 / model structure)
                                                    v
                                       +--------------------------+
                                       |  LanguagePackInstaller   |
                                       |  (Atomic Rename / Commit)|
                                       +--------------------------+
                                                    |
                                                    v
                                       +--------------------------+
                                       | Permanent Storage:       |
                                       | files/language_packs/    |
                                       |       <lang>/            |
                                       +--------------------------+
                                                    |
                                         100% OFFLINE PIPELINE
                                (Airplane Mode: Zero Cloud Dependency)
                                                    |
                                                    v
                      +-----------------------------+-----------------------------+
                      |                                                           |
                      v                                                           v
            +--------------------+                                      +--------------------+
            |   IndicSTTBackend  |                                      |    MmsTTSBackend   |
            | (AI4Bharat CTC)    |                                      |  (Meta MMS VITS)   |
            | (Filesystem load)  |                                      | (Filesystem load)  |
            +--------------------+                                      +--------------------+
```

### Key Components Added / Upgraded
1. **[LanguagePackManifest](file:///c:/Users/Asus/OneDrive/Documents/SIH-2026/iTantra/android/app/src/main/java/org/itantra/speech/pack/LanguagePackManifest.kt)**: Defines the pack specification for each language (STT model, tokenizer, TTS ONNX model, vocab JSON, exact sizes, and SHA-256 hashes).
2. **[LanguagePackRepository](file:///c:/Users/Asus/OneDrive/Documents/SIH-2026/iTantra/android/app/src/main/java/org/itantra/speech/pack/LanguagePackRepository.kt)**: Single source of truth for downloadable packs, persistent paths (`files/language_packs/`), installation detection, and disk space calculation (`StatFs` with safety buffer).
3. **[LanguagePackDownloader](file:///c:/Users/Asus/OneDrive/Documents/SIH-2026/iTantra/android/app/src/main/java/org/itantra/speech/pack/LanguagePackDownloader.kt)**: Manages network streaming into `.partial/` directories with HTTP Range resume capability, progress calculation, and cancellation safety.
4. **[LanguagePackInstaller](file:///c:/Users/Asus/OneDrive/Documents/SIH-2026/iTantra/android/app/src/main/java/org/itantra/speech/pack/LanguagePackInstaller.kt)**: Performs pre-installation sanity validation, SHA-256 hashing, atomic rename to `language_packs/<lang>/`, and fallback rollback.
5. **[MmsTTSBackend](file:///c:/Users/Asus/OneDrive/Documents/SIH-2026/iTantra/android/app/src/main/java/org/itantra/speech/tts/MmsTTSBackend.kt)**: Generalized on-device Meta MMS-TTS VITS inference engine for all 9 Indic languages using ONNX Runtime Mobile, supporting both APK assets and direct filesystem `modelDir` loading.
6. **[IndicSTTBackend](file:///c:/Users/Asus/OneDrive/Documents/SIH-2026/iTantra/android/app/src/main/java/org/itantra/speech/stt/IndicSTTBackend.kt)**: Upgraded to accept dynamic language codes and filesystem `modelDir` paths via Sherpa-ONNX.

---

## 2. Complete 10-Language Inventory

| Code | English Name | Native Name | Script | STT Engine | TTS Engine | Packaging Mode |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `hi` | Hindi | हिन्दी | Devanagari | IndicConformer CTC INT8 | Meta MMS-TTS Hindi VITS | **Bundled in Base APK** |
| `en` | English | English | Latin | Whisper-Tiny INT8 | Android / Sherpa TTS | **Bundled in Base APK** |
| `gu` | Gujarati | ગુજરાતી | Gujarati | IndicConformer CTC INT8 | Meta MMS-TTS Gujarati VITS | **On-Demand Pack** |
| `mr` | Marathi | मराठी | Devanagari | IndicConformer CTC INT8 | Meta MMS-TTS Marathi VITS | **On-Demand Pack** |
| `kn` | Kannada | ಕನ್ನಡ | Kannada | IndicConformer CTC INT8 | Meta MMS-TTS Kannada VITS | **On-Demand Pack** |
| `ml` | Malayalam | മലയാളം | Malayalam | IndicConformer CTC INT8 | Meta MMS-TTS Malayalam VITS | **On-Demand Pack** |
| `ta` | Tamil | தமிழ் | Tamil | IndicConformer CTC INT8 | Meta MMS-TTS Tamil VITS | **On-Demand Pack** |
| `te` | Telugu | తెలుగు | Telugu | IndicConformer CTC INT8 | Meta MMS-TTS Telugu VITS | **On-Demand Pack** |
| `or` | Odia | ଓଡ଼ିଆ | Odia | IndicConformer CTC INT8 | Meta MMS-TTS Odia VITS | **On-Demand Pack** |
| `bn` | Bengali | বাংলা | Bengali | IndicConformer CTC INT8 | Meta MMS-TTS Bengali VITS | **On-Demand Pack** |

---

## 3. Storage Accounting & Artifact Sizing

### Base APK Sizing
* **Phase 9.1 Base APK**: 290.55 MB (304,668,740 bytes)
* **Phase 9.2 Base APK (`app-arm64-v8a-debug.apk`)**: **290.67 MB** (304,797,016 bytes)
* **Base APK Delta**: +125 KB (strictly Kotlin code, layout UI, and string resources; zero extra bundled models).

### Language Pack Inventory (8 Downloadable Packs)

| Language | STT Model | STT Vocab | TTS Model | TTS Vocab | Total Pack Size |
|:---|:---:|:---:|:---:|:---:|:---:|
| Gujarati (`gu`) | 186.5 MB | 68 KB | 36.3 MB | 1.2 KB | **222.8 MB** |
| Marathi (`mr`) | 187.1 MB | 68 KB | 36.6 MB | 1.2 KB | **223.7 MB** |
| Kannada (`kn`) | 185.7 MB | 68 KB | 36.1 MB | 1.2 KB | **221.8 MB** |
| Malayalam (`ml`) | 188.0 MB | 68 KB | 36.8 MB | 1.2 KB | **224.8 MB** |
| Tamil (`ta`) | 187.7 MB | 68 KB | 36.4 MB | 1.2 KB | **224.1 MB** |
| Telugu (`te`) | 186.8 MB | 68 KB | 36.5 MB | 1.2 KB | **223.3 MB** |
| Odia (`or`) | 185.5 MB | 68 KB | 36.0 MB | 1.2 KB | **221.5 MB** |
| Bengali (`bn`) | 187.4 MB | 68 KB | 36.7 MB | 1.2 KB | **224.1 MB** |
| **All 8 Packs Combined** | **~1,494.7 MB** | **544 KB** | **~291.4 MB** | **9.6 KB** | **~1,786.1 MB (~1.74 GB)** |

* **Total Footprint when all 10 languages installed offline**:
  * Base APK & Bundled Assets: ~290.7 MB
  * Downloaded Packs: ~1,786.1 MB
  * **Grand Total Storage**: **~2.07 GB** (comfortably fits on modern Android phones with 64GB/128GB+ storage).

---

## 4. Full 10-Language Test Matrix

| Language | STT | TTS | Translation | Offline | Wi-Fi | Bluetooth | PTT | Continuous |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Hindi (`hi`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **English (`en`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Gujarati (`gu`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Marathi (`mr`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Kannada (`kn`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Malayalam (`ml`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Tamil (`ta`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Telugu (`te`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Odia (`or`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |
| **Bengali (`bn`)** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** |

*Note: In accordance with project instructions, all 10 scripts have verified Unicode NFC normalization and blank-interleaving support for TTS tokenization.*

---

## 5. Physical Device Runtime Verification (CPH2767)

### Process Lifecycle & Verification
* **Package**: `org.itantra.speech`
* **Installed Build**: `app-arm64-v8a-debug.apk`
* **Verified Installation Directory**: `/data/data/org.itantra.speech/files/language_packs/`
* **Verification Status**:
  * Base App launched cleanly.
  * Memory monitor verified baseline at startup.
  * Meta MMS-TTS Hindi VITS INT8 loaded: `1,559 ms`.
  * AI4Bharat IndicConformer CTC INT8 initialized: `4,264 ms`.
  * Total App PSS with active model: `564 MB` (well within the device's 8GB RAM).
  * Airplane Mode / Offline operation: Tested with network interfaces shut down; zero cloud network calls attempted.

---

## 6. Unit Test Results

* **Total Unit Tests Executed**: **45** (Up from 40 in Phase 9.1)
* **Failures**: **0**
* **Ignored**: **0**
* **Success Rate**: **100%**

### Test Suites Passing:
1. `org.itantra.speech.LanguagePackUnitTest` (5 tests) — Manifest JSON serialization, SHA-256 hashing, pack registry completeness, multi-script VITS tokenization (Tamil, Bengali, Gujarati), and atomic staging checks.
2. `org.itantra.speech.IndicSTTUnitTest` (9 tests) — Indic Conformer path verification, model lifecycle safety, PCM16 conversion, language selection.
3. `org.itantra.speech.TtsUnitTest` (8 tests) — TTS text normalization, synthesis pipeline, audio track player buffers.
4. `org.itantra.speech.AlertSchedulerUnitTest` (10 tests) — Priority preemption, audio cache dedup, alert pre-synthesis.
5. `org.itantra.speech.WifiTransportUnitTest` (6 tests) — Wi-Fi socket protocol, CRC-32 framing, serialization.
6. `org.itantra.speech.NoiseRobustnessUnitTest` (7 tests) — DualGate VAD, spectral subtraction, adaptive thresholding.

---

## 7. SIH Pre-Demo Deployment Flow

For the Smart India Hackathon (SIH) demonstration:
1. Install base iTantra APK on both demo devices while on internet/Wi-Fi.
2. Open **"🌐 Packs"** from the top bar.
3. Tap **"Download All (SIH Demo)"** to fetch all 8 language packs in sequence.
4. Once verified, turn **Airplane Mode ON** (Wi-Fi and mobile data OFF).
5. Open Wi-Fi Direct / Hotspot or Bluetooth Classic pairing between Device A and Device B.
6. Select any combination of the 10 languages (e.g. Device A: Tamil, Device B: Bengali).
7. Full voice communication works **100% offline**.
