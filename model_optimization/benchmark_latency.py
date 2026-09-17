import os
import sys
import time
import json
import numpy as np
import soundfile as sf
import sherpa_onnx
import onnxruntime as ort

sys.stdout.reconfigure(encoding='utf-8')

def benchmark_stt(model_path, tokens_path, audio_path, runs=10):
    audio, sr = sf.read(audio_path)
    audio_f32 = audio.astype(np.float32)
    
    t_init_0 = time.perf_counter()
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=model_path,
        tokens=tokens_path,
        num_threads=2
    )
    init_time_ms = (time.perf_counter() - t_init_0) * 1000
    
    latencies = []
    text = ""
    for r in range(runs):
        t0 = time.perf_counter()
        stream = recognizer.create_stream()
        stream.accept_waveform(sr, audio_f32)
        recognizer.decode_stream(stream)
        text = stream.result.text
        lat_ms = (time.perf_counter() - t0) * 1000
        latencies.append(lat_ms)
        
    cold = latencies[0]
    warm = latencies[1:]
    return {
        "model": os.path.basename(model_path),
        "size_mb": os.path.getsize(model_path) / (1024*1024),
        "init_ms": init_time_ms,
        "cold_ms": cold,
        "warm_mean_ms": np.mean(warm),
        "warm_p50_ms": np.median(warm),
        "warm_p95_ms": np.percentile(warm, 95),
        "transcription": text
    }

if __name__ == "__main__":
    audio = "model_optimization/validation/baseline_sample_1.wav" # "भारत एक महान देश है" (2.2s)
    tokens = "model_optimization/baseline/indic-tokens.txt"
    
    base_m = "model_optimization/baseline/indic-hi.int8.onnx"
    cand_m = "model_optimization/candidates/indic-hi-quant-conv.onnx"
    
    print("Benchmarking Baseline STT...")
    base_res = benchmark_stt(base_m, tokens, audio, runs=10)
    print("Baseline STT Results:", json.dumps(base_res, indent=2))
    
    print("\nBenchmarking Optimized STT...")
    cand_res = benchmark_stt(cand_m, tokens, audio, runs=10)
    print("Optimized STT Results:", json.dumps(cand_res, indent=2))
    
    with open("model_optimization/benchmarks/stt_latency_comparison.json", "w") as f:
        json.dump({"baseline": base_res, "candidate": cand_res}, f, indent=2)
