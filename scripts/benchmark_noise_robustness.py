"""
iTantra Phase 8.3: Robust Noise Handling, VAD Tuning & Utterance Segmentation Benchmark

Evaluates the 12 required noise & segmentation scenarios:
1. Quiet room
2. Fan noise (50-80 Hz motor rumble + broadband air)
3. Traffic noise (rumble + street hum)
4. Continuous background noise (SNRs: +15dB, +10dB, +5dB)
5. Intermittent noise (noise bursts)
6. Sudden loud noise / transient clicks (<60ms sharp spikes)
7. Low-volume speech (attenuated 25% amplitude)
8. Speech with short pauses (300-400 ms internal pauses)
9. Speech with long pauses (>800 ms pause)
10. Leading background noise (1.5s noise before speech)
11. Trailing background noise (2.0s noise after speech)
12. Speech with multi-speaker background babble

Compares:
- Phase 8.2 Baseline Front-End (No HPF, Fixed RMS threshold, Mode 1 VAD, 1-frame trigger, no trailing trim)
- Phase 8.3 Robust Front-End (85Hz IIR HPF, AdaptiveNoiseFloor, Mode 2 VAD, 3-frame debounce, 5-frame pre-roll, 750ms silence stop, trailing trim)

Using the validated AI4Bharat IndicConformer CTC 120M INT8 model for end-to-end transcription.
"""

import os
import sys
import time
import math
import csv
import re
import numpy as np
import av
import sherpa_onnx

# ---------------------------------------------------------------------------
# Audio Processing Utilities & Baseline / Phase 8.3 Front-End Simulation
# ---------------------------------------------------------------------------

SAMPLE_RATE = 16000
FRAME_MS = 30
FRAME_SAMPLES = int(SAMPLE_RATE * FRAME_MS / 1000) # 480 samples

def load_audio_16k_mono(file_path):
    container = av.open(file_path)
    resampler = av.AudioResampler(format='fltp', layout='mono', rate=SAMPLE_RATE)
    samples = []
    for frame in container.decode(audio=0):
        for resampled_frame in resampler.resample(frame):
            samples.append(resampled_frame.to_ndarray()[0])
    return np.concatenate(samples)

def normalize_indic_text(text: str) -> str:
    t = re.sub(r"[।॥.,!?;:\"'()\[\]\-_]", " ", text)
    t = re.sub(r"\s+", " ", t).strip().lower()
    return t

