"""Init for backend.schemas package."""
from .speech import (
    HealthResponse,
    LanguageItem,
    LanguagesResponse,
    STTResponse,
    TTSRequest,
    ErrorResponse,
)
from .events import TranscriptEvent
from .protocol import (
    MessageHeader,
    TranscriptMessage,
    AckMessage,
    HeartbeatMessage,
)

__all__ = [
    "HealthResponse",
    "LanguageItem",
    "LanguagesResponse",
    "STTResponse",
    "TTSRequest",
    "ErrorResponse",
    "TranscriptEvent",
    "MessageHeader",
    "TranscriptMessage",
    "AckMessage",
    "HeartbeatMessage",
]
