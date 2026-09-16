"""Init for backend.routes package."""
from .health import router as health_router
from .languages import router as languages_router
from .speech import router as speech_router
from .voice import router as voice_router

__all__ = [
    "health_router",
    "languages_router",
    "speech_router",
    "voice_router",
]
