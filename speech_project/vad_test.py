import time
import numpy as np
import sounddevice as sd
import soundfile as sf

SAMPLE_RATE = 16000
CHANNELS = 1

# 30 ms audio chunks
CHUNK_DURATION = 0.03
CHUNK_SIZE = int(SAMPLE_RATE * CHUNK_DURATION)

# Silence required to end speech
SILENCE_DURATION = 0.7

# Initial threshold. We will tune this later.
ENERGY_THRESHOLD = 0.015


print("======================================")
print("       SIMPLE MICROPHONE VAD")
print("======================================")
print("Waiting for speech...")
print("Speak normally, then stop talking.")
print()


audio_chunks = []
speech_started = False
last_speech_time = None
recording_finished = False


def audio_callback(indata, frames, time_info, status):
    global speech_started
    global last_speech_time
    global recording_finished

    if status:
        print("Audio status:", status)

    audio = indata[:, 0].copy()

    # Calculate RMS energy
    energy = np.sqrt(np.mean(audio ** 2))

    current_time = time.perf_counter()

    # Speech detected
    if energy > ENERGY_THRESHOLD:

        if not speech_started:
            speech_started = True
            print("🎙️ Speech detected!")

        last_speech_time = current_time
        audio_chunks.append(audio)

    # Silence after speech
    elif speech_started:

        audio_chunks.append(audio)

        silence_time = current_time - last_speech_time

        if silence_time >= SILENCE_DURATION:
            recording_finished = True


# Start microphone
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


# --------------------------------------
# Save recording
# --------------------------------------

if not audio_chunks:

    print("No speech detected.")
    exit()


audio = np.concatenate(audio_chunks)

duration = len(audio) / SAMPLE_RATE


print()
print("======================================")
print("          VAD RESULT")
print("======================================")
print(f"Speech duration : {duration:.2f} seconds")
print(f"Samples         : {len(audio)}")
print(f"Sample rate     : {SAMPLE_RATE} Hz")


if duration > 0.3:

    output_file = "mic_input.wav"

    sf.write(
        output_file,
        audio,
        SAMPLE_RATE
    )

    print(f"Saved           : {output_file}")

else:

    print("Recording too short. Ignoring it.")

print("======================================")