import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
import onnx
import onnxruntime as ort
import sherpa_onnx
import numpy as np

def test_baseline_sherpa():
    print("Testing baseline indic-hi with sherpa_onnx...")
    model_path = "model_optimization/baseline/indic-hi.int8.onnx"
    tokens_path = "model_optimization/baseline/indic-tokens.txt"
    
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=model_path,
        tokens=tokens_path,
        num_threads=2
    )
    print("Baseline Sherpa recognizer created successfully!")
    
    # Create 3 seconds of dummy audio (16kHz mono silence/sine)
    sample_rate = 16000
    samples = np.zeros(sample_rate * 2, dtype=np.float32)
    stream = recognizer.create_stream()
    stream.accept_waveform(sample_rate, samples)
    recognizer.decode_stream(stream)
    result = stream.result.text
    print(f"Inference test run result: '{result}' (expected empty for silence)")
    return True

def test_baseline_mms_tts():
    print("\nTesting baseline mms-hin with onnxruntime...")
    model_path = "model_optimization/baseline/mms-hin.int8.onnx"
    session = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    print("Baseline MMS-TTS session created successfully!")
    
    # Check input names
    inputs = {inp.name: inp.shape for inp in session.get_inputs()}
    print(f"Inputs: {inputs}")
    
    # Prepare input matching MmsTTSBackend
    input_ids = np.array([[0, 10, 0, 20, 0]], dtype=np.int64)
    attention_mask = np.ones((1, 5), dtype=np.int64)
    scales = np.array([0.667, 1.0, 0.8], dtype=np.float32)
    
    ort_inputs = {
        "input_ids": input_ids,
        "attention_mask": attention_mask
    }
    outputs = session.run(None, ort_inputs)
    print(f"TTS output audio shape: {outputs[0].shape}, min: {outputs[0].min():.4f}, max: {outputs[0].max():.4f}")
    return True

if __name__ == "__main__":
    test_baseline_sherpa()
    test_baseline_mms_tts()
