"""Integration and functional tests for Phase 5 Voice Pipeline and Pause Detection."""

import numpy as np
import pytest

from backend.config.vad_config import VADConfig
from backend.services.sentence_segmenter import SentenceSegmenter


def make_voiced_frame(samples: int = 480, freq: float = 220.0, sr: int = 16000) -> np.ndarray:
    """Helper to generate a realistic voiced harmonic frame."""
    t = np.linspace(0, samples / sr, samples, endpoint=False)
    signal = 0.6 * np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(2 * np.pi * (freq * 2) * t)
    return (signal * 25000).astype(np.int16)


def make_silent_frame(samples: int = 480) -> np.ndarray:
    """Helper to generate a silent frame."""
    return np.zeros(samples, dtype=np.int16)


class TestSentenceSegmenterLogic:
    """Unit tests for sentence segmentation, pause tolerance, and finalization."""

    @pytest.fixture
    def segmenter(self):
        config = VADConfig(
            sample_rate=16000,
            frame_duration_ms=30,
            vad_mode=2,
            energy_threshold=0.008,
            speech_pad_ms=60,                # 2 frames
            short_pause_threshold_ms=200,    # 200 ms
            silence_threshold_ms=400,        # 400 ms (14 frames)
            min_speech_duration_ms=300,      # 300 ms (10 frames)
            max_utterance_duration_ms=5000,
        )
        return SentenceSegmenter(config)

    def test_idle_on_silence(self, segmenter):
        """Feeding silence frames must keep segmenter in IDLE state without output."""
        silence = make_silent_frame(segmenter.config.frame_samples)
        for _ in range(20):
            res = segmenter.process_frame(silence)
            assert res is None
        assert segmenter.state == "IDLE"

    def test_short_pause_tolerated_without_splitting(self, segmenter):
        """Short pauses (e.g. 150ms < 400ms threshold) should not finalize the sentence."""
        voiced = make_voiced_frame(segmenter.config.frame_samples)
        silence = make_silent_frame(segmenter.config.frame_samples)

        # 1. 500ms speech (17 frames)
        for _ in range(17):
            res = segmenter.process_frame(voiced)
            assert res is None
        assert segmenter.state == "SPEECH"

        # 2. 150ms short pause (5 frames of silence < 400ms stoppage threshold)
        for _ in range(5):
            res = segmenter.process_frame(silence)
            assert res is None
        assert segmenter.state == "PAUSED"

        # 3. Speech resumes (500ms)
        for _ in range(17):
            res = segmenter.process_frame(voiced)
            assert res is None
        assert segmenter.state == "SPEECH"

    def test_sentence_finalization_on_long_pause(self, segmenter):
        """Terminal silence meeting stoppage threshold must finalize and emit utterance."""
        voiced = make_voiced_frame(segmenter.config.frame_samples)
        silence = make_silent_frame(segmenter.config.frame_samples)

        # 1. 600ms speech (20 frames)
        for _ in range(20):
            segmenter.process_frame(voiced)

        # 2. 450ms silence (15 frames >= 400ms silence_threshold_ms)
        utterance = None
        for _ in range(15):
            res = segmenter.process_frame(silence)
            if res is not None:
                utterance = res
                break

        assert utterance is not None
        assert utterance.speech_duration_ms >= 300
        assert segmenter.state == "IDLE"

    def test_noise_burst_rejected_if_below_min_speech_duration(self, segmenter):
        """A brief click or noise burst (< 300ms) followed by silence must be rejected."""
        voiced = make_voiced_frame(segmenter.config.frame_samples)
        silence = make_silent_frame(segmenter.config.frame_samples)

        # Only 90ms of speech (3 frames < 300ms min)
        for _ in range(3):
            segmenter.process_frame(voiced)

        # 450ms silence
        utterance = None
        for _ in range(15):
            res = segmenter.process_frame(silence)
            if res is not None:
                utterance = res

        assert utterance is None
        assert segmenter.state == "IDLE"

    def test_buffer_reset(self, segmenter):
        """Verify reset clears segmenter state completely."""
        voiced = make_voiced_frame(segmenter.config.frame_samples)
        for _ in range(5):
            segmenter.process_frame(voiced)
        assert segmenter.state == "SPEECH"

        segmenter.reset()
        assert segmenter.state == "IDLE"
        assert len(segmenter.speech_buffer) == 0


class TestVoiceProcessAPIEndpoint:
    """Integration test suite for POST /api/voice/process endpoint."""

    def test_voice_process_valid_hindi_audio(self, client, real_audio_bytes):
        """Verify full end-to-end pipeline on real Hindi audio (speech_project/audio.wav)."""
        files = {"audio": ("audio.wav", real_audio_bytes, "audio/wav")}
        data = {"language": "hi", "silence_threshold_ms": 600}

        response = client.post("/api/voice/process", files=files, data=data)
        assert response.status_code == 200
        data = response.json()

        assert data["status"] == "completed"
        assert data["language"] == "hi"
        assert data["total_audio_duration_ms"] > 0
        assert data["vad_latency_ms"] >= 0
        assert data["total_processing_latency_ms"] > 0
        assert len(data["sentences"]) >= 1

        first_sentence = data["sentences"][0]
        assert first_sentence["sentence_index"] == 1
        assert len(first_sentence["transcript"]) > 0
        assert first_sentence["speech_duration_ms"] > 0
        assert first_sentence["stt_latency_ms"] > 0
        assert data["user_perceived_latency_ms"] is not None

    def test_voice_process_pure_silence_returns_no_speech(self, client):
        """Verify pure digital silence yields status='no_speech_detected' with 0 sentences."""
        silence_wav = np.zeros(16000 * 2, dtype=np.int16)  # 2.0 seconds of silence
        import io, soundfile as sf
        buf = io.BytesIO()
        sf.write(buf, silence_wav, 16000, format="WAV", subtype="PCM_16")
        buf.seek(0)

        files = {"audio": ("silence.wav", buf.read(), "audio/wav")}
        data = {"language": "hi"}

        response = client.post("/api/voice/process", files=files, data=data)
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "no_speech_detected"
        assert len(data["sentences"]) == 0

    def test_voice_process_missing_audio_rejected(self, client):
        """Verify request without audio yields 422."""
        data = {"language": "hi"}
        response = client.post("/api/voice/process", data=data)
        assert response.status_code == 422

    def test_voice_process_empty_audio_rejected(self, client):
        """Verify 0-byte audio file yields 400 invalid_audio."""
        files = {"audio": ("empty.wav", b"", "audio/wav")}
        data = {"language": "hi"}
        response = client.post("/api/voice/process", files=files, data=data)
        assert response.status_code == 400
        assert response.json()["error"] == "invalid_audio"

    def test_voice_process_unsupported_language_english(self, client, real_audio_bytes):
        """Verify English (en) returns 400 with honest IndicConformer constraint explanation."""
        files = {"audio": ("audio.wav", real_audio_bytes, "audio/wav")}
        data = {"language": "en"}
        response = client.post("/api/voice/process", files=files, data=data)
        assert response.status_code == 400
        assert response.json()["error"] == "unsupported_language"

    def test_voice_process_multilingual_routing(self, client, real_audio_bytes):
        """Verify Indian regional languages (e.g. Gujarati, Marathi) pass validation."""
        for lang in ["gu", "mr"]:
            files = {"audio": ("audio.wav", real_audio_bytes, "audio/wav")}
            data = {"language": lang, "silence_threshold_ms": 600}
            response = client.post("/api/voice/process", files=files, data=data)
            assert response.status_code == 200
            assert response.json()["language"] == lang
