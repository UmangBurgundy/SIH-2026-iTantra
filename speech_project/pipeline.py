import os
import sys
import time
import numpy as np
import soundfile as sf
import torch
import torchaudio
import transformers.modeling_utils as mu
from transformers import AutoModel

# Ensure UTF-8 console output on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

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

STT_MODEL = "ai4bharat/indic-conformer-600m-multilingual"
TTS_MODEL = "ai4bharat/IndicF5"

# Look for audio file in current dir or script dir
AUDIO_FILE = sys.argv[1] if len(sys.argv) > 1 else "audio.wav"
if not os.path.exists(AUDIO_FILE):
    alt_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), AUDIO_FILE)
    if os.path.exists(alt_path):
        AUDIO_FILE = alt_path

OUTPUT_FILE = "speech_output.wav"

LANGUAGE = "hi"

# IMPORTANT:
# This must exactly describe what is spoken in AUDIO_FILE.
REFERENCE_TEXT = "हेलो माई नेम इज़ उमंग जैन"

TARGET_TEXT_FALLBACK = "नमस्ते! यह मेरा पहला टेक्स्ट टू स्पीच परीक्षण है।"


# --------------------------------------------------
# 1. Load models
# --------------------------------------------------

print("Loading STT model...")
start = time.perf_counter()

stt_model = AutoModel.from_pretrained(
    STT_MODEL,
    trust_remote_code=True
)
stt_model.eval()

stt_load_time = time.perf_counter() - start

print(f"STT loaded in {stt_load_time:.2f}s")


print("\nLoading TTS model...")
start = time.perf_counter()

tts_model = AutoModel.from_pretrained(
    TTS_MODEL,
    trust_remote_code=True
)

tts_model.eval()

tts_load_time = time.perf_counter() - start

print(f"TTS loaded in {tts_load_time:.2f}s")


# --------------------------------------------------
# 2. Load input audio
# --------------------------------------------------

print(f"\nLoading {AUDIO_FILE}...")

audio, sample_rate = sf.read(AUDIO_FILE)

if audio.ndim > 1:
    audio = audio.mean(axis=1)

if sample_rate != 16000:
    raise ValueError(
        f"Expected 16 kHz audio, got {sample_rate} Hz"
    )

wav = torch.tensor(
    audio,
    dtype=torch.float32
).unsqueeze(0)

audio_duration = len(audio) / sample_rate

print(f"Input duration: {audio_duration:.2f}s")


# --------------------------------------------------
# 3. STT
# --------------------------------------------------

print("\nRunning STT...")

start = time.perf_counter()

with torch.inference_mode():
    text = stt_model(
        wav,
        LANGUAGE,
        "ctc"
    )

stt_time = time.perf_counter() - start
stt_rtf = stt_time / audio_duration

print("\nSTT RESULT")
print("--------------------------------")
print(text)
print("--------------------------------")
print(f"STT time: {stt_time:.2f}s")
print(f"STT RTF : {stt_rtf:.2f}")


# --------------------------------------------------
# 4. Choose text for TTS
# --------------------------------------------------

# For this first pipeline test, use the STT output.
target_text = text

print("\nText passed to TTS:")
print(target_text)


# --------------------------------------------------
# 5. TTS
# --------------------------------------------------

print("\nRunning TTS...")

start = time.perf_counter()

tts_audio = tts_model(
    target_text,
    ref_audio_path=AUDIO_FILE,
    ref_text=REFERENCE_TEXT
)

tts_time = time.perf_counter() - start


# --------------------------------------------------
# 6. Save generated audio
# --------------------------------------------------

if tts_audio.dtype == np.int16:
    tts_audio = tts_audio.astype(np.float32) / 32768.0

tts_sample_rate = 24000

sf.write(
    OUTPUT_FILE,
    tts_audio,
    tts_sample_rate
)

tts_duration = len(tts_audio) / tts_sample_rate
tts_rtf = tts_time / tts_duration


# --------------------------------------------------
# 7. Final results
# --------------------------------------------------

total_time = stt_time + tts_time

print("\n==========================================")
print("       OFFLINE SPEECH → SPEECH")
print("==========================================")

print(f"Input audio       : {audio_duration:.2f}s")

print(f"\nSTT text           : {text}")
print(f"STT processing     : {stt_time:.2f}s")
print(f"STT RTF            : {stt_rtf:.2f}")

print(f"\nTTS audio duration : {tts_duration:.2f}s")
print(f"TTS processing     : {tts_time:.2f}s")
print(f"TTS RTF            : {tts_rtf:.2f}")

print(f"\nTotal processing   : {total_time:.2f}s")

print(f"\nOutput             : {OUTPUT_FILE}")

print("==========================================")