"""Interactive CLI Live Microphone Test Runner for iTantra.

Captures real microphone audio, processes it through Dual-Gate VAD,
tolerates short pauses, finalizes sentences on stoppage, and prints IndicConformer transcripts.

Usage:
    python scripts/live_mic_test.py [--lang hi] [--device 1] [--silence 800]
"""

import argparse
import os
import sys
import time

# Ensure project root is in sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

# Ensure UTF-8 output on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

import sounddevice as sd
from backend.config.languages import SUPPORTED_LANGUAGES, STT_SUPPORTED_LANGUAGES
from backend.config.vad_config import VADConfig, default_vad_config
from backend.services.microphone_service import microphone_service
from backend.services.stt_service import stt_service
from backend.schemas.events import TranscriptEvent


def on_transcript_received(event: TranscriptEvent):
    """Callback invoked when an utterance is finalized and transcribed."""
    print("\n" + "=" * 55)
    print(f"🎙️ SENTENCE #{event.sentence_index} FINALIZED ({event.language.upper()})")
    print("=" * 55)
    print(f"  Transcript        : \"{event.text}\"")
    print(f"  Speech Duration   : {event.speech_duration_ms} ms")
    print(f"  Silence Stoppage  : {event.silence_duration_ms} ms")
    print(f"  STT Model Latency : {event.stt_latency_ms} ms")
    print(f"  Total Latency     : {event.total_latency_ms} ms")
    print("=" * 55)
    print("Listening for next sentence... (speak naturally, pause to finalize, Ctrl+C to stop)\n")


def main():
    parser = argparse.ArgumentParser(description="iTantra Live Microphone Speech Pipeline")
    parser.add_argument("--lang", default="hi", help="Target language code (e.g. hi, gu, mr, ta, te)")
    parser.add_argument("--device", type=int, default=None, help="Audio input device index")
    parser.add_argument("--silence", type=int, default=800, help="Silence stoppage threshold in ms (default: 800)")
    parser.add_argument("--vad-mode", type=int, default=2, choices=[0, 1, 2, 3], help="WebRTC VAD mode (default: 2)")
    args = parser.parse_args()

    print("==================================================")
    print("     iTANTRA LIVE MICROPHONE SPEECH PIPELINE")
    print("==================================================")

    # 1. Validate language
    lang = args.lang.lower().strip()
    if lang not in STT_SUPPORTED_LANGUAGES:
        print(f"❌ Error: Language '{lang}' is not supported for STT.")
        print(f"Supported Indic languages: {', '.join(sorted(STT_SUPPORTED_LANGUAGES))}")
        if lang == "en":
            print("Note: English is not in IndicConformer-600M's 22 Indian regional languages.")
        sys.exit(1)

    lang_name = SUPPORTED_LANGUAGES.get(lang, lang.upper())
    print(f"Selected Language : {lang_name} ({lang})")

    # 2. Query audio devices
    devices = sd.query_devices()
    default_dev = sd.default.device[0]
    dev_index = args.device if args.device is not None else default_dev

    if dev_index is not None and 0 <= dev_index < len(devices):
        dev_info = devices[dev_index]
        print(f"Microphone Device : [{dev_index}] {dev_info['name']} (Channels: {dev_info['max_input_channels']})")
    else:
        print(f"Microphone Device : Default (Index: {default_dev})")

    # 3. Pre-load STT model
    print("\nPre-loading IndicConformer STT model...")
    t0 = time.perf_counter()
    stt_service.load_model()
    print(f"STT Model Ready ({time.perf_counter() - t0:.2f}s)")

    # 4. Configure VAD
    vad_config = VADConfig(
        silence_threshold_ms=args.silence,
        vad_mode=args.vad_mode,
    )
    print(f"VAD Configuration : WebRTC Mode {vad_config.vad_mode}, Silence Stoppage {vad_config.silence_threshold_ms}ms")

    # 5. Start live session
    print("\n--------------------------------------------------")
    print("Status: 🔴 LIVE & LISTENING...")
    print("Speak into your microphone naturally.")
    print("Short pauses will be tolerated; long pauses will finalize the sentence.")
    print("Press Ctrl+C at any time to stop.")
    print("--------------------------------------------------\n")

    microphone_service.start(
        language=lang,
        vad_config=vad_config,
        device=args.device,
        on_transcript=on_transcript_received,
    )

    try:
        while microphone_service.is_active():
            time.sleep(0.1)
    except KeyboardInterrupt:
        print("\n\nStopping session and flushing pending speech buffer...")

    # Stop and flush
    events = microphone_service.stop()
    diag = microphone_service.get_stream_diagnostics()

    print("\n==================================================")
    print("                SESSION SUMMARY")
    print("==================================================")
    print(f"Total Sentences Transcribed : {len(events)}")
    if events:
        for ev in events:
            print(f"  [{ev.sentence_index}] \"{ev.text}\" ({ev.speech_duration_ms}ms speech, {ev.total_latency_ms}ms latency)")
    print("\nStream Diagnostics:")
    print(f"  Processed Frames          : {diag['processed_frames']}")
    print(f"  Avg Frame Processing Time : {diag['avg_frame_processing_latency_ms']} ms / frame")
    print(f"  Dropped Chunks            : {diag['dropped_chunks']}")
    print(f"  Callback Overflows        : {diag['callback_overflows']}")
    print("==================================================")


if __name__ == "__main__":
    main()
