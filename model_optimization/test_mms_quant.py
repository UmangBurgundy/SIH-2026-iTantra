import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
import onnx
import onnxruntime.quantization as q
import numpy as np
import onnxruntime as ort

def test_mms_quant():
    input_model = "model_optimization/baseline/mms-hin.int8.onnx"
    output_model = "model_optimization/candidates/mms-hin-quant-more.onnx"
    
    print("Testing dynamic quantization of mms-hin for remaining ops...")
    try:
        model_proto = onnx.load(input_model, load_external_data=False)
        q.quantize_dynamic(
            model_input=model_proto,
            model_output=output_model,
            op_types_to_quantize=['Conv', 'ConvTranspose'],
            weight_type=q.QuantType.QUInt8,
            per_channel=False
        )
        size_in = os.path.getsize(input_model) / (1024*1024)
        size_out = os.path.getsize(output_model) / (1024*1024)
        print(f"Quantized size: {size_in:.2f} MB -> {size_out:.2f} MB (Saved: {size_in - size_out:.2f} MB)")
        
        # Test inference
        session = ort.InferenceSession(output_model, providers=["CPUExecutionProvider"])
        print("MMS session created successfully!")
        
        input_ids = np.array([[0, 10, 0, 20, 0]], dtype=np.int64)
        attention_mask = np.ones((1, 5), dtype=np.int64)
        outputs = session.run(None, {"input_ids": input_ids, "attention_mask": attention_mask})
        print(f"Synthesized audio shape: {outputs[0].shape}")
        return True
    except Exception as e:
        print(f"MMS quantization error: {e}")
        return False

if __name__ == "__main__":
    test_mms_quant()
