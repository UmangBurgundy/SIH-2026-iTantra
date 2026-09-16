"""Application settings and constants for iTantra backend."""

import os
from pathlib import Path
from pydantic import BaseModel

# Project directory resolution
BACKEND_DIR = Path(__file__).resolve().parent.parent
PROJECT_ROOT = BACKEND_DIR.parent
SPEECH_PROJECT_DIR = PROJECT_ROOT / "speech_project"

# Reference voice defaults for IndicF5 zero-shot generation
DEFAULT_REF_AUDIO = SPEECH_PROJECT_DIR / "audio.wav"
DEFAULT_REF_TEXT = "हेलो माई नेम इज़ उमंग जैन"


class Settings(BaseModel):
    """Application configuration container."""

    APP_NAME: str = "iTantra Multilingual Voice AI Backend"
    APP_VERSION: str = "1.0.0"
    API_PREFIX: str = "/api"

    # STT Configuration
    STT_MODEL_ID: str = "ai4bharat/indic-conformer-600m-multilingual"
    STT_SAMPLE_RATE: int = 16000
    STT_DECODING_MODE: str = "ctc"  # 'ctc' or 'rnnt'

    # TTS Configuration
    TTS_MODEL_ID: str = "ai4bharat/IndicF5"
    TTS_SAMPLE_RATE: int = 24000
    REF_AUDIO_PATH: str = str(DEFAULT_REF_AUDIO)
    REF_TEXT: str = DEFAULT_REF_TEXT

    # Audio Validation & Limits
    MAX_AUDIO_SIZE_BYTES: int = 25 * 1024 * 1024  # 25 MB max upload
    MAX_AUDIO_DURATION_SEC: float = 60.0          # 60 seconds max
    MIN_AUDIO_DURATION_SEC: float = 0.2           # 200 ms min

    ALLOWED_AUDIO_EXTENSIONS: tuple = (
        ".wav", ".wave", ".mp3", ".flac", ".ogg", ".opus", ".m4a"
    )

    # Server settings
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    DEBUG: bool = False


settings = Settings()
