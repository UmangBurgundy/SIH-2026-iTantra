"""Language service handling validation, capabilities, and modular language detection."""

from typing import List, Dict, Any, Optional
from backend.config.languages import (
    SUPPORTED_LANGUAGES,
    STT_SUPPORTED_LANGUAGES,
    TTS_SUPPORTED_LANGUAGES,
    is_valid_language,
    is_stt_supported,
    is_tts_supported,
    get_languages_response,
)
from backend.utils.errors import UnsupportedLanguageError


class LanguageService:
    """Service for managing language capabilities and routing."""

    def validate_for_stt(self, language: str) -> str:
        """Validate language code for Speech-to-Text inference."""
        if not language or not isinstance(language, str):
            raise UnsupportedLanguageError("unknown", component="STT")
        
        lang = language.strip().lower()
        if not is_valid_language(lang):
            raise UnsupportedLanguageError(lang, component="system")
        if not is_stt_supported(lang):
            raise UnsupportedLanguageError(
                lang,
                component=f"STT. The installed IndicConformer model supports: {', '.join(sorted(STT_SUPPORTED_LANGUAGES))}"
            )
        return lang

    def validate_for_tts(self, language: str) -> str:
        """Validate language code for Text-to-Speech synthesis."""
        if not language or not isinstance(language, str):
            raise UnsupportedLanguageError("unknown", component="TTS")

        lang = language.strip().lower()
        if not is_valid_language(lang):
            raise UnsupportedLanguageError(lang, component="system")
        if not is_tts_supported(lang):
            raise UnsupportedLanguageError(
                lang,
                component=f"TTS. Supported languages: {', '.join(sorted(TTS_SUPPORTED_LANGUAGES))}"
            )
        return lang

    def get_supported_languages(self) -> List[Dict[str, Any]]:
        """Return list of supported languages with model status."""
        return get_languages_response()

    def detect_language(self, audio_data: Any) -> Optional[str]:
        """
        Extensible hook for open-source Speech Language Identification (LID).
        In the current release, explicit language parameter is used.
        """
        return None


language_service = LanguageService()
