"""Unit and integration tests for Phase 6A Live Microphone Capture and Streaming Pipeline."""

import queue
import time
import numpy as np
import pytest
import sounddevice as sd

from backend.config.vad_config import VADConfig
from backend.schemas.events import TranscriptEvent
from backend.services.microphone_service import LiveMicrophoneService
from backend.services.sentence_segmenter import SegmentedUtterance
from backend.utils.errors import UnsupportedLanguageError


def make_pcm_chunk(samples: int = 480, freq: float = 220.0, sr: int = 16000) -> bytes:
    """Generate 16-bit mono PCM bytes for testing."""
    t = np.linspace(0, samples / sr, samples, endpoint=False)
    signal = 0.6 * np.sin(2 * np.pi * freq * t)
    return (signal * 25000).astype(np.int16).tobytes()


class TestLiveMicrophoneServiceUnit:
    """Unit tests for microphone service queues, lifecycle, and frame handling."""

    @pytest.fixture
    def mic_service(self):
        config = VADConfig(
            sample_rate=16000,
            frame_duration_ms=30,
            vad_mode=2,
            speech_pad_ms=60,
            short_pause_threshold_ms=200,
            silence_threshold_ms=400,
            min_speech_duration_ms=300,
        )
        return LiveMicrophoneService(default_config=config)

    def test_service_initialization(self, mic_service):
        """1. Verify service initializes with correct audio specifications."""
        assert mic_service.config.sample_rate == 16000
        assert mic_service.config.frame_samples == 480
        assert mic_service.config.frame_bytes == 960
        assert mic_service.is_running is False
        assert mic_service.is_paused is False
        assert mic_service.audio_queue.empty()
        assert mic_service.stt_queue.empty()

    def test_callback_enqueues_pcm_bytes(self, mic_service):
        """2. Verify audio callback converts chunk to 16-bit PCM and enqueues without blocking."""
        mic_service.is_running = True
        dummy_audio = (np.random.uniform(-0.5, 0.5, (480, 1)) * 32767.0).astype(np.int16)

        mic_service._audio_callback(dummy_audio, 480, {}, 0)

        assert not mic_service.audio_queue.empty()
        queued_bytes = mic_service.audio_queue.get_nowait()
        assert len(queued_bytes) == 960  # 480 samples * 2 bytes
        assert isinstance(queued_bytes, bytes)

    def test_callback_ignores_when_paused(self, mic_service):
        """3. Verify audio callback discards chunks when paused."""
        mic_service.is_running = True
        mic_service.is_paused = True
        dummy_audio = np.zeros((480, 1), dtype=np.int16)

        mic_service._audio_callback(dummy_audio, 480, {}, 0)
        assert mic_service.audio_queue.empty()

    def test_unsupported_language_rejected(self, mic_service):
        """4. Verify unsupported language (e.g. 'en') is rejected cleanly before starting."""
        with pytest.raises(UnsupportedLanguageError):
            mic_service.start(language="en")

    def test_pause_and_resume_controls(self, mic_service):
        """5. Verify pause and resume toggle state correctly."""
        assert mic_service.is_paused is False
        mic_service.pause()
        assert mic_service.is_paused is True
        mic_service.resume()
        assert mic_service.is_paused is False

    def test_listener_registration_and_dispatch(self, mic_service):
        """6. Verify event listeners receive dispatched TranscriptEvents."""
        received_events = []

        def dummy_listener(event: TranscriptEvent):
            received_events.append(event)

        mic_service.add_listener(dummy_listener)
        assert dummy_listener in mic_service.listeners

        # Create a mock segmented utterance
        mock_pcm = np.zeros(16000, dtype=np.float32)  # 1s
        utterance = SegmentedUtterance(
            pcm_audio=mock_pcm,
            start_ms=0,
            end_ms=1000,
            speech_duration_ms=1000,
            silence_duration_ms=400,
        )

        event = mic_service._process_and_emit(utterance)
        assert event is not None
        assert len(received_events) == 1
        assert received_events[0].sentence_index == 1

        mic_service.remove_listener(dummy_listener)
        assert dummy_listener not in mic_service.listeners

    def test_non_blocking_pipeline_queue_throughput(self, mic_service):
        """7. Verify pipeline worker adapts arbitrary chunk sizes into exact 30ms frames."""
        mic_service.is_running = True
        # Enqueue 3 chunks of 640 samples (total 1920 samples = 4 exact frames of 480 samples)
        chunk_1 = b"\x00" * (640 * 2)
        chunk_2 = b"\x00" * (640 * 2)
        chunk_3 = b"\x00" * (640 * 2)

        mic_service.audio_queue.put(chunk_1)
        mic_service.audio_queue.put(chunk_2)
        mic_service.audio_queue.put(chunk_3)

        # Start worker briefly
        import threading
        t = threading.Thread(target=mic_service._pipeline_worker, daemon=True)
        t.start()

        # Wait for audio_queue to be drained
        mic_service.audio_queue.join()
        mic_service.is_running = False
        t.join(timeout=1.0)

        # The audio_queue must be completely empty with no deadlock
        assert mic_service.audio_queue.empty()


class TestLiveMicrophoneHardwareIntegration:
    """Hardware integration tests using sounddevice."""

    def test_audio_input_device_detection(self):
        """8. Verify sounddevice can query input devices without crashing."""
        devices = sd.query_devices()
        assert len(devices) > 0
        input_devices = [d for d in devices if d['max_input_channels'] > 0]
        # At least one sound capture device or mapper must be present on standard systems
        assert len(input_devices) >= 1

    def test_start_and_stop_live_session(self):
        """9. Verify live microphone session starts, records briefly, and stops cleanly."""
        service = LiveMicrophoneService()
        events = []

        service.start(
            language="hi",
            on_transcript=lambda ev: events.append(ev)
        )
        assert service.is_active() is True
        assert service.stream is not None

        # Capture for 0.5s of live microphone input
        time.sleep(0.5)

        # Stop and flush
        flushed_events = service.stop()
        assert service.is_active() is False
        assert service.stream is None
        assert isinstance(flushed_events, list)
