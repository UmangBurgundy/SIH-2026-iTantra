"""Communication protocol schemas and validation for iTantra two-device messaging."""

import time
import uuid
from typing import Optional, Dict, Any, Literal
from pydantic import BaseModel, Field, field_validator

from backend.config.languages import is_valid_language


class MessageHeader(BaseModel):
    """Protocol message header."""
    message_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    session_id: str = Field(default="default-session")
    sender_id: str = Field(default="device-a")
    recipient_id: Optional[str] = Field(default=None)
    sequence: int = Field(default=1, ge=1)
    timestamp: float = Field(default_factory=time.time)
    version: str = Field(default="1.0")


class TranscriptMessage(BaseModel):
    """Payload for speech-to-text transcript communication."""
    type: Literal["transcript"] = "transcript"
    header: MessageHeader = Field(default_factory=MessageHeader)
    language: str = Field(..., min_length=2, max_length=10)
    text: str = Field(..., min_length=1, max_length=5000)
    speech_duration_ms: int = Field(default=0, ge=0)
    silence_duration_ms: int = Field(default=0, ge=0)

    @field_validator("language")
    @classmethod
    def validate_language(cls, v: str) -> str:
        lang = v.strip().lower()
        if not is_valid_language(lang):
            raise ValueError(f"Language code '{lang}' is not recognized in iTantra supported languages.")
        return lang

    @field_validator("text")
    @classmethod
    def validate_text(cls, v: str) -> str:
        text = v.strip()
        if not text:
            raise ValueError("Transcript text cannot be empty or whitespace only.")
        return text

    def to_dict(self) -> Dict[str, Any]:
        """Serialize message to dictionary."""
        return self.model_dump()


class AckMessage(BaseModel):
    """Acknowledgement message for reliability."""
    type: Literal["ack"] = "ack"
    ack_message_id: str
    sequence: int
    sender_id: str
    timestamp: float = Field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return self.model_dump()


class HeartbeatMessage(BaseModel):
    """Heartbeat ping/pong message."""
    type: Literal["ping", "pong"] = "ping"
    sender_id: str
    timestamp: float = Field(default_factory=time.time)

    def to_dict(self) -> Dict[str, Any]:
        return self.model_dump()
