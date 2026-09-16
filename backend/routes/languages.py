"""Supported languages route."""

from fastapi import APIRouter
from backend.schemas.speech import LanguagesResponse
from backend.services.language_service import language_service

router = APIRouter(tags=["Languages"])


@router.get("/languages", response_model=LanguagesResponse)
async def get_languages():
    """
    Return all verified supported languages and their component capabilities (STT/TTS).
    Does not advertise capabilities not supported by installed models.
    """
    languages_list = language_service.get_supported_languages()
    return LanguagesResponse(languages=languages_list)
