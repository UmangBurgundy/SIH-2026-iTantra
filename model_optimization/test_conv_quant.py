import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
import onnx
import onnxruntime.quantization as q
import sherpa_onnx
import numpy as np

def test_conv_quant():
    input_model = "model_optimization/baseline/indic-hi.int8.onnx"
    output_model = "model_optimization/candidates/indic-hi-quant-conv.onnx"
    
    print("Testing dynamic quantization of remaining Conv and Gather nodes...")
    try:
        model_proto = onnx.load(input_model, load_external_data=False)
        q.quantize_dynamic(
            model_input=model_proto,
            model_output=output_model,
            op_types_to_quantize=['Conv'],
            weight_type=q.QuantType.QUInt8,
            per_channel=False
        )
        size_in = os.path.getsize(input_model) / (1024*1024)
        size_out = os.path.getsize(output_model) / (1024*1024)
        print(f"Quantized size: {size_in:.2f} MB -> {size_out:.2f} MB (Saved: {size_in - size_out:.2f} MB)")
        
        print("\nTesting in sherpa-onnx...")
        tokens_path = "model_optimization/baseline/indic-tokens.txt"
        recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=output_model,
            tokens=tokens_path,
            num_threads=2
        )
        print("Sherpa-onnx initialized successfully with Conv-quantized model!")
        
        # Test with baseline audio
        import soundfile as sf
        audio, sr = sf.read("model_optimization/validation/baseline_sample_0.wav")
        stream = recognizer.create_stream()
        stream.accept_waveform(sr, audio.astype(np.float32))
        recognizer.decode_stream(stream)
        text = stream.result.text
        print(f"Transcription of 'नमस्ते' audio: '{text}'")
        return True
    except Exception as e:
        print(f"Error: {e}")
        return False

if __name__ == "__main__":
    test_conv_quant()
