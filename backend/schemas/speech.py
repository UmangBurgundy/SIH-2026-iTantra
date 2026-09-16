"""Pydantic schemas for request and response validation."""

from typing import List, Optional
from pydantic import BaseModel, Field, field_validator


class HealthResponse(BaseModel):
    """Health check response."""
    status: str = "ok"
    version: str = "1.0.0"
    stt_status: str = "ready"
    tts_status: str = "ready"


class LanguageItem(BaseModel):
    """Schema for individual language details."""
    code: str
    name: str
    native_name: str
    stt_supported: bool
    tts_supported: bool
    stt_model: Optional[str] = None
    tts_model: Optional[str] = None


class LanguagesResponse(BaseModel):
    """Schema for supported languages list response."""
    languages: List[LanguageItem]


class STTResponse(BaseModel):
    """Schema for speech-to-text transcription response."""
    text: str = Field(..., description="Recognized speech transcription")
    language: str = Field(..., description="Language code of the transcribed audio")
    audio_duration_sec: float = Field(..., description="Audio duration in seconds")
    inference_time_sec: float = Field(..., description="Model processing time in seconds")
    rtf: float = Field(..., description="Real-Time Factor (inference_time / duration)")


class TTSRequest(BaseModel):
    """Schema for text-to-speech synthesis request."""
    text: str = Field(..., min_length=1, max_length=2000, description="Text to synthesize")
    language: str = Field(..., min_length=2, max_length=5, description="Target language code (e.g. 'hi')")
    ref_audio_path: Optional[str] = Field(None, description="Optional custom reference voice audio file path")
    ref_text: Optional[str] = Field(None, description="Optional custom reference voice transcript")

    @field_validator("text")
    @classmethod
    def validate_non_empty_text(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("Text cannot be empty or whitespace only")
        return v.strip()

    @field_validator("language")
    @classmethod
    def normalize_language(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("Language code is required")
        return v.strip().lower()


class ErrorResponse(BaseModel):
    """Schema for structured error response."""
    error: str
    detail: str
    status_code: int
