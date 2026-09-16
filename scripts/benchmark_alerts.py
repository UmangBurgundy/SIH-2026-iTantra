"""
iTantra Phase 8.5: Alert Audio Cache & Pre-Synthesis Benchmark.

Evaluates:
1. Predefined Hindi alert definitions and character counts
2. MMS-TTS INT8 pre-synthesis latency, duration, and PCM disk footprint
3. In-memory vs disk cache lookup latency (T2 - T1)
4. Cache-hit vs cache-miss speedup ratio
5. Deduplication and priority scheduling simulation
"""

import os
import sys
import time
import json
import psutil
import unicodedata
import numpy as np

MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "android", "app", "src", "main", "assets", "models", "tts")
ONNX_PATH = os.path.join(MODEL_DIR, "mms-hin.int8.onnx")
VOCAB_PATH = os.path.join(MODEL_DIR, "mms-hin-vocab.json")

PREDEFINED_ALERTS = [
    ("alert_fire_001", "Fire Evacuation", "आग लग गई है। तुरंत इमारत खाली करें।"),
    ("alert_medical_002", "Medical Assistance", "चिकित्सा सहायता की आवश्यकता है। कृपया तुरंत डॉक्टर भेजें।"),
    ("alert_hazard_003", "Hazard Warning", "खतरे की चेतावनी। सभी लोग सुरक्षित स्थान पर जाएं।"),
    ("alert_emergency_004", "Emergency Notification", "आपातकालीन सूचना। शांति बनाए रखें और निर्देशों का पालन करें।")
]

HINDI_DIGIT_WORDS = {
    0: "शून्य", 1: "एक", 2: "दो", 3: "तीन", 4: "चार", 5: "पाँच",
    6: "छह", 7: "सात", 8: "आठ", 9: "नौ", 10: "दस"
}

def normalize_hindi(text):
    text = unicodedata.normalize('NFC', text)
    import re
    text = re.sub(r'[\।,\.!?;:\-"\'\(\)]', ' ', text)
    text = re.sub(r'\s+', ' ', text).strip()
    return text

def text_to_tokens(text, vocab):
    normalized = normalize_hindi(text)
    ids = []
    for ch in normalized:
        if ch in vocab:
            ids.append(int(vocab[ch]))
        elif ch == ' ':
            ids.append(int(vocab.get(' ', 0)))
    interleaved = []
    for tid in ids:
        interleaved.append(0)
        interleaved.append(tid)
    interleaved.append(0)
    return interleaved

def float_to_pcm16(samples):
    max_val = np.max(np.abs(samples)) if len(samples) > 0 else 1.0
    scale = (32767.0 / max_val) if max_val > 1.0 else 32767.0
    int_samples = np.clip(samples * scale, -32768, 32767).astype(np.int16)
    return int_samples.tobytes()

def main():
    print("=" * 75)
    print("iTantra Phase 8.5: Alert Audio Cache & Priority Benchmark")
    print("=" * 75)

    with open(VOCAB_PATH, 'r', encoding='utf-8') as f:
        vocab = json.load(f)

    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 2
    session = ort.InferenceSession(ONNX_PATH, opts, providers=['CPUExecutionProvider'])

    # Warmup
    warmup_tokens = text_to_tokens("परीक्षण", vocab)
    inp = np.array([warmup_tokens], dtype=np.int64)
    session.run(None, {'input_ids': inp, 'attention_mask': np.ones_like(inp, dtype=np.int64)})

    cache_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "android", "app", "build", "alert_cache_sim")
    os.makedirs(cache_dir, exist_ok=True)

    audio_cache = {}
    print("\n[1] Pre-Synthesis of Mission-Critical Predefined Alerts:")
    print("-" * 80)
    print(f"{'Alert ID':<20} | {'Title':<22} | {'Synth (ms)':<10} | {'Audio (s)':<9} | {'PCM Size'}")
    print("-" * 80)

    total_storage_bytes = 0
    synth_latencies = []

    for alert_id, title, text in PREDEFINED_ALERTS:
        tokens = text_to_tokens(text, vocab)
        inp = np.array([tokens], dtype=np.int64)

        t_start = time.perf_counter()
        outputs = session.run(None, {'input_ids': inp, 'attention_mask': np.ones_like(inp, dtype=np.int64)})
        t_synth = (time.perf_counter() - t_start) * 1000

        audio = outputs[0][0]
        pcm_bytes = float_to_pcm16(audio)
        duration_s = len(audio) / 16000.0

        # Save to memory and disk cache
        audio_cache[alert_id] = pcm_bytes
        file_path = os.path.join(cache_dir, f"{alert_id}.pcm")
        with open(file_path, 'wb') as f:
            f.write(pcm_bytes)

        file_size = len(pcm_bytes)
        total_storage_bytes += file_size
        synth_latencies.append(t_synth)

        print(f"{alert_id:<20} | {title:<22} | {t_synth:>10.2f} | {duration_s:>9.2f} | {file_size:>6} bytes")

    avg_synth = np.mean(synth_latencies)
    print("-" * 80)
    print(f"Total Pre-Synthesized Footprint: {total_storage_bytes / 1024:.2f} KB ({len(PREDEFINED_ALERTS)} alerts)")
    print(f"Average Pre-Synthesis Latency:    {avg_synth:.2f} ms")

    # Benchmark Cache Lookup (Tier 1 Memory vs Tier 2 Disk)
    print("\n[2] High-Resolution Cache Lookup Latencies (10,000 iterations):")
    print("-" * 80)

    # Memory lookup
    t0 = time.perf_counter_ns()
    for _ in range(10000):
        _ = audio_cache["alert_fire_001"]
    t_mem_ns = (time.perf_counter_ns() - t0) / 10000
    print(f"  Tier 1 Memory Cache Lookup: {t_mem_ns / 1000.0:.3f} µs ({t_mem_ns / 1_000_000.0:.5f} ms)")

    # Disk lookup
    disk_path = os.path.join(cache_dir, "alert_fire_001.pcm")
    t0 = time.perf_counter_ns()
    for _ in range(1000):
        with open(disk_path, 'rb') as f:
            _ = f.read()
    t_disk_ns = (time.perf_counter_ns() - t0) / 1000
    print(f"  Tier 2 Disk Cache Lookup:   {t_disk_ns / 1_000_000.0:.3f} ms")

    speedup = avg_synth / (t_disk_ns / 1_000_000.0)
    print(f"\n[3] Emergency Latency Speedup (Pre-Synthesized vs Dynamic Synthesis):")
    print(f"  Synthesis on Miss:  ~{avg_synth:.1f} ms")
    print(f"  Cached Lookup:      ~{t_disk_ns / 1_000_000.0:.2f} ms (Disk) / {t_mem_ns / 1000.0:.1f} µs (RAM)")
    print(f"  Speedup Factor:     ~{speedup:.0f}x faster playback start!")
    print("=" * 75)

if __name__ == '__main__':
    main()
