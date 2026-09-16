import os
import sys
import time
import numpy as np
import soundfile as sf
import torch
import transformers.modeling_utils as mu
from transformers import AutoModel
import torchaudio

# Ensure UTF-8 console output on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# Patch transformers meta initialization to prevent torchaudio meta tensor conflict on Windows
mu.PreTrainedModel.get_init_context = classmethod(lambda cls, *a, **k: [])

# Patch torchaudio.load with soundfile to bypass missing torchcodec on Windows
def _sf_load(filepath, *args, **kwargs):
    data, sr = sf.read(filepath)
    if data.ndim == 1:
        data = data[np.newaxis, :]
    else:
        data = data.T
    return torch.tensor(data, dtype=torch.float32), sr

torchaudio.load = _sf_load

MODEL_ID = "ai4bharat/IndicF5"

# Look for audio file in current dir or script dir
REFERENCE_AUDIO = sys.argv[1] if len(sys.argv) > 1 else "audio.wav"
if not os.path.exists(REFERENCE_AUDIO):
    alt_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), REFERENCE_AUDIO)
    if os.path.exists(alt_path):
        REFERENCE_AUDIO = alt_path
    elif os.path.exists("speech_project/sample_test.wav"):
        REFERENCE_AUDIO = "speech_project/sample_test.wav"
    elif os.path.exists("sample_test.wav"):
        REFERENCE_AUDIO = "sample_test.wav"

REFERENCE_TEXT = "हेलो माई नेम इज़ उमंग जैन"
TARGET_TEXT = "नमस्ते! यह मेरा पहला टेक्स्ट टू स्पीच परीक्षण है।"
OUTPUT_AUDIO = "output.wav"

print(f"Using reference audio: {REFERENCE_AUDIO}")
print("Loading IndicF5...")

load_start = time.perf_counter()

model = AutoModel.from_pretrained(
    MODEL_ID,
    trust_remote_code=True
)

device = "cuda" if torch.cuda.is_available() else "cpu"
model = model.to(device)

load_time = time.perf_counter() - load_start

print(f"Model loaded in {load_time:.2f} seconds")
print(f"Device: {device}")

print("\nGenerating speech...")
print(f"Text: {TARGET_TEXT}")

tts_start = time.perf_counter()

audio = model(
    TARGET_TEXT,
    ref_audio_path=REFERENCE_AUDIO,
    ref_text=REFERENCE_TEXT
)

tts_time = time.perf_counter() - tts_start

# IndicF5 normally outputs 24 kHz audio
sample_rate = 24000

if audio.dtype == np.int16:
    audio = audio.astype(np.float32) / 32768.0

sf.write(
    OUTPUT_AUDIO,
    audio,
    sample_rate
)

audio_duration = len(audio) / sample_rate
rtf = tts_time / audio_duration

print("\n================================")
print("          TTS RESULT")
print("================================")

print(f"Output file   : {OUTPUT_AUDIO}")
print(f"Audio duration: {audio_duration:.2f} sec")
print(f"TTS time      : {tts_time:.2f} sec")
print(f"RTF           : {rtf:.2f}")

print("================================")