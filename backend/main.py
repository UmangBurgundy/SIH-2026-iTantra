"""FastAPI application entrypoint for iTantra Multilingual Voice AI Backend."""

import logging
import sys
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.exceptions import RequestValidationError

from backend.config.settings import settings
from backend.routes import health_router, languages_router, speech_router, voice_router
from backend.services.stt_service import stt_service
from backend.services.tts_service import tts_service
from backend.utils.errors import (
    iTantraException,
    itantra_exception_handler,
    validation_exception_handler,
    global_exception_handler,
)

# Ensure UTF-8 output on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("itantra.main")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Application lifespan manager.
    Initializes and caches open-source STT and TTS models once at startup.
    """
    logger.info("Initializing iTantra Multilingual Voice AI Backend...")
    try:
        # Pre-load STT model
        logger.info("Pre-loading IndicConformer STT model...")
        stt_service.load_model()
    except Exception as e:
        logger.error(f"Error loading STT model on startup: {e}")

    try:
        # Pre-load TTS model
        logger.info("Pre-loading IndicF5 TTS model...")
        tts_service.load_model()
    except Exception as e:
        logger.error(f"Error loading TTS model on startup: {e}")

    logger.info("iTantra Backend startup complete. Ready to serve requests.")
    yield
    logger.info("Shutting down iTantra Backend...")


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    description="Multilingual Voice AI Backend powered by IndicConformer and IndicF5 open-source models.",
    lifespan=lifespan,
)

# Enable CORS for frontend integration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Exception handlers
app.add_exception_handler(iTantraException, itantra_exception_handler)
app.add_exception_handler(RequestValidationError, validation_exception_handler)
app.add_exception_handler(Exception, global_exception_handler)

# Register routes
app.include_router(health_router, prefix=settings.API_PREFIX)
app.include_router(languages_router, prefix=settings.API_PREFIX)
app.include_router(speech_router, prefix=settings.API_PREFIX)
app.include_router(voice_router, prefix=settings.API_PREFIX)


@app.get("/")
async def root():
    """Root info endpoint."""
    return {
        "name": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "status": "online",
        "docs_url": "/docs",
        "health_url": f"{settings.API_PREFIX}/health",
        "languages_url": f"{settings.API_PREFIX}/languages",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("backend.main:app", host=settings.HOST, port=settings.PORT, reload=settings.DEBUG)
