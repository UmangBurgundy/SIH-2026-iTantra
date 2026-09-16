"""API route for the real-time voice processing pipeline."""

import logging
from typing import Optional
from fastapi import APIRouter, UploadFile, File, Form, status

from backend.config.vad_config import VADConfig, default_vad_config
from backend.schemas.voice import VoiceProcessResponse
from backend.services.voice_pipeline_service import voice_pipeline_service
from backend.services.language_service import language_service
from backend.utils.errors import AudioValidationError

logger = logging.getLogger("itanta.routes.voice")

router = APIRouter(tags=["Voice Pipeline"])


@router.post(
    "/voice/process",
    response_model=VoiceProcessResponse,
    summary="Process speech through VAD, pause detection, sentence formation, and STT",
    status_code=status.HTTP_200_OK,
)
async def process_voice(
    audio: UploadFile = File(..., description="Audio file or microphone stream recording"),
    language: str = Form(..., description="Target language code (e.g. 'hi', 'gu', 'ta')"),
    silence_threshold_ms: Optional[int] = Form(
        None, description="Silence stoppage duration in ms required to finalize a sentence (default: 800)"
    ),
    vad_mode: Optional[int] = Form(
        None, ge=0, le=3, description="WebRTC VAD aggressiveness mode 0-3 (default: 2)"
    ),
):
    """
    Ingest speech audio, execute dual-gate VAD, segment sentences using pause/stoppage detection,
    and generate structured transcripts using the open-source IndicConformer STT service.

    - Distinguishes intra-sentence pauses from sentence-ending stoppages.
    - Yields coherent sentence-level transcripts.
    - Measures VAD latency, STT latency, and user-perceived end-of-speech latency.
    """
    # 1. Validate language
    validated_lang = language_service.validate_for_stt(language)

    # 2. Validate audio upload
    if not audio:
        raise AudioValidationError("No audio file was uploaded.")

    content = await audio.read()
    if not content or len(content) == 0:
        raise AudioValidationError("Uploaded audio file is empty (0 bytes).")

    # 3. Construct custom VAD configuration if overrides provided
    config = default_vad_config
    if silence_threshold_ms is not None or vad_mode is not None:
        config = VADConfig(
            silence_threshold_ms=silence_threshold_ms or default_vad_config.silence_threshold_ms,
            vad_mode=vad_mode if vad_mode is not None else default_vad_config.vad_mode,
        )

    # 4. Run pipeline
    response = voice_pipeline_service.process_audio(
        audio_content=content,
        language=validated_lang,
        vad_config=config,
    )

    return response
