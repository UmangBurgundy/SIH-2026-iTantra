"""Pydantic schemas for the Phase 5 Voice Pipeline API."""

from typing import List, Optional
from pydantic import BaseModel, Field


class FinalizedSentence(BaseModel):
    """Schema representing a finalized sentence or utterance."""
    sentence_index: int = Field(..., description="1-based index of this sentence in the audio session")
    transcript: str = Field(..., description="Recognized speech text for this utterance")
    speech_start_ms: int = Field(..., description="Timestamp in ms where speech began")
    speech_end_ms: int = Field(..., description="Timestamp in ms where speech stopped before final pause")
    speech_duration_ms: int = Field(..., description="Total speech duration in ms excluding terminal silence")
    silence_duration_ms: int = Field(..., description="Terminal silence duration in ms that triggered finalization")
    stt_latency_ms: float = Field(..., description="Time taken by STT model inference in milliseconds")
    total_processing_latency_ms: float = Field(..., description="Total processing time for this utterance in ms")


class VoiceProcessResponse(BaseModel):
    """Schema returned by POST /api/voice/process."""
    language: str = Field(..., description="Language code of the processed audio")
    status: str = Field(..., description="Processing status: 'completed' or 'no_speech_detected'")
    sentences: List[FinalizedSentence] = Field(default_factory=list, description="List of segmented sentences")
    total_audio_duration_ms: int = Field(..., description="Total length of input audio in ms")
    vad_latency_ms: float = Field(..., description="Time spent in Voice Activity Detection in ms")
    total_processing_latency_ms: float = Field(..., description="Total end-to-end pipeline processing time in ms")
    user_perceived_latency_ms: Optional[float] = Field(
        None,
        description="User-perceived end-of-speech latency: terminal silence threshold + STT inference latency"
    )
