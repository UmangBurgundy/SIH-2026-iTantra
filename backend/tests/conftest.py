"""Pytest fixtures for iTantra backend testing."""

import io
import os
from pathlib import Path
import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient

from backend.main import app
from backend.services.stt_service import stt_service
from backend.services.tts_service import tts_service


@pytest.fixture(scope="session")
def client():
    """Create a FastAPI test client ensuring models are initialized."""
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture(scope="session")
def real_audio_bytes():
    """Load actual project reference audio file (speech_project/audio.wav)."""
    audio_path = Path(__file__).resolve().parent.parent.parent / "speech_project" / "audio.wav"
    assert audio_path.exists(), f"Audio file not found at {audio_path}"
    with open(audio_path, "rb") as f:
        return f.read()


@pytest.fixture(scope="session")
def synthetic_wav_bytes():
    """Create a 1.0 second 16 kHz mono WAV byte stream for fast validation testing."""
    sample_rate = 16000
    duration = 1.0
    t = np.linspace(0, duration, int(sample_rate * duration), endpoint=False)
    # 440 Hz tone with fade in/out
    audio = 0.2 * np.sin(2 * np.pi * 440 * t)
    fade = int(sample_rate * 0.05)
    audio[:fade] *= np.linspace(0, 1, fade)
    audio[-fade:] *= np.linspace(1, 0, fade)

    buf = io.BytesIO()
    sf.write(buf, audio.astype(np.float32), sample_rate, format="WAV", subtype="PCM_16")
    buf.seek(0)
    return buf.read()
