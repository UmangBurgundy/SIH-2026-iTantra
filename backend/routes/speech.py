"""Speech-to-Text and Text-to-Speech endpoints."""

import logging
from fastapi import APIRouter, UploadFile, File, Form, Response, status
from backend.schemas.speech import STTResponse, TTSRequest
from backend.services.stt_service import stt_service
from backend.services.tts_service import tts_service
from backend.services.language_service import language_service
from backend.utils.audio import load_audio_bytes, AudioProcessingError
from backend.utils.errors import AudioValidationError

logger = logging.getLogger("itanta.routes.speech")

router = APIRouter(tags=["Speech"])


@router.post(
    "/speech-to-text",
    response_model=STTResponse,
    summary="Transcribe audio to text",
    status_code=status.HTTP_200_OK,
)
async def speech_to_text(
    audio: UploadFile = File(..., description="Audio file to transcribe (e.g. WAV, MP3, FLAC)"),
    language: str = Form(..., description="Target language code (e.g. 'hi', 'gu', 'ta')"),
    decoding: str = Form("ctc", description="Decoding strategy: 'ctc' or 'rnnt'"),
):
    """
    Process incoming speech audio and transcribe using IndicConformer.

    - Validates audio format, duration, and file size.
    - Validates target language support against IndicConformer capabilities.
    - Returns transcribed text with duration and latency metrics.
    """
    # 1. Validate language
    validated_lang = language_service.validate_for_stt(language)

    # 2. Read audio payload
    if not audio:
        raise AudioValidationError("No audio file was uploaded.")

    content = await audio.read()
    if not content or len(content) == 0:
        raise AudioValidationError("Uploaded audio file is empty (0 bytes).")

    # 3. Preprocess audio
    try:
        wav_tensor, duration_sec = load_audio_bytes(content, target_sr=16000)
    except AudioProcessingError as e:
        raise AudioValidationError(str(e))

    # 4. Transcribe using singleton STT service
    text, inference_time = stt_service.transcribe(
        wav_tensor=wav_tensor,
        language=validated_lang,
        decoding=decoding.lower() if decoding in ("ctc", "rnnt") else "ctc",
    )

    rtf = round(inference_time / duration_sec, 3) if duration_sec > 0 else 0.0

    return STTResponse(
        text=text,
        language=validated_lang,
        audio_duration_sec=round(duration_sec, 2),
        inference_time_sec=round(inference_time, 2),
        rtf=rtf,
    )


@router.post(
    "/text-to-speech",
    summary="Synthesize text to speech audio",
    status_code=status.HTTP_200_OK,
    responses={
        200: {
            "content": {"audio/wav": {}},
            "description": "Generated WAV audio stream",
        }
    },
)
async def text_to_speech(request: TTSRequest):
    """
    Synthesize speech from input text using IndicF5.

    - Validates text content and target language.
    - Uses zero-shot voice cloning with pre-configured reference voice or custom sample.
    - Returns raw WAV audio stream.
    """
    # 1. Validate language
    validated_lang = language_service.validate_for_tts(request.language)

    # 2. Synthesize audio
    wav_bytes, inference_time, duration_sec = tts_service.synthesize(
        text=request.text,
        language=validated_lang,
        ref_audio_path=request.ref_audio_path,
        ref_text=request.ref_text,
    )

    rtf = round(inference_time / duration_sec, 3) if duration_sec > 0 else 0.0

    return Response(
        content=wav_bytes,
        media_type="audio/wav",
        headers={
            "Content-Disposition": "attachment; filename=synthesized_speech.wav",
            "X-Language": validated_lang,
            "X-Inference-Time-Sec": str(round(inference_time, 2)),
            "X-Audio-Duration-Sec": str(round(duration_sec, 2)),
            "X-RTF": str(rtf),
        },
    )
