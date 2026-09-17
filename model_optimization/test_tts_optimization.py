import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
sys.path.append(".")
import numpy as np
import soundfile as sf
import onnxruntime as ort
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
import model_optimization.evaluate_pipeline as ep

def benchmark_tts(model_path, runs=10):
    vocab = ep.load_vocab("model_optimization/baseline/mms-hin-vocab.json")
    text = "नमस्ते भारत एक महान देश है"
    tokens = ep.tokenize(text, vocab)
    
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(model_path, so, providers=["CPUExecutionProvider"])
    
    # Warmup
    _ = ep.synthesize_audio(session, tokens)
    
    import time
    latencies = []
    for _ in range(runs):
        t0 = time.perf_counter()
        audio = ep.synthesize_audio(session, tokens)
        latencies.append((time.perf_counter() - t0) * 1000)
        
    return {
        "model": os.path.basename(model_path),
        "size_mb": os.path.getsize(model_path) / (1024*1024),
        "mean_lat_ms": np.mean(latencies),
        "p50_lat_ms": np.median(latencies),
        "audio_len_s": len(audio) / 16000.0,
        "rms": float(np.sqrt(np.mean(audio**2))),
        "peak": float(np.abs(audio).max())
    }

if __name__ == "__main__":
    base_tts = "model_optimization/baseline/mms-hin.int8.onnx"
    graphopt_tts = "model_optimization/candidates/mms-hin-graphopt.onnx"
    
    print("Benchmarking Baseline TTS...")
    res_base = benchmark_tts(base_tts)
    print("Baseline:", res_base)
    
    print("\nBenchmarking Graph-Optimized TTS...")
    res_graphopt = benchmark_tts(graphopt_tts)
    print("GraphOpt:", res_graphopt)
