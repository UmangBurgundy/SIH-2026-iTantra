"""Unit tests for Voice Activity Detection (VAD) service."""

import time
import numpy as np
import pytest

from backend.config.vad_config import VADConfig
from backend.services.vad_service import VADService


class TestVADService:
    """Test suite for Dual-Gate VAD."""

    @pytest.fixture
    def vad(self):
        config = VADConfig(sample_rate=16000, frame_duration_ms=30, vad_mode=2, energy_threshold=0.008)
        return VADService(config)

    def test_vad_initialization(self, vad):
        """1. Verify VAD initializes with correct configuration."""
        assert vad.config.sample_rate == 16000
        assert vad.config.frame_duration_ms == 30
        assert vad.config.frame_samples == 480
        assert vad.config.frame_bytes == 960

    def test_vad_mode_switching(self, vad):
        """Verify VAD aggressiveness modes (0-3)."""
        for mode in [0, 1, 2, 3]:
            vad.set_mode(mode)
            assert vad.config.vad_mode == mode

    def test_silence_detection(self, vad):
        """2. Verify pure digital silence is classified as non-speech."""
        silence_frame = np.zeros(vad.config.frame_samples, dtype=np.int16)
        is_speech = vad.is_speech_frame(silence_frame)
        assert is_speech is False

        # Raw bytes silence
        silence_bytes = b"\x00" * vad.config.frame_bytes
        assert vad.is_speech_frame(silence_bytes) is False

    def test_low_energy_noise_rejected_by_energy_gate(self, vad):
        """Verify low-level hiss below energy floor is rejected immediately."""
        # Noise with RMS energy ~ 0.002 (below 0.008 threshold)
        noise = (np.random.normal(0, 50, vad.config.frame_samples)).astype(np.int16)
        assert vad.compute_energy(noise) < vad.config.energy_threshold
        assert vad.is_speech_frame(noise) is False

    def test_speech_detection_on_voice_signal(self, vad):
        """3. Verify strong voiced signal (harmonics) triggers speech detection."""
        # Synthesize a voiced harmonic frame (fundamentals around 200 Hz + harmonics)
        sr = vad.config.sample_rate
        t = np.linspace(0, 0.03, vad.config.frame_samples, endpoint=False)
        voiced = (
            0.5 * np.sin(2 * np.pi * 200 * t) +
            0.3 * np.sin(2 * np.pi * 400 * t) +
            0.2 * np.sin(2 * np.pi * 600 * t)
        )
        voiced_int16 = (voiced * 25000).astype(np.int16)

        assert vad.compute_energy(voiced_int16) > vad.config.energy_threshold
        assert vad.is_speech_frame(voiced_int16) is True

    def test_invalid_frame_sample_count(self, vad):
        """Verify error is raised if input frame length does not match frame_samples."""
        wrong_size = np.zeros(300, dtype=np.int16)  # expected 480
        with pytest.raises(ValueError, match="Expected 480 samples"):
            vad.is_speech_frame(wrong_size)

    def test_vad_latency(self, vad):
        """Verify per-frame VAD decision is sub-millisecond (< 0.5 ms)."""
        frame = np.zeros(vad.config.frame_samples, dtype=np.int16)
        durations = []
        for _ in range(50):
            t0 = time.perf_counter()
            vad.is_speech_frame(frame)
            durations.append((time.perf_counter() - t0) * 1000)

        avg_latency_ms = sum(durations) / len(durations)
        assert avg_latency_ms < 0.5, f"VAD latency too high: {avg_latency_ms:.3f} ms"
