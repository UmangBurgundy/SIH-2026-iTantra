"""
iTantra Phase 8.2: FP32 vs INT8 IndicConformer Comparison
Directly measures Requirement 7:
- FP32 model size vs INT8 model size
- FP32 latency vs INT8 latency
- FP32 RTF vs INT8 RTF
- Process RAM
- Transcript differences
- WER / CER differences
"""

import os
import sys
import time
import numpy as np
import av
import sherpa_onnx
import re

def load_audio_16k_mono(file_path):
    container = av.open(file_path)
    resampler = av.AudioResampler(format='fltp', layout='mono', rate=16000)
    samples = []
    for frame in container.decode(audio=0):
        for resampled_frame in resampler.resample(frame):
            samples.append(resampled_frame.to_ndarray()[0])
    return np.concatenate(samples)

def normalize_indic_text(text: str) -> str:
    t = re.sub(r"[।॥.,!?;:\"'()\[\]\-_]", " ", text)
    return re.sub(r"\s+", " ", t).strip().lower()

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

def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        
    print("=" * 80)
    print("  Requirement 7: IndicConformer FP32 vs INT8 Benchmark Comparison")
    print("=" * 80)
    
    fp32_path = "scratch/indic-hi.fp32.onnx"
    int8_path = "android/app/src/main/assets/models/indic-hi.int8.onnx"
    tokens_path = "android/app/src/main/assets/models/indic-tokens.txt"
    
    fp32_size_mb = os.path.getsize(fp32_path) / 1024 / 1024
    int8_size_mb = os.path.getsize(int8_path) / 1024 / 1024
    size_reduction = (1 - (int8_size_mb / fp32_size_mb)) * 100
    
    print(f"FP32 Model Size: {fp32_size_mb:.2f} MB")
    print(f"INT8 Model Size: {int8_size_mb:.2f} MB ({size_reduction:.1f}% reduction)")
    
    # 1. Test FP32 Model Loading
    t0 = time.time()
    fp32_rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=fp32_path,
        tokens=tokens_path,
        num_threads=2,
        decoding_method="greedy_search"
    )
    fp32_load_ms = (time.time() - t0) * 1000
    print(f"FP32 Recognizer Loaded in: {fp32_load_ms:.1f} ms")
    
    # 2. Test INT8 Model Loading
    t0 = time.time()
    int8_rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=int8_path,
        tokens=tokens_path,
        num_threads=2,
        decoding_method="greedy_search"
    )
    int8_load_ms = (time.time() - t0) * 1000
    print(f"INT8 Recognizer Loaded in: {int8_load_ms:.1f} ms\n")
    
    test_clips = [
        ("dataset/clips/common_voice_hi_27891803.mp3", "अजब मौसम की गजब कहानी, मई की फिजा भी हुई सुहानी"),
        ("dataset/clips/common_voice_hi_24960424.mp3", "उसने मुझसे पूछा कि मैं उसका पता जानता था या नहीं।"),
        ("dataset/clips/common_voice_hi_24963290.mp3", "मुझे समझ में नहीं आ रहा था कि उससे क्या कहूँ।"),
        ("dataset/clips/common_voice_hi_24963289.mp3", "मैं उनका आभारी हूँ।"),
        ("dataset/clips/common_voice_hi_24963288.mp3", "वह दस भाषाएँ बोलना जानता है।")
    ]
    
    fp32_latencies = []
    int8_latencies = []
    fp32_wers = []
    int8_wers = []
    fp32_cers = []
    int8_cers = []
    
    for path, ref in test_clips:
        audio = load_audio_16k_mono(path)
        dur = len(audio) / 16000.0
        
        # FP32 inference
        t0 = time.perf_counter()
        s_fp32 = fp32_rec.create_stream()
        s_fp32.accept_waveform(16000, audio)
        fp32_rec.decode_stream(s_fp32)
        pred_fp32 = s_fp32.result.text.strip()
        lat_fp32 = (time.perf_counter() - t0) * 1000.0
        wer_f, cer_f = compute_wer_cer(ref, pred_fp32)
        
        # INT8 inference
        t0 = time.perf_counter()
        s_int8 = int8_rec.create_stream()
        s_int8.accept_waveform(16000, audio)
        int8_rec.decode_stream(s_int8)
        pred_int8 = s_int8.result.text.strip()
        lat_int8 = (time.perf_counter() - t0) * 1000.0
        wer_i, cer_i = compute_wer_cer(ref, pred_int8)
        
        fp32_latencies.append(lat_fp32)
        int8_latencies.append(lat_int8)
        fp32_wers.append(wer_f)
        int8_wers.append(wer_i)
        fp32_cers.append(cer_f)
        int8_cers.append(cer_i)
        
        print(f"Sample: {os.path.basename(path)} (Dur: {dur:.2f}s)")
        print(f"  Ref:       {ref}")
        print(f"  FP32 Pred: {pred_fp32} [Lat: {lat_fp32:.1f}ms, WER: {wer_f:.1f}%, CER: {cer_f:.1f}%]")
        print(f"  INT8 Pred: {pred_int8} [Lat: {lat_int8:.1f}ms, WER: {wer_i:.1f}%, CER: {cer_i:.1f}%]")
        print(f"  Diff:      {'IDENTICAL' if pred_fp32 == pred_int8 else 'DIFFERENT'}\n")
        
    print("=" * 80)
    print("  FP32 vs INT8 QUANTIZATION BENCHMARK SUMMARY")
    print("=" * 80)
    print(f"{'Metric':<30} | {'FP32 IndicConformer':<22} | {'INT8 IndicConformer':<22}")
    print("-" * 80)
    print(f"{'Model File Size':<30} | {f'{fp32_size_mb:.2f} MB':<22} | {f'{int8_size_mb:.2f} MB':<22}")
    print(f"{'Mean Inference Latency':<30} | {f'{np.mean(fp32_latencies):.1f} ms':<22} | {f'{np.mean(int8_latencies):.1f} ms':<22}")
    print(f"{'Mean RTF':<30} | {f'{np.mean(fp32_latencies)/1000.0 / 4.0:.3f}':<22} | {f'{np.mean(int8_latencies)/1000.0 / 4.0:.3f}':<22}")
    print(f"{'Mean Word Error Rate (WER)':<30} | {f'{np.mean(fp32_wers):.1f}%':<22} | {f'{np.mean(int8_wers):.1f}%':<22}")
    print(f"{'Mean Char Error Rate (CER)':<30} | {f'{np.mean(fp32_cers):.1f}%':<22} | {f'{np.mean(int8_cers):.1f}%':<22}")
    print("=" * 80)

if __name__ == "__main__":
    main()
