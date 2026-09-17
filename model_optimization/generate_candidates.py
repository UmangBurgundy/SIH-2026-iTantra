import os
import sys
import copy
import onnx
from onnx import numpy_helper, helper
import numpy as np
import onnxruntime as ort
from onnx import numpy_helper

sys.stdout.reconfigure(encoding='utf-8')

def optimize_graph(input_path, output_path):
    print(f"\n[Graph Optimization] {os.path.basename(input_path)} -> {os.path.basename(output_path)}")
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.optimized_model_filepath = output_path
    # Creating the session applies and saves graph optimization
    try:
        session = ort.InferenceSession(input_path, so, providers=["CPUExecutionProvider"])
        size_in = os.path.getsize(input_path) / (1024*1024)
        size_out = os.path.getsize(output_path) / (1024*1024)
        print(f"  Success: {size_in:.2f} MB -> {size_out:.2f} MB (Delta: {size_out - size_in:.2f} MB)")
        return True
    except Exception as e:
        print(f"  Failed: {e}")
        return False

def generate_fp16_candidate(input_path, output_path):
    print(f"\n[FP16 Mixed Precision] Converting FP32 weights in {os.path.basename(input_path)} to FP16...")
    model = onnx.load(input_path, load_external_data=False)
    graph = model.graph
    
    converted_count = 0
    bytes_saved = 0
    
    new_inits = []
    for init in graph.initializer:
        if init.data_type == 1: # FLOAT (FP32)
            arr = numpy_helper.to_array(init)
            # Check if values are within float16 dynamic range
            max_val = np.abs(arr).max()
            if max_val < 65504: # within float16 range
                # We can store as float16 tensor, but in ONNX graph, if the node expects float,
                # we must check operator compatibility or cast.
                # Alternatively, let's check size reduction.
                pass
        new_inits.append(init)
    print(f"  Inspected initializers.")

def test_int4_quantization(input_path, output_path, block_size=128):
    print(f"\n[INT4 MatMulNBits] Attempting INT4 quantization for {os.path.basename(input_path)} (block_size={block_size})...")
    try:
        model = onnx.load(input_path, load_external_data=False)
        quant = matmul_4bits_quantizer.MatMul4BitsQuantizer(
            model=model,
            block_size=block_size,
            is_symmetric=True,
            accuracy_level=None
        )
        quant.process()
        onnx.save(quant.model.model, output_path)
        size_in = os.path.getsize(input_path) / (1024*1024)
        size_out = os.path.getsize(output_path) / (1024*1024)
        print(f"  Success: {size_in:.2f} MB -> {size_out:.2f} MB (Reduction: {(1 - size_out/size_in)*100:.1f}%)")
        return True
    except Exception as e:
        print(f"  INT4 generation error: {e}")
        return False

if __name__ == "__main__":
    stt_base = "model_optimization/baseline/indic-hi.int8.onnx"
    tts_base = "model_optimization/baseline/mms-hin.int8.onnx"
    whisper_enc = "model_optimization/baseline/tiny-encoder.int8.onnx"
    whisper_dec = "model_optimization/baseline/tiny-decoder.int8.onnx"
    
    # 1. Graph Optimization Candidates
    stt_graphopt = "model_optimization/candidates/indic-hi-graphopt.onnx"
    tts_graphopt = "model_optimization/candidates/mms-hin-graphopt.onnx"
    optimize_graph(stt_base, stt_graphopt)
    optimize_graph(tts_base, tts_graphopt)
    
    # 2. INT4 Candidates (tested separately in feasibility study)
    # stt_int4 = "model_optimization/candidates/indic-hi-int4-b128.onnx"
    # tts_int4 = "model_optimization/candidates/mms-hin-int4-b128.onnx"
    # test_int4_quantization(stt_base, stt_int4, block_size=128)
    # test_int4_quantization(tts_base, tts_int4, block_size=128)
    print("\nGraph optimization completed.")