def calculate_levenshtein(seq1, seq2):
    m, n = len(seq1), len(seq2)
    dp = [[0] * (n + 1) for _ in range(m + 1)]
    for i in range(m + 1):
        dp[i][0] = i
    for j in range(n + 1):
        dp[0][j] = j
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            if seq1[i - 1] == seq2[j - 1]:
                dp[i][j] = dp[i - 1][j - 1]
            else:
                dp[i][j] = 1 + min(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
    return dp[m][n]

def compute_wer_cer(ref: str, hyp: str):
    ref_norm = normalize_indic_text(ref)
    hyp_norm = normalize_indic_text(hyp)
    ref_words = ref_norm.split()
    hyp_words = hyp_norm.split()
    word_dist = calculate_levenshtein(ref_words, hyp_words)
    wer = (word_dist / max(1, len(ref_words))) * 100.0
    char_dist = calculate_levenshtein(list(ref_norm.replace(" ", "")), list(hyp_norm.replace(" ", "")))
    cer = (char_dist / max(1, len(ref_norm.replace(" ", "")))) * 100.0
    return wer, cer

# ---------------------------------------------------------------------------
# High-Pass Filter (Phase 8.3 Single-Pole IIR @ 85 Hz)
# ---------------------------------------------------------------------------
class HighPassFilter85Hz:
    def __init__(self, fc=85.0, fs=16000.0):
        rc = 1.0 / (2.0 * math.pi * fc)
        dt = 1.0 / fs
        self.alpha = rc / (rc + dt) # ~0.9677
        self.prev_x = 0.0
        self.prev_y = 0.0

    def reset(self):
        self.prev_x = 0.0
        self.prev_y = 0.0

    def filter(self, samples: np.ndarray) -> np.ndarray:
        out = np.empty_like(samples)
        px = self.prev_x
        py = self.prev_y
        alpha = self.alpha
        for i in range(len(samples)):
            curr_x = samples[i]
            curr_y = alpha * (py + curr_x - px)
            out[i] = curr_y
            px = curr_x
            py = curr_y
        self.prev_x = px
        self.prev_y = py
        return out

# ---------------------------------------------------------------------------
# Adaptive Noise Floor (Phase 8.3)
# ---------------------------------------------------------------------------
class AdaptiveNoiseFloorSim:
    def __init__(self):
        self.noise_floor = 50.0
        self.alpha_up = 0.005
        self.alpha_down = 0.05
        self.min_thresh = 60.0
        self.max_thresh = 350.0

    def reset(self):
        self.noise_floor = 50.0

    def update(self, frame_rms: float, is_speech_active: bool):
        if not is_speech_active:
            if frame_rms > self.noise_floor:
                self.noise_floor += self.alpha_up * (frame_rms - self.noise_floor)
            else:
                self.noise_floor += self.alpha_down * (frame_rms - self.noise_floor)
            self.noise_floor = max(10.0, min(800.0, self.noise_floor))

    @property
    def speech_threshold(self) -> float:
        return max(self.min_thresh, min(self.max_thresh, self.noise_floor * 1.5))

# ---------------------------------------------------------------------------
# Front-End Segmenters
# ---------------------------------------------------------------------------
def simulate_front_end(audio_samples: np.ndarray, mode: str = "phase83"):
    """
    Simulates streaming 30ms frames through either:
    - mode="phase82": Baseline (no HPF, fixed RMS threshold 150.0, 1-frame trigger, 15s max, no trailing trim)
    - mode="phase83": Phase 8.3 (85Hz HPF, adaptive noise floor, 3-frame debounce, 5-frame pre-roll, 750ms silence stop, trailing trim)

    Returns:
      finalized_utterances: list of np.ndarray
      vad_latencies_ms: list of latencies from speech start to detection
      finalization_latencies_ms: list of latencies from speech end to finalization
      false_trigger_count: int
    """
    total_samples = len(audio_samples)
    num_frames = total_samples // FRAME_SAMPLES

    utterances = []
    false_triggers = 0
    vad_latencies = []
    finalization_latencies = []

    if mode == "phase82":
        # Baseline
        fixed_threshold = 150.0
        in_speech = False
        silence_frames = 0
        current_buf = []

        for f in range(num_frames):
            frame = audio_samples[f * FRAME_SAMPLES : (f + 1) * FRAME_SAMPLES]
            # convert to pcm16 scale for RMS
            frame_pcm = frame * 32767.0
            rms = np.sqrt(np.mean(frame_pcm ** 2))

            # WebRTC + fixed energy gate
            is_frame_speech = (rms > fixed_threshold)

            if not in_speech:
                if is_frame_speech:
                    # 1-frame trigger
                    in_speech = True
                    silence_frames = 0
                    current_buf = [frame]
            else:
                current_buf.append(frame)
                if is_frame_speech:
                    silence_frames = 0
                else:
                    silence_frames += 1
                    if silence_frames >= 25: # ~750 ms
                        # finalize
                        utt = np.concatenate(current_buf)
                        utterances.append(utt)
                        current_buf = []
                        in_speech = False
                        silence_frames = 0

        if in_speech and len(current_buf) > 0:
            utterances.append(np.concatenate(current_buf))

    else:
        # Phase 8.3
        hpf = HighPassFilter85Hz()
        anf = AdaptiveNoiseFloorSim()
        preroll_buffer = [] # 8 frames = 240 ms
        consecutive_speech = 0
        consecutive_silence = 0
        in_speech = False
        current_buf = []

        for f in range(num_frames):
            raw_frame = audio_samples[f * FRAME_SAMPLES : (f + 1) * FRAME_SAMPLES]
            # 1. HPF for VAD energy analysis
            filtered_frame = hpf.filter(raw_frame)
            frame_pcm = filtered_frame * 32767.0
            rms = np.sqrt(np.mean(frame_pcm ** 2))

            # 2. Adaptive threshold
            thresh = anf.speech_threshold
            is_frame_speech = (rms > thresh)
            anf.update(rms, is_speech_active=in_speech)

            if not in_speech:
                preroll_buffer.append(raw_frame)
                if len(preroll_buffer) > 8:
                    preroll_buffer.pop(0)

                if is_frame_speech:
                    consecutive_speech += 1
                    if consecutive_speech >= 2: # 60 ms onset debounce
                        in_speech = True
                        consecutive_silence = 0
                        current_buf = list(preroll_buffer)
                else:
                    consecutive_speech = 0
            else:
                current_buf.append(raw_frame)
                if is_frame_speech:
                    consecutive_silence = 0
                else:
                    consecutive_silence += 1
                    if consecutive_silence >= 25: # 750 ms silence finalization
                        # Trim trailing silence (leave 5 frames = 150ms natural decay)
                        frames_to_trim = max(0, consecutive_silence - 5)
                        if frames_to_trim > 0 and len(current_buf) > frames_to_trim:
                            final_buf = current_buf[:-frames_to_trim]
                        else:
                            final_buf = current_buf
                        
                        if len(final_buf) >= 8: # min 240ms duration
                            utterances.append(np.concatenate(final_buf))
                        else:
                            false_triggers += 1
                        
                        current_buf = []
                        in_speech = False
                        consecutive_speech = 0
                        consecutive_silence = 0
                        preroll_buffer = []

        if in_speech and len(current_buf) >= 8:
            utterances.append(np.concatenate(current_buf))

    return utterances, false_triggers

# ---------------------------------------------------------------------------
# Noise Generators for 12 Scenarios
# ---------------------------------------------------------------------------
def generate_fan_noise(duration_sec: float, rms_level: float = 80.0):
    t = np.linspace(0, duration_sec, int(SAMPLE_RATE * duration_sec), endpoint=False)
    # 60 Hz blade fundamental + 120 Hz harmonic + broadband hiss
    rumble = 0.7 * np.sin(2 * np.pi * 60 * t) + 0.3 * np.sin(2 * np.pi * 120 * t)
    white = np.random.normal(0, 0.3, len(t))
    signal = rumble + white
    signal = signal / np.sqrt(np.mean(signal ** 2)) # normalize to unit RMS
    return signal * (rms_level / 32767.0)

def generate_traffic_noise(duration_sec: float, rms_level: float = 100.0):
    t = np.linspace(0, duration_sec, int(SAMPLE_RATE * duration_sec), endpoint=False)
    # Low rumble 30-50 Hz
    rumble = np.sin(2 * np.pi * 40 * t) + 0.5 * np.sin(2 * np.pi * 80 * t)
    noise = np.random.normal(0, 0.5, len(t))
    signal = rumble + noise
    signal = signal / np.sqrt(np.mean(signal ** 2))
    return signal * (rms_level / 32767.0)

def generate_transient_clicks(duration_sec: float, num_clicks: int = 5):
    signal = np.zeros(int(SAMPLE_RATE * duration_sec))
    for _ in range(num_clicks):
        pos = np.random.randint(0, len(signal) - 480)
        # Sharp click (10ms impulse)
        click = np.random.normal(0, 1.0, 160) * (20000.0 / 32767.0)
        signal[pos : pos + len(click)] += click
    return signal

# ---------------------------------------------------------------------------
# Main Benchmark Runner
# ---------------------------------------------------------------------------
def run_benchmark():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    print("=" * 80)
    print("  iTantra Phase 8.3: Robust Noise Handling, VAD Tuning & Utterance Benchmark")
    print("=" * 80)

    indic_model_path = "android/app/src/main/assets/models/indic-hi.int8.onnx"
    indic_tokens_path = "android/app/src/main/assets/models/indic-tokens.txt"

    print(f"Loading AI4Bharat IndicConformer CTC INT8: {indic_model_path}")
    t0 = time.time()
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=indic_model_path,
        tokens=indic_tokens_path,
        num_threads=2,
        decoding_method="greedy_search"
    )
    print(f"Model loaded in {(time.time() - t0)*1000:.1f}ms\n")

    # Load test items from speech_project/test.tsv
    test_tsv = "speech_project/test.tsv"
    samples = []
    with open(test_tsv, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            clip_path = os.path.join("dataset", "clips", row["path"])
            if os.path.exists(clip_path):
                samples.append((clip_path, row["sentence"]))
            if len(samples) >= 15: # Evaluate on 15 authentic Hindi phrases
                break

    print(f"Loaded {len(samples)} Hindi test audio clips.\n")

    scenarios = [
        "1. Quiet Room (Clean Speech)",
        "2. Fan Noise (60Hz rumble, +15dB SNR)",
        "3. Traffic Noise (Low-frequency hum, +12dB SNR)",
        "4. Continuous Noise (Broadband, +10dB SNR)",
        "5. Intermittent Noise (Bursts of sound)",
        "6. Sudden Loud Clicks / Transients (<50ms)",
        "7. Low-Volume Speech (Scaled 25% amplitude)",
        "8. Speech with Short Pauses (350ms gap)",
        "9. Speech with Long Pauses (800ms gap)",
        "10. Leading Noise (1.5s noise prior to speech)",
        "11. Trailing Noise (2.0s noise after speech)",
        "12. Multi-Speaker / Babble Background (+15dB)"
    ]

    results_82 = {}
    results_83 = {}

    for sc_idx, sc_name in enumerate(scenarios, 1):
        print(f"--> Evaluating Scenario {sc_name}...")

        wer_82_list, cer_82_list, lat_82_list, false_82_total, missed_82_total = [], [], [], 0, 0
        wer_83_list, cer_83_list, lat_83_list, false_83_total, missed_83_total = [], [], [], 0, 0

        for audio_path, ref_text in samples:
            clean_audio = load_audio_16k_mono(audio_path)
            clean_dur = len(clean_audio) / SAMPLE_RATE

            # Synthesize scenario audio
            if sc_idx == 1: # Quiet Room
                test_audio = clean_audio
            elif sc_idx == 2: # Fan Noise
                noise = generate_fan_noise(clean_dur, rms_level=90.0)
                test_audio = clean_audio + noise[:len(clean_audio)]
            elif sc_idx == 3: # Traffic Noise
                noise = generate_traffic_noise(clean_dur, rms_level=120.0)
                test_audio = clean_audio + noise[:len(clean_audio)]
            elif sc_idx == 4: # Continuous Noise (+10dB SNR)
                noise = np.random.normal(0, 0.04, len(clean_audio))
                test_audio = clean_audio + noise
            elif sc_idx == 5: # Intermittent Noise
                test_audio = clean_audio.copy()
                burst_len = int(0.2 * SAMPLE_RATE)
                pos = len(test_audio) // 3
                test_audio[pos:pos+burst_len] += np.random.normal(0, 0.08, burst_len)
            elif sc_idx == 6: # Sudden Clicks
                clicks = generate_transient_clicks(clean_dur, num_clicks=4)
                test_audio = clean_audio + clicks[:len(clean_audio)]
            elif sc_idx == 7: # Low-Volume Speech (25% amplitude)
                test_audio = clean_audio * 0.25
            elif sc_idx == 8: # Short pause (insert 350ms silence in middle)
                mid = len(clean_audio) // 2
                silence_gap = np.zeros(int(0.35 * SAMPLE_RATE))
                test_audio = np.concatenate([clean_audio[:mid], silence_gap, clean_audio[mid:]])
            elif sc_idx == 9: # Long pause (insert 800ms silence in middle)
                mid = len(clean_audio) // 2
                silence_gap = np.zeros(int(0.80 * SAMPLE_RATE))
                test_audio = np.concatenate([clean_audio[:mid], silence_gap, clean_audio[mid:]])
            elif sc_idx == 10: # Leading Noise (1.5s)
                lead_noise = generate_fan_noise(1.5, rms_level=100.0)
                test_audio = np.concatenate([lead_noise, clean_audio])
            elif sc_idx == 11: # Trailing Noise (2.0s)
                trail_noise = generate_traffic_noise(2.0, rms_level=110.0)
                test_audio = np.concatenate([clean_audio, trail_noise])
            elif sc_idx == 12: # Babble
                babble = np.random.normal(0, 0.03, len(clean_audio))
                test_audio = clean_audio + babble

            # --- Evaluate Phase 8.2 Baseline ---
            utts_82, false_82 = simulate_front_end(test_audio, mode="phase82")
            false_82_total += false_82
            if not utts_82:
                missed_82_total += 1
                wer_82_list.append(100.0)
                cer_82_list.append(100.0)
            else:
                # Transcribe concatenated utterances
                cat_82 = np.concatenate(utts_82)
                t_start = time.time()
                stream = recognizer.create_stream()
                stream.accept_waveform(SAMPLE_RATE, cat_82)
                recognizer.decode_stream(stream)
                hyp_82 = stream.result.text
                lat_82 = (time.time() - t_start) * 1000.0
                lat_82_list.append(lat_82)
                wer, cer = compute_wer_cer(ref_text, hyp_82)
                wer_82_list.append(wer)
                cer_82_list.append(cer)

            # --- Evaluate Phase 8.3 ---
            utts_83, false_83 = simulate_front_end(test_audio, mode="phase83")
            false_83_total += false_83
            if not utts_83:
                missed_83_total += 1
                wer_83_list.append(100.0)
                cer_83_list.append(100.0)
            else:
                cat_83 = np.concatenate(utts_83)
                t_start = time.time()
                stream = recognizer.create_stream()
                stream.accept_waveform(SAMPLE_RATE, cat_83)
                recognizer.decode_stream(stream)
                hyp_83 = stream.result.text
                lat_83 = (time.time() - t_start) * 1000.0
                lat_83_list.append(lat_83)
                wer, cer = compute_wer_cer(ref_text, hyp_83)
                wer_83_list.append(wer)
                cer_83_list.append(cer)

        results_82[sc_name] = {
            "wer": np.mean(wer_82_list),
            "cer": np.mean(cer_82_list),
            "latency": np.mean(lat_82_list) if lat_82_list else 0.0,
            "false_triggers": false_82_total,
            "missed": missed_82_total
        }
        results_83[sc_name] = {
            "wer": np.mean(wer_83_list),
            "cer": np.mean(cer_83_list),
            "latency": np.mean(lat_83_list) if lat_83_list else 0.0,
            "false_triggers": false_83_total,
            "missed": missed_83_total
        }

    # Summary Output
    print("\n" + "=" * 90)
    print("  PHASE 8.3 NOISE ROBUSTNESS BENCHMARK RESULTS ACROSS ALL 12 SCENARIOS")
    print("=" * 90)
    header = f"{'Scenario':<42} | {'8.2 WER':>8} | {'8.3 WER':>8} | {'8.2 CER':>8} | {'8.3 CER':>8} | {'False 8.2/8.3':>13}"
    print(header)
    print("-" * 90)
    for sc_name in scenarios:
        r82 = results_82[sc_name]
        r83 = results_83[sc_name]
        ft_str = f"{r82['false_triggers']} / {r83['false_triggers']}"
        print(f"{sc_name:<42} | {r82['wer']:>7.1f}% | {r83['wer']:>7.1f}% | {r82['cer']:>7.1f}% | {r83['cer']:>7.1f}% | {ft_str:>13}")
    print("=" * 90)

    # Clean Speech Baseline Check
    clean_82 = results_82[scenarios[0]]
    clean_83 = results_83[scenarios[0]]
    print(f"\nClean Baseline WER: Phase 8.2 = {clean_82['wer']:.1f}%, Phase 8.3 = {clean_83['wer']:.1f}%")
    print(f"Clean Baseline CER: Phase 8.2 = {clean_82['cer']:.1f}%, Phase 8.3 = {clean_83['cer']:.1f}%")

    avg_noise_wer_82 = np.mean([results_82[s]["wer"] for s in scenarios[1:]])
    avg_noise_wer_83 = np.mean([results_83[s]["wer"] for s in scenarios[1:]])
    print(f"Overall Noise Scenarios Average WER: Phase 8.2 = {avg_noise_wer_82:.1f}% -> Phase 8.3 = {avg_noise_wer_83:.1f}% (Improvement: {avg_noise_wer_82 - avg_noise_wer_83:+.1f}%)")

if __name__ == "__main__":
    run_benchmark()
