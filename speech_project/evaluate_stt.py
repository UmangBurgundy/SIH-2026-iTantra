import os
import sys
import time
import csv
import re

import numpy as np
import soundfile as sf
import torch
from transformers import AutoModel

# Ensure UTF-8 console output on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


# ============================================================
# CONFIG
# ============================================================

STT_MODEL = "ai4bharat/indic-conformer-600m-multilingual"

TEST_TSV = "test.tsv"

# dataset/clips is one level above speech_project
CLIPS_DIR = os.path.join("..", "dataset", "clips")

LANGUAGE = "hi"

# Start small. Increase after this works.
MAX_SAMPLES = None


# ============================================================
# TEXT NORMALIZATION
# ============================================================

def normalize_text(text):
    """
    Basic normalization for WER calculation.

    We remove punctuation and normalize whitespace,
    but preserve Hindi characters.
    """

    text = str(text).strip().lower()

    # Remove punctuation while keeping Unicode letters/numbers.
    text = re.sub(r"[^\w\s\u0900-\u097F]", " ", text)

    # Normalize whitespace
    text = re.sub(r"\s+", " ", text)

    return text.strip()


# ============================================================
# WER
# ============================================================

def word_error_rate(reference, hypothesis):
    """
    Calculate Word Error Rate using Levenshtein distance.
    """

    ref = normalize_text(reference).split()
    hyp = normalize_text(hypothesis).split()

    n = len(ref)
    m = len(hyp)

    if n == 0:
        return 0.0 if m == 0 else 1.0

    dp = [[0] * (m + 1) for _ in range(n + 1)]

    for i in range(n + 1):
        dp[i][0] = i

    for j in range(m + 1):
        dp[0][j] = j

    for i in range(1, n + 1):
        for j in range(1, m + 1):

            if ref[i - 1] == hyp[j - 1]:
                cost = 0
            else:
                cost = 1

            dp[i][j] = min(
                dp[i - 1][j] + 1,       # deletion
                dp[i][j - 1] + 1,       # insertion
                dp[i - 1][j - 1] + cost # substitution
            )

    return dp[n][m] / n


# ============================================================
# LOAD MODEL
# ============================================================

print("=" * 55)
print("             HINDI STT BASELINE EVALUATION")
print("=" * 55)

print("\nLoading IndicConformer...")

load_start = time.perf_counter()

model = AutoModel.from_pretrained(
    STT_MODEL,
    trust_remote_code=True
)

model.eval()

load_time = time.perf_counter() - load_start

print(f"Model loaded in {load_time:.2f}s")


# ============================================================
# CHECK PATHS
# ============================================================

if not os.path.exists(TEST_TSV):
    print(f"\nERROR: Cannot find {TEST_TSV}")
    sys.exit(1)

if not os.path.isdir(CLIPS_DIR):
    print(f"\nERROR: Cannot find clips directory:")
    print(os.path.abspath(CLIPS_DIR))
    sys.exit(1)


print("\nTest TSV   :", os.path.abspath(TEST_TSV))
print("Clips dir  :", os.path.abspath(CLIPS_DIR))
print("Language   :", LANGUAGE)
print("Samples    :", MAX_SAMPLES)


# ============================================================
# READ TEST TSV
# ============================================================

print("\nReading test.tsv...")

rows = []

with open(TEST_TSV, "r", encoding="utf-8") as f:

    reader = csv.DictReader(f, delimiter="\t")

    for row in reader:

        filename = row.get("path", "").strip()
        sentence = row.get("sentence", "").strip()

        if not filename or not sentence:
            continue

        rows.append({
            "filename": filename,
            "sentence": sentence
        })

        if MAX_SAMPLES is not None and len(rows) >= MAX_SAMPLES:
            break


print(f"Loaded {len(rows)} evaluation samples.")


if len(rows) == 0:
    print("\nERROR: No usable samples found in test.tsv")
    sys.exit(1)


# ============================================================
# EVALUATION
# ============================================================

results = []

total_audio_seconds = 0.0
total_inference_seconds = 0.0

print("\n")
print("=" * 55)
print("Starting evaluation...")
print("=" * 55)


