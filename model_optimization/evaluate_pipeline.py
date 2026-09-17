import os
import sys
import json
import unicodedata
import numpy as np
import soundfile as sf
import onnxruntime as ort
import sherpa_onnx

sys.stdout.reconfigure(encoding='utf-8')

def load_vocab(vocab_path):
    with open(vocab_path, "r", encoding="utf-8") as f:
        return json.load(f)

def tokenize(text, vocab):
    normalized = unicodedata.normalize("NFC", text).strip()
    token_ids = []
    for ch in normalized:
        if ch in vocab:
            token_ids.append(vocab[ch])
    
    # Interleave zeros (VITS blank token = 0)
    interleaved = [0]
    for tid in token_ids:
        interleaved.append(tid)
        interleaved.append(0)
    return interleaved

def synthesize_audio(session, token_ids):
    input_ids = np.array([token_ids], dtype=np.int64)
    attention_mask = np.ones_like(input_ids, dtype=np.int64)
    
    inputs = {
        "input_ids": input_ids,
        "attention_mask": attention_mask
    }
    outputs = session.run(None, inputs)
    audio = outputs[0]
    if audio.ndim == 3:
        audio = audio[0, 0]
    elif audio.ndim == 2:
        audio = audio[0]
    return audio

def run_evaluation(tts_model_path, stt_model_path, output_prefix="baseline"):
    vocab_path = "model_optimization/baseline/mms-hin-vocab.json"
    tokens_path = "model_optimization/baseline/indic-tokens.txt"
    vocab = load_vocab(vocab_path)
    
    print(f"\n=======================================================")
    print(f"EVALUATION: {output_prefix}")
    print(f"TTS Model: {tts_model_path} ({os.path.getsize(tts_model_path)/(1024*1024):.2f} MB)")
    print(f"STT Model: {stt_model_path} ({os.path.getsize(stt_model_path)/(1024*1024):.2f} MB)")
    print(f"=======================================================")
    
    # 1. Initialize TTS
    tts_session = ort.InferenceSession(tts_model_path, providers=["CPUExecutionProvider"])
    
    # 2. Initialize STT
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=stt_model_path,
        tokens=tokens_path,
        num_threads=2
    )
    
    test_phrases = [
        "नमस्ते",
        "भारत एक महान देश है",
        "हम सब भारतीय हैं",
        "आप कैसे हैं"
    ]
    
    eval_results = []
    
    for idx, phrase in enumerate(test_phrases):
        tokens = tokenize(phrase, vocab)
        audio = synthesize_audio(tts_session, tokens)
        
        # Audio stats
        peak = float(np.abs(audio).max())
        rms = float(np.sqrt(np.mean(audio**2)))
        duration = len(audio) / 16000.0
        
        # Save audio file
        wav_path = f"model_optimization/validation/{output_prefix}_sample_{idx}.wav"
        sf.write(wav_path, audio, 16000)
        
        # Transcribe
        stream = recognizer.create_stream()
        stream.accept_waveform(16000, audio.astype(np.float32))
        recognizer.decode_stream(stream)
        transcription = stream.result.text.strip()
        
        print(f"[{idx+1}] Text: '{phrase}'")
        print(f"    Audio: {duration:.2f}s | Peak: {peak:.3f} | RMS: {rms:.3f} -> Saved: {wav_path}")
        print(f"    STT : '{transcription}'")
        
        eval_results.append({
            "target": phrase,
            "transcription": transcription,
            "duration_s": duration,
            "peak": peak,
            "rms": rms,
            "audio_path": wav_path
        })
        
    return eval_results

if __name__ == "__main__":
    tts_baseline = "model_optimization/baseline/mms-hin.int8.onnx"
    stt_baseline = "model_optimization/baseline/indic-hi.int8.onnx"
    results = run_evaluation(tts_baseline, stt_baseline, output_prefix="baseline")
    with open("model_optimization/benchmarks/baseline_eval.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("\nSaved baseline evaluation to model_optimization/benchmarks/baseline_eval.json")
