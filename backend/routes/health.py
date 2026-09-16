"""Health check route."""

from fastapi import APIRouter
from backend.schemas.speech import HealthResponse
from backend.services.stt_service import stt_service
from backend.services.tts_service import tts_service

router = APIRouter(tags=["Health"])


@router.get("/health", response_model=HealthResponse)
async def get_health():
    """Return operational status and model readiness."""
    return HealthResponse(
        status="ok",
        version="1.0.0",
        stt_status="ready" if stt_service.is_ready else "not_loaded",
        tts_status="ready" if tts_service.is_ready else "not_loaded",
    )