for index, item in enumerate(rows, start=1):

    filename = item["filename"]
    reference = item["sentence"]

    audio_path = os.path.join(CLIPS_DIR, filename)

    if not os.path.exists(audio_path):

        print(
            f"[{index}/{len(rows)}] "
            f"MISSING: {filename}"
        )

        continue

    try:

        # ----------------------------------------------------
        # Load audio
        # ----------------------------------------------------

        audio, sample_rate = sf.read(audio_path)

        # Convert stereo → mono
        if audio.ndim > 1:
            audio = np.mean(audio, axis=1)

        audio = audio.astype(np.float32)

        # ----------------------------------------------------
        # Resample if necessary
        # ----------------------------------------------------

        if sample_rate != 16000:
            import scipy.signal

            new_length = int(
                len(audio) * 16000 / sample_rate
            )

            audio = scipy.signal.resample(
                audio,
                new_length
            ).astype(np.float32)

            sample_rate = 16000

        # Calculate duration after resampling
        audio_duration = len(audio) / sample_rate

        # IndicConformer expects a 2D PyTorch tensor [1, samples]
        wav = torch.tensor(audio, dtype=torch.float32).unsqueeze(0)

        # ----------------------------------------------------
        # STT
        # ----------------------------------------------------

        inference_start = time.perf_counter()

        with torch.no_grad():

            prediction = model(
                wav,
                LANGUAGE,
                "ctc"
            )

        inference_time = time.perf_counter() - inference_start

        hypothesis = str(prediction).strip()

        # ----------------------------------------------------
        # Metrics
        # ----------------------------------------------------

        wer = word_error_rate(
            reference,
            hypothesis
        )

        rtf = (
            inference_time / audio_duration
            if audio_duration > 0
            else 0
        )

        total_audio_seconds += audio_duration
        total_inference_seconds += inference_time

        results.append({
            "filename": filename,
            "reference": reference,
            "prediction": hypothesis,
            "wer": wer,
            "audio_duration": audio_duration,
            "inference_time": inference_time,
            "rtf": rtf
        })

        print(
            f"[{index}/{len(rows)}] "
            f"WER={wer * 100:6.2f}% "
            f"RTF={rtf:5.2f}",
            flush=True
        )

    except Exception as e:

        print(
            f"[{index}/{len(rows)}] "
            f"ERROR: {filename}",
            flush=True
        )

        print("   ", str(e), flush=True)


# ============================================================
# FINAL METRICS
# ============================================================

if not results:

    print("\nNo samples were successfully evaluated.")
    sys.exit(1)


# Corpus-level WER
reference_words = 0
total_edit_distance = 0


for result in results:

    ref_words = normalize_text(
        result["reference"]
    ).split()

    hyp_words = normalize_text(
        result["prediction"]
    ).split()

    n = len(ref_words)
    m = len(hyp_words)

    dp = [[0] * (m + 1) for _ in range(n + 1)]

    for i in range(n + 1):
        dp[i][0] = i

    for j in range(m + 1):
        dp[0][j] = j

    for i in range(1, n + 1):

        for j in range(1, m + 1):

            cost = 0 if (
                ref_words[i - 1] == hyp_words[j - 1]
            ) else 1

            dp[i][j] = min(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost
            )

    reference_words += n
    total_edit_distance += dp[n][m]


corpus_wer = (
    total_edit_distance / reference_words
    if reference_words > 0
    else 0
)

average_rtf = (
    total_inference_seconds / total_audio_seconds
    if total_audio_seconds > 0
    else 0
)

average_latency = (
    total_inference_seconds / len(results)
)


# ============================================================
# SAVE CSV
# ============================================================

output_csv = "stt_baseline_results.csv"

with open(
    output_csv,
    "w",
    newline="",
    encoding="utf-8"
) as f:

    writer = csv.writer(f)

    writer.writerow([
        "filename",
        "reference",
        "prediction",
        "wer",
        "audio_duration",
        "inference_time",
        "rtf"
    ])

    for result in results:

        writer.writerow([
            result["filename"],
            result["reference"],
            result["prediction"],
            f"{result['wer']:.6f}",
            f"{result['audio_duration']:.4f}",
            f"{result['inference_time']:.4f}",
            f"{result['rtf']:.6f}"
        ])


# ============================================================
# REPORT
# ============================================================

print("\n")
print("=" * 55)
print("             BASELINE RESULTS")
print("=" * 55)

print(f"\nSamples evaluated : {len(results)}")

print(
    f"Total audio      : "
    f"{total_audio_seconds:.2f}s"
)

print(
    f"Total STT time   : "
    f"{total_inference_seconds:.2f}s"
)

print(
    f"Corpus WER       : "
    f"{corpus_wer * 100:.2f}%"
)

print(
    f"Average latency  : "
    f"{average_latency:.3f}s"
)

print(
    f"Average RTF      : "
    f"{average_rtf:.3f}"
)

print(f"\nDetailed results : {output_csv}")

print("\n" + "=" * 55)
print("Evaluation complete.")
print("=" * 55)