import time
import torch
import soundfile as sf
from transformers import AutoModel

MODEL_ID = "ai4bharat/indic-conformer-600m-multilingual"
AUDIO_FILE = "audio.wav"
LANGUAGE = "hi"

# -----------------------------
# Load model
# -----------------------------
print("Loading model...")

model_start = time.perf_counter()

model = AutoModel.from_pretrained(
    MODEL_ID,
    trust_remote_code=True
)

model.eval()

model_load_time = time.perf_counter() - model_start

print(f"Model loaded in {model_load_time:.2f} seconds")


# -----------------------------
# Load audio
# -----------------------------
print(f"\nLoading {AUDIO_FILE}...")

audio, sr = sf.read(AUDIO_FILE)

print(f"Sample rate : {sr} Hz")

# Convert stereo -> mono
if len(audio.shape) > 1:
    audio = audio.mean(axis=1)

# Convert numpy -> torch
wav = torch.tensor(audio, dtype=torch.float32)

# Add channel dimension
wav = wav.unsqueeze(0)

audio_duration = wav.shape[1] / sr

print(f"Audio duration : {audio_duration:.2f} seconds")
print(f"Audio samples  : {wav.shape[1]}")


# -----------------------------
# Check sample rate
# -----------------------------
if sr != 16000:
    raise ValueError(
        f"Expected 16000 Hz audio, but got {sr} Hz. "
        "Convert the audio to 16 kHz first."
    )


# -----------------------------
# Run STT
# -----------------------------
print("\nRunning STT...")

stt_start = time.perf_counter()

with torch.inference_mode():
    text = model(wav, LANGUAGE, "ctc")

stt_time = time.perf_counter() - stt_start


# -----------------------------
# Calculate RTF
# -----------------------------
rtf = stt_time / audio_duration


# -----------------------------
# Results
# -----------------------------
print("\n================================")
print("          STT RESULT")
print("================================")

print(f"Text           : {text}")
print(f"Audio duration : {audio_duration:.2f} sec")
print(f"STT time       : {stt_time:.2f} sec")
print(f"RTF            : {rtf:.2f}")

print("================================")