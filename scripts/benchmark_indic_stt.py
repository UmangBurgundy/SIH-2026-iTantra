"""
iTantra Phase 8.2: Native Indic STT Accuracy & Comparison Benchmark
Benchmarks AI4Bharat IndicConformer CTC INT8 against Whisper-Tiny INT8 on real Hindi speech samples.
Measures:
- Audio Duration (sec)
- Latency (ms)
- Real-Time Factor (RTF)
- WER & CER (raw and normalized)
- Native script fidelity (Devanagari \u0900-\u097F)
"""

import os
import sys
import time
import csv
import re
import numpy as np
import av
import sherpa_onnx

def load_audio_16k_mono(file_path):
    container = av.open(file_path)
    resampler = av.AudioResampler(format='fltp', layout='mono', rate=16000)
    samples = []
    for frame in container.decode(audio=0):
        for resampled_frame in resampler.resample(frame):
            samples.append(resampled_frame.to_ndarray()[0])
    return np.concatenate(samples)

def normalize_indic_text(text: str) -> str:
    """
    Standard normalizer for Indic ASR evaluation:
    - Removes punctuation (danda ।, commas, dots, exclamation, question marks)
    - Normalizes whitespace
    - Lowercases any Latin characters
    """
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
    
    return wer, cer, ref_norm, hyp_norm

def is_devanagari(text: str) -> bool:
    devanagari_chars = [c for c in text if "\u0900" <= c <= "\u097F"]
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return False
    return (len(devanagari_chars) / len(letters)) >= 0.70

def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    
    print("=" * 80)
    print("  iTantra Phase 8.2: Native Indic STT vs Whisper-Tiny Benchmark")
    print("=" * 80)
    
    indic_model_path = "android/app/src/main/assets/models/indic-hi.int8.onnx"
    indic_tokens_path = "android/app/src/main/assets/models/indic-tokens.txt"
    whisper_enc_path = "android/app/src/main/assets/models/tiny-encoder.int8.onnx"
    whisper_dec_path = "android/app/src/main/assets/models/tiny-decoder.int8.onnx"
    whisper_tokens_path = "android/app/src/main/assets/models/tiny-tokens.txt"
    
    # 1. Load IndicConformer
    t0 = time.time()
    indic_recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=indic_model_path,
        tokens=indic_tokens_path,
        num_threads=2,
        decoding_method="greedy_search"
    )
    indic_load_ms = (time.time() - t0) * 1000
    print(f"IndicConformer (CTC INT8) loaded in {indic_load_ms:.1f} ms")
    
    # 2. Load Whisper-Tiny
    t0 = time.time()
    whisper_recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=whisper_enc_path,
        decoder=whisper_dec_path,
        tokens=whisper_tokens_path,
        language="hi",
        task="transcribe",
        num_threads=2
    )
    whisper_load_ms = (time.time() - t0) * 1000
    print(f"Whisper-Tiny (INT8) loaded in {whisper_load_ms:.1f} ms\n")
    
    # Select 10 real test clips from speech_project/test.tsv
    tsv_path = "speech_project/test.tsv"
    test_samples = []
    with open(tsv_path, encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            clip_name = row["path"]
            full_clip_path = os.path.join("dataset/clips", clip_name)
            if os.path.exists(full_clip_path):
                test_samples.append({
                    "path": full_clip_path,
                    "filename": clip_name,
                    "reference": row["sentence"].strip()
                })
            if len(test_samples) >= 10:
                break
                
    print(f"Selected {len(test_samples)} real speech utterances for benchmarking:\n")
    
    indic_results = []
    whisper_results = []
    
    for i, s in enumerate(test_samples, 1):
        audio = load_audio_16k_mono(s["path"])
        dur = len(audio) / 16000.0
        
        # Test IndicConformer
        t0 = time.perf_counter()
        stream_i = indic_recognizer.create_stream()
        stream_i.accept_waveform(16000, audio)
        indic_recognizer.decode_stream(stream_i)
        pred_indic = stream_i.result.text.strip()
        lat_indic = (time.perf_counter() - t0) * 1000.0
        rtf_indic = (lat_indic / 1000.0) / dur if dur > 0 else 0.0
        wer_i, cer_i, ref_n, hyp_i_n = compute_wer_cer(s["reference"], pred_indic)
        is_dev_i = is_devanagari(pred_indic)
        
        # Test Whisper-Tiny
        t0 = time.perf_counter()
        stream_w = whisper_recognizer.create_stream()
        stream_w.accept_waveform(16000, audio)
        whisper_recognizer.decode_stream(stream_w)
        pred_whisper = stream_w.result.text.strip()
        lat_whisper = (time.perf_counter() - t0) * 1000.0
        rtf_whisper = (lat_whisper / 1000.0) / dur if dur > 0 else 0.0
        wer_w, cer_w, _, hyp_w_n = compute_wer_cer(s["reference"], pred_whisper)
        is_dev_w = is_devanagari(pred_whisper)
        
        indic_results.append({
            "dur": dur, "lat": lat_indic, "rtf": rtf_indic,
            "wer": wer_i, "cer": cer_i, "pred": pred_indic, "is_dev": is_dev_i
        })
        whisper_results.append({
            "dur": dur, "lat": lat_whisper, "rtf": rtf_whisper,
            "wer": wer_w, "cer": cer_w, "pred": pred_whisper, "is_dev": is_dev_w
        })
        
        print(f"--- Utterance {i}: {s['filename']} (Duration: {dur:.2f}s) ---")
        print(f"  Reference:       {s['reference']}")
        print(f"  IndicConformer:  {pred_indic}")
        print(f"                   [Lat: {lat_indic:.1f}ms | RTF: {rtf_indic:.3f} | WER: {wer_i:.1f}% | CER: {cer_i:.1f}% | Devanagari: {is_dev_i}]")
        print(f"  Whisper-Tiny:    {pred_whisper}")
        print(f"                   [Lat: {lat_whisper:.1f}ms | RTF: {rtf_whisper:.3f} | WER: {wer_w:.1f}% | CER: {cer_w:.1f}% | Devanagari: {is_dev_w}]")
        print()
        
    avg_dur = np.mean([r["dur"] for r in indic_results])
    avg_lat_i = np.mean([r["lat"] for r in indic_results])
    avg_rtf_i = np.mean([r["rtf"] for r in indic_results])
    avg_wer_i = np.mean([r["wer"] for r in indic_results])
    avg_cer_i = np.mean([r["cer"] for r in indic_results])
    dev_rate_i = np.mean([1.0 if r["is_dev"] else 0.0 for r in indic_results]) * 100.0
    
    avg_lat_w = np.mean([r["lat"] for r in whisper_results])
    avg_rtf_w = np.mean([r["rtf"] for r in whisper_results])
    avg_wer_w = np.mean([r["wer"] for r in whisper_results])
    avg_cer_w = np.mean([r["cer"] for r in whisper_results])
    dev_rate_w = np.mean([1.0 if r["is_dev"] else 0.0 for r in whisper_results]) * 100.0
    
    print("=" * 80)
    print("  SUMMARY BENCHMARK COMPARISON (10 Hindi Speech Utterances)")
    print("=" * 80)
    print(f"{'Metric':<30} | {'Whisper-Tiny (INT8)':<22} | {'IndicConformer (INT8)':<22}")
    print("-" * 80)
    print(f"{'Model Parameters':<30} | {'39M':<22} | {'120M':<22}")
    print(f"{'On-Disk Size':<30} | {'103.6 MB':<22} | {'188.5 MB':<22}")
    print(f"{'Mean Latency':<30} | {f'{avg_lat_w:.1f} ms':<22} | {f'{avg_lat_i:.1f} ms':<22}")
    print(f"{'Mean Real-Time Factor (RTF)':<30} | {f'{avg_rtf_w:.3f}':<22} | {f'{avg_rtf_i:.3f}':<22}")
    print(f"{'Devanagari Script Fidelity':<30} | {f'{dev_rate_w:.1f}%':<22} | {f'{dev_rate_i:.1f}%':<22}")
    print(f"{'Mean Word Error Rate (WER)':<30} | {f'{avg_wer_w:.1f}%':<22} | {f'{avg_wer_i:.1f}%':<22}")
    print(f"{'Mean Char Error Rate (CER)':<30} | {f'{avg_cer_w:.1f}%':<22} | {f'{avg_cer_i:.1f}%':<22}")
    print("=" * 80)

if __name__ == "__main__":
    main()
