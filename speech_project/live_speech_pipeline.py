import os
import sys
import time
from collections import deque

import numpy as np
import sounddevice as sd
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

# --------------------------------------------------
# Configuration
# --------------------------------------------------
# Windows compatibility patch for IndicF5/Vocos
mu.PreTrainedModel.get_init_context = classmethod(
    lambda cls, *a, **k: []
)

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

LANGUAGE = "hi"

SAMPLE_RATE = 16000
CHANNELS = 1

CHUNK_DURATION = 0.03
CHUNK_SIZE = int(SAMPLE_RATE * CHUNK_DURATION)

ENERGY_THRESHOLD = 0.015

SILENCE_DURATION = 0.7

PRE_ROLL_DURATION = 0.20
PRE_ROLL_CHUNKS = int(PRE_ROLL_DURATION / CHUNK_DURATION)

INPUT_FILE = "mic_input.wav"
OUTPUT_FILE = "speech_output.wav"

# This must exactly describe your reference audio.
REFERENCE_TEXT = "हेलो माई नेम इज़ उमंग जैन"


# --------------------------------------------------
# Load models
# --------------------------------------------------

print("======================================")
print("     OFFLINE LIVE SPEECH PIPELINE")
print("======================================")

print("\nLoading STT model...")
start = time.perf_counter()

stt_model = AutoModel.from_pretrained(
    STT_MODEL,
    trust_remote_code=True
)
stt_model.eval()

print(f"STT loaded in {time.perf_counter() - start:.2f}s")


print("\nLoading TTS model...")
start = time.perf_counter()

tts_model = AutoModel.from_pretrained(
    TTS_MODEL,
    trust_remote_code=True
)
tts_model.eval()

print(f"TTS loaded in {time.perf_counter() - start:.2f}s")


# --------------------------------------------------
# VAD state
# --------------------------------------------------

audio_chunks = []

pre_roll = deque(maxlen=PRE_ROLL_CHUNKS)

speech_started = False
last_speech_time = None
recording_finished = False


# --------------------------------------------------
# Microphone callback
# --------------------------------------------------

def audio_callback(indata, frames, time_info, status):
    global speech_started
    global last_speech_time
    global recording_finished

    if status:
        print("Audio status:", status)

    audio = indata[:, 0].copy()

    energy = np.sqrt(np.mean(audio ** 2))

    current_time = time.perf_counter()

    # ----------------------------------------------
    # Speech detected
    # ----------------------------------------------

    if energy > ENERGY_THRESHOLD:

        if not speech_started:

            speech_started = True

            print("\n🎙️ Speech detected!")

            # Add audio immediately before speech detection
            audio_chunks.extend(list(pre_roll))

            pre_roll.clear()

        audio_chunks.append(audio)

        last_speech_time = current_time

    # ----------------------------------------------
    # Silence
    # ----------------------------------------------

    elif speech_started:

        audio_chunks.append(audio)

        silence_time = current_time - last_speech_time

        if silence_time >= SILENCE_DURATION:
            recording_finished = True

    else:

        # Still waiting for speech
        pre_roll.append(audio)


# --------------------------------------------------
# Record from microphone
# --------------------------------------------------

print("\nWaiting for speech...")
print("Speak normally, then stop talking.")

stream = sd.InputStream(
    samplerate=SAMPLE_RATE,
    channels=CHANNELS,
    dtype="float32",
    blocksize=CHUNK_SIZE,
    callback=audio_callback
)

stream.start()

try:

    while not recording_finished:
        time.sleep(0.05)

finally:

    stream.stop()
    stream.close()


# --------------------------------------------------
# Save microphone recording
# --------------------------------------------------

if not audio_chunks:

    print("No speech detected.")
    raise SystemExit


audio = np.concatenate(audio_chunks)

audio_duration = len(audio) / SAMPLE_RATE

if audio_duration < 0.4:
    print(f"\n⚠️ Recording too short ({audio_duration:.2f}s). Ignoring noise burst.")
    sys.exit(0)

sf.write(
    INPUT_FILE,
    audio,
    SAMPLE_RATE
)

print("\n======================================")
print("          VAD COMPLETE")
print("======================================")
print(f"Input duration : {audio_duration:.2f}s")
print(f"Saved          : {INPUT_FILE}")
print("======================================")


# --------------------------------------------------
# STT
# --------------------------------------------------

print("\nRunning STT...")

start = time.perf_counter()

wav = torch.tensor(
    audio,
    dtype=torch.float32
).unsqueeze(0)

with torch.inference_mode():

    text = stt_model(
        wav,
        LANGUAGE,
        "ctc"
    )

stt_time = time.perf_counter() - start

stt_rtf = stt_time / audio_duration

print("\nSTT RESULT")
print("--------------------------------------")
print(text if text.strip() else "[NO SPEECH DETECTED]")
print("--------------------------------------")
print(f"STT time : {stt_time:.2f}s")
print(f"STT RTF  : {stt_rtf:.2f}")

if not text or not text.strip():
    print("\n⚠️ No words were recognized in the audio.")
    print("Please speak clearly or increase your microphone volume.")
    sys.exit(0)


# --------------------------------------------------
# TTS
# --------------------------------------------------

print("\nRunning TTS...")

start = time.perf_counter()

# Use audio.wav as reference voice if present, otherwise fall back to microphone input
ref_audio = "audio.wav" if os.path.exists("audio.wav") else INPUT_FILE
ref_text = REFERENCE_TEXT if ref_audio == "audio.wav" else text

print(f"Using reference audio: {ref_audio}")
tts_audio = tts_model(
    text,
    ref_audio_path=ref_audio,
    ref_text=ref_text
)

tts_time = time.perf_counter() - start


# --------------------------------------------------
# Save TTS output
# --------------------------------------------------

if tts_audio.dtype == np.int16:

    tts_audio = (
        tts_audio.astype(np.float32) / 32768.0
    )

TTS_SAMPLE_RATE = 24000

sf.write(
    OUTPUT_FILE,
    tts_audio,
    TTS_SAMPLE_RATE
)

tts_duration = len(tts_audio) / TTS_SAMPLE_RATE

tts_rtf = tts_time / tts_duration

print("\n======================================")
print("       SPEECH → SPEECH RESULT")
print("======================================")

print(f"Input duration : {audio_duration:.2f}s")

print(f"\nRecognized text:")
print(text)

print(f"\nSTT time       : {stt_time:.2f}s")
print(f"STT RTF        : {stt_rtf:.2f}")

print(f"\nTTS duration   : {tts_duration:.2f}s")
print(f"TTS time       : {tts_time:.2f}s")
print(f"TTS RTF        : {tts_rtf:.2f}")

print(f"\nTotal time     : {stt_time + tts_time:.2f}s")

print(f"\nOutput         : {OUTPUT_FILE}")

print("======================================")