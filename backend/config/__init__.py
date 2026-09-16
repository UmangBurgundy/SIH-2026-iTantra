"""Configuration package for iTantra backend."""
from .languages import (
    ALL_LANGUAGES,
    SUPPORTED_LANGUAGES,
    STT_SUPPORTED_LANGUAGES,
    TTS_SUPPORTED_LANGUAGES,
    is_valid_language,
    is_stt_supported,
    is_tts_supported,
    get_languages_response,
)
from .settings import settings

__all__ = [
    "ALL_LANGUAGES",
    "SUPPORTED_LANGUAGES",
    "STT_SUPPORTED_LANGUAGES",
    "TTS_SUPPORTED_LANGUAGES",
    "is_valid_language",
    "is_stt_supported",
    "is_tts_supported",
    "get_languages_response",
    "settings",
]
