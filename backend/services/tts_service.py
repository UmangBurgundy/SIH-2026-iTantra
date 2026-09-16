"""Text-to-Speech (TTS) service supporting pluggable Baseline, Lightweight, and Cached Alert backends."""

import logging
import threading
import time
from typing import Tuple, Optional, Dict, Any

from backend.config.settings import settings
from backend.services.backends.base import BaseTTSBackend
from backend.services.backends.tts_indic_f5 import IndicF5Backend
from backend.services.backends.tts_mms import MMSTTSBackend
from backend.services.alert_cache import alert_cache
from backend.utils.errors import ModelUnavailableError

logger = logging.getLogger("itantra.tts")


class TTSService:
    """
    Singleton service managing Text-to-Speech synthesis.
    Supports modular backends:
    - 'baseline': IndicF5 + Vocos (diffusion voice cloning)
    - 'lightweight': Meta MMS-TTS VITS (~36.3M params, RTF ~0.29 on CPU)
    - Pre-synthesized Alert Cache: instant sub-millisecond playback for critical emergency phrases.
    """

    _instance: Optional["TTSService"] = None
    _lock = threading.Lock()

    def __init__(self, default_backend: str = "baseline"):
        self._load_lock = threading.Lock()
        self._inference_lock = threading.Lock()
        self.device = "cuda" if torch_cuda_available() else "cpu"

        # Initialize backends
        self._backends: Dict[str, BaseTTSBackend] = {
            "baseline": IndicF5Backend(model_name=settings.TTS_MODEL_ID),
            "lightweight": MMSTTSBackend(),
        }
        self.active_backend_name = default_backend
        self._backend: BaseTTSBackend = self._backends[default_backend]

    @classmethod
    def get_instance(cls) -> "TTSService":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = cls()
        return cls._instance

    @property
    def model(self):
        """Expose underlying model for backward compatibility."""
        if isinstance(self._backend, IndicF5Backend):
            return self._backend.model
        return None

    @model.setter
    def model(self, val):
        if isinstance(self._backend, IndicF5Backend):
            self._backend.model = val

    @property
    def load_time_sec(self) -> float:
        return self._backend.load_time_sec

    def set_backend(self, backend_type: str) -> None:
        """Switch active backend ('baseline' or 'lightweight')."""
        backend_key = backend_type.lower().strip()
        if backend_key not in self._backends:
            raise ValueError(f"Unknown TTS backend '{backend_type}'. Supported: {list(self._backends.keys())}")

        with self._load_lock:
            if backend_key != self.active_backend_name:
                logger.info(f"Switching TTS backend: {self.active_backend_name} -> {backend_key}")
                self.active_backend_name = backend_key
                self._backend = self._backends[backend_key]

    def load_model(self) -> None:
        """Initialize active TTS backend."""
        with self._load_lock:
            self._backend.load()

    def unload_model(self) -> None:
        """Unload active TTS backend to reclaim memory."""
        with self._load_lock:
            self._backend.unload()

    @property
    def is_ready(self) -> bool:
        return self._backend.is_ready

    def synthesize(
        self,
        text: str,
        language: str,
        ref_audio_path: Optional[str] = None,
        ref_text: Optional[str] = None,
        use_alert_cache: bool = True,
    ) -> Tuple[bytes, float, float]:
        """
        Synthesize text into WAV audio bytes.
        If the phrase is in AlertAudioCache, returns instantly (0ms inference).
        Otherwise delegates to active backend.
        """
        lang = language.strip().lower()
        cleaned_text = text.strip()

        # 1. Check Alert Audio Cache (Instant 0ms emergency announcement)
        if use_alert_cache and alert_cache.is_cached(cleaned_text, lang):
            wav_bytes = alert_cache.get(cleaned_text, lang)
            if wav_bytes:
                logger.info(f"Alert cache hit for phrase '{cleaned_text}' [{lang}]. Instant playback ready.")
                # Estimated duration from WAV header or byte length
                audio_dur = max(0.5, len(wav_bytes) / (2 * 16000))
                return wav_bytes, 0.001, audio_dur

        if not self.is_ready:
            raise ModelUnavailableError("TTS model is not loaded yet")

        with self._inference_lock:
            wav_bytes, infer_time, audio_dur = self._backend.synthesize(
                text=cleaned_text,
                language=lang,
                ref_audio_path=ref_audio_path,
                ref_text=ref_text,
            )

        # Cache emergency alerts if not present
        if use_alert_cache and cleaned_text in alert_cache._memory_cache:
            alert_cache.put(cleaned_text, lang, wav_bytes)

        return wav_bytes, infer_time, audio_dur

    def get_stats(self) -> Dict[str, Any]:
        """Return backend diagnostic stats."""
        stats = self._backend.get_stats()
        stats["active_backend"] = self.active_backend_name
        return stats


def torch_cuda_available() -> bool:
    try:
        import torch
        return torch.cuda.is_available()
    except Exception:
        return False


tts_service = TTSService.get_instance()
