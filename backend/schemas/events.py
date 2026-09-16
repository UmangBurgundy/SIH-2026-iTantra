"""Event schemas for live microphone streaming and transcript dispatching."""

import time
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field


class TranscriptEvent(BaseModel):
    """Event emitted when a complete sentence/utterance is finalized and transcribed."""
    type: str = Field(default="transcript", description="Event type identifier")
    language: str = Field(..., description="Language code of the transcription")
    text: str = Field(..., description="Recognized speech text")
    sentence_index: int = Field(..., description="1-based sequence index in the session")
    speech_duration_ms: int = Field(..., description="Active speech duration in milliseconds")
    silence_duration_ms: int = Field(..., description="Terminal silence duration in ms that closed the utterance")
    stt_latency_ms: float = Field(..., description="Time taken by STT model inference in ms")
    total_latency_ms: float = Field(..., description="Total processing latency from speech completion to transcript in ms")
    timestamp: float = Field(default_factory=time.time, description="Unix timestamp of event generation")

    def to_dict(self) -> Dict[str, Any]:
        """Convert event to dictionary."""
        return self.model_dump()
