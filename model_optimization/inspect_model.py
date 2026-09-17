import os
import sys
import json
import onnx
from onnx import numpy_helper
from collections import Counter

TENSOR_TYPE_MAP = {
    0: "UNDEFINED",
    1: "FLOAT",
    2: "UINT8",
    3: "INT8",
    4: "UINT16",
    5: "INT16",
    6: "INT32",
    7: "INT64",
    8: "STRING",
    9: "BOOL",
    10: "FLOAT16",
    11: "DOUBLE",
    12: "UINT32",
    13: "UINT64",
    14: "COMPLEX64",
    15: "COMPLEX128",
    16: "BFLOAT16"
}

def analyze_model(model_path):
    print(f"\n=======================================================")
    print(f"ANALYZING: {os.path.basename(model_path)}")
    print(f"File Size: {os.path.getsize(model_path):,} bytes ({os.path.getsize(model_path)/(1024*1024):.2f} MB)")
    print(f"=======================================================")

    model = onnx.load(model_path, load_external_data=False)
    graph = model.graph

    print(f"IR Version: {model.ir_version}")
    print(f"Producer: {model.producer_name} {model.producer_version}")
    opsets = {entry.domain if entry.domain else "ai.onnx": entry.version for entry in model.opset_import}
    print(f"Opset Imports: {opsets}")

    # Inputs and Outputs
    print("\n--- Inputs ---")
    for inp in graph.input:
        shape = [str(d.dim_value) if d.dim_value > 0 else (d.dim_param if d.dim_param else "?") for d in inp.type.tensor_type.shape.dim]
        elem_type = TENSOR_TYPE_MAP.get(inp.type.tensor_type.elem_type, str(inp.type.tensor_type.elem_type))
        print(f"  {inp.name}: {elem_type} shape=[{', '.join(shape)}]")

    print("\n--- Outputs ---")
    for out in graph.output:
        shape = [str(d.dim_value) if d.dim_value > 0 else (d.dim_param if d.dim_param else "?") for d in out.type.tensor_type.shape.dim]
        elem_type = TENSOR_TYPE_MAP.get(out.type.tensor_type.elem_type, str(out.type.tensor_type.elem_type))
        print(f"  {out.name}: {elem_type} shape=[{', '.join(shape)}]")

    # Node operators count
    op_counts = Counter(node.op_type for node in graph.node)
    print(f"\n--- Operator Distribution (Total Nodes: {len(graph.node)}) ---")
    for op, count in op_counts.most_common(20):
        print(f"  {op:25s}: {count:5d} nodes")

    # Initializers analysis
    type_bytes = Counter()
    type_params = Counter()
    tensor_sizes = []

    for init in graph.initializer:
        dtype_str = TENSOR_TYPE_MAP.get(init.data_type, f"TYPE_{init.data_type}")
        
        # Calculate size in bytes
        num_elements = 1
        for d in init.dims:
            num_elements *= d
        
        if init.raw_data:
            size_bytes = len(init.raw_data)
        else:
            # fallback based on type
            bytes_per_elem = 4 if init.data_type in (1, 6) else (1 if init.data_type in (2, 3) else 8)
            size_bytes = num_elements * bytes_per_elem
        
        type_bytes[dtype_str] += size_bytes
        type_params[dtype_str] += num_elements
        tensor_sizes.append((init.name, dtype_str, list(init.dims), num_elements, size_bytes))

    total_init_bytes = sum(type_bytes.values())
    total_params = sum(type_params.values())

    print(f"\n--- Initializer Weight Distribution (Total Parameters: {total_params:,}) ---")
    print(f"Total Initializer Storage: {total_init_bytes:,} bytes ({total_init_bytes/(1024*1024):.2f} MB)")
    for dtype, b in type_bytes.most_common():
        pct = (b / total_init_bytes) * 100 if total_init_bytes > 0 else 0
        p_count = type_params[dtype]
        print(f"  {dtype:15s}: {b:12,} bytes ({b/(1024*1024):6.2f} MB) [{pct:5.1f}%] - {p_count:,} params")

    # Top 10 largest tensors
    tensor_sizes.sort(key=lambda x: x[4], reverse=True)
    print(f"\n--- Top 10 Largest Weight Tensors ---")
    for name, dtype, dims, count, b in tensor_sizes[:10]:
        print(f"  {name:45s} | {dtype:8s} | dims={str(dims):20s} | {count:10,} params | {b/(1024*1024):6.2f} MB")

    return {
        "file": os.path.basename(model_path),
        "file_size": os.path.getsize(model_path),
        "total_nodes": len(graph.node),
        "op_counts": dict(op_counts),
        "total_params": total_params,
        "type_bytes": dict(type_bytes),
        "type_params": dict(type_params)
    }

if __name__ == "__main__":
    models = [
        "model_optimization/baseline/indic-hi.int8.onnx",
        "model_optimization/baseline/mms-hin.int8.onnx",
        "model_optimization/baseline/tiny-encoder.int8.onnx",
        "model_optimization/baseline/tiny-decoder.int8.onnx"
    ]
    results = {}
    for m in models:
        if os.path.exists(m):
            results[os.path.basename(m)] = analyze_model(m)
        else:
            print(f"File not found: {m}")
    
    with open("model_optimization/benchmarks/graph_analysis.json", "w") as f:
        json.dump(results, f, indent=2)
    print("\nSaved analysis to model_optimization/benchmarks/graph_analysis.json")
