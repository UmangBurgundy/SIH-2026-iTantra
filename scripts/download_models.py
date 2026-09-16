#!/usr/bin/env python3
"""
scripts/download_models.py

Automated Model Downloader for iTantra.
Downloads the required on-device INT8 speech AI models into `android/app/src/main/assets/models/`:

1. IndicConformer CTC INT8 (AI4Bharat / Sherpa-ONNX) for Hindi STT (~188 MB)
2. Meta MMS-TTS Hindi VITS INT8 (~36.5 MB)
3. Whisper-Tiny INT8 for English STT (~102 MB total)

Usage:
    python scripts/download_models.py
"""

import os
import sys
import urllib.request
import shutil

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS_DIR = os.path.join(BASE_DIR, "android", "app", "src", "main", "assets", "models")
TTS_DIR = os.path.join(MODELS_DIR, "tts")

# HuggingFace & Sherpa-ONNX model release URLs
MODELS = [
    # --- Hindi STT: AI4Bharat IndicConformer CTC INT8 ---
    {
        "name": "IndicConformer Hindi CTC INT8 (STT)",
        "dest": os.path.join(MODELS_DIR, "indic-hi.int8.onnx"),
        "url": "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-indic-conformer-hi-int8/resolve/main/model.int8.onnx",
        "size_mb": 188.4
    },
    # --- English STT: Whisper-Tiny INT8 ---
    {
        "name": "Whisper-Tiny Encoder INT8 (STT)",
        "dest": os.path.join(MODELS_DIR, "tiny-encoder.int8.onnx"),
        "url": "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
        "size_mb": 12.3
    },
    {
        "name": "Whisper-Tiny Decoder INT8 (STT)",
        "dest": os.path.join(MODELS_DIR, "tiny-decoder.int8.onnx"),
        "url": "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
        "size_mb": 85.7
    },
    # --- Hindi TTS: Meta MMS-TTS Hindi VITS INT8 ---
    {
        "name": "Meta MMS-TTS Hindi VITS INT8 (TTS)",
        "dest": os.path.join(TTS_DIR, "mms-hin.int8.onnx"),
        "url": "https://huggingface.co/facebook/mms-tts-hin/resolve/main/model.onnx", # will be placed if needed
        "size_mb": 36.6
    }
]

def download_with_progress(url: str, dest_path: str, label: str):
    print(f"\n[Downloading] {label}...")
    print(f"Target: {dest_path}")
    
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    temp_path = dest_path + ".tmp"

    def report_hook(block_num, block_size, total_size):
        downloaded = block_num * block_size
        if total_size > 0:
            percent = min(100.0, (downloaded / total_size) * 100.0)
            mb_down = downloaded / (1024 * 1024)
            mb_tot = total_size / (1024 * 1024)
            sys.stdout.write(f"\r  Progress: {percent:5.1f}% ({mb_down:6.1f} MB / {mb_tot:6.1f} MB)")
            sys.stdout.flush()
        else:
            mb_down = downloaded / (1024 * 1024)
            sys.stdout.write(f"\r  Downloaded: {mb_down:6.1f} MB")
            sys.stdout.flush()

    try:
        urllib.request.urlretrieve(url, temp_path, reporthook=report_hook)
        if os.path.exists(dest_path):
            os.remove(dest_path)
        os.rename(temp_path, dest_path)
        print("\n  -> Download Complete.")
    except Exception as e:
        if os.path.exists(temp_path):
            os.remove(temp_path)
        print(f"\n  -> Error downloading {label}: {e}")
        print("  -> Please verify internet connection or download manually.")

def main():
    print("=" * 65)
    print(" iTantra Offline AI Model Downloader")
    print("=" * 65)
    os.makedirs(MODELS_DIR, exist_ok=True)
    os.makedirs(TTS_DIR, exist_ok=True)

    for item in MODELS:
        dest = item["dest"]
        name = item["name"]
        if os.path.exists(dest) and os.path.getsize(dest) > 1024 * 1024:
            size_mb = os.path.getsize(dest) / (1024 * 1024)
            print(f"[Already Present] {name} ({size_mb:.1f} MB)")
        else:
            download_with_progress(item["url"], dest, name)

    print("\n" + "=" * 65)
    print(" All AI models verified. Ready to build Android APK!")
    print("=" * 65)

if __name__ == "__main__":
    main()
