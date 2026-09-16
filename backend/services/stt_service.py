"""Speech-to-Text (STT) service supporting pluggable Baseline and Lightweight backends."""

import logging
import threading
from typing import Tuple, Optional, Dict, Any, Union
import torch

from backend.config.settings import settings
from backend.services.backends.base import BaseSTTBackend
from backend.services.backends.stt_indic_conformer import IndicConformerBackend
from backend.services.backends.stt_whisper import WhisperSTTBackend
from backend.utils.errors import ModelUnavailableError

logger = logging.getLogger("itantra.stt")


class STTService:
    """
    Singleton service managing Speech-to-Text inference.
    Supports modular backends:
    - 'baseline': IndicConformer-600M (high Indic accuracy)
    - 'lightweight': OpenAI Whisper-Tiny (~37.8M params, low RAM, fast CPU RTF)
    """

    _instance: Optional["STTService"] = None
    _lock = threading.Lock()

    def __init__(self, default_backend: str = "baseline"):
        self._load_lock = threading.Lock()
        self._inference_lock = threading.Lock()
        self.device = "cuda" if torch.cuda.is_available() else "cpu"

        # Initialize backends
        self._backends: Dict[str, BaseSTTBackend] = {
            "baseline": IndicConformerBackend(model_name=settings.STT_MODEL_ID),
            "lightweight": WhisperSTTBackend(),
        }
        self.active_backend_name = default_backend
        self._backend: BaseSTTBackend = self._backends[default_backend]

    @classmethod
    def get_instance(cls) -> "STTService":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = cls()
        return cls._instance

    @property
    def model(self):
        """Expose underlying model for backward compatibility."""
        if isinstance(self._backend, IndicConformerBackend):
            return self._backend.model
        elif isinstance(self._backend, WhisperSTTBackend):
            return self._backend.model
        return None

    @model.setter
    def model(self, val):
        if isinstance(self._backend, IndicConformerBackend):
            self._backend.model = val

    @property
    def load_time_sec(self) -> float:
        return self._backend.load_time_sec

    def set_backend(self, backend_type: str) -> None:
        """Switch active backend ('baseline' or 'lightweight')."""
        backend_key = backend_type.lower().strip()
        if backend_key not in self._backends:
            raise ValueError(f"Unknown STT backend '{backend_type}'. Supported: {list(self._backends.keys())}")

        with self._load_lock:
            if backend_key != self.active_backend_name:
                logger.info(f"Switching STT backend: {self.active_backend_name} -> {backend_key}")
                self.active_backend_name = backend_key
                self._backend = self._backends[backend_key]

    def load_model(self) -> None:
        """Initialize the active STT backend model."""
        with self._load_lock:
            self._backend.load()

    def unload_model(self) -> None:
        """Unload active STT backend to reclaim memory."""
        with self._load_lock:
            self._backend.unload()

    @property
    def is_ready(self) -> bool:
        """Return True if active backend is ready."""
        return self._backend.is_ready

    def transcribe(
        self,
        wav_tensor: torch.Tensor,
        language: str,
        decoding: str = "ctc",
    ) -> Tuple[str, float]:
        """
        Transcribe audio waveform to text.
        Delegates to active backend.
        """
        if not self.is_ready:
            raise ModelUnavailableError("STT model is not loaded yet")

        with self._inference_lock:
            return self._backend.transcribe(
                wav_tensor=wav_tensor,
                language=language,
                decoding=decoding,
            )

    def get_stats(self) -> Dict[str, Any]:
        """Return backend diagnostic stats."""
        stats = self._backend.get_stats()
        stats["active_backend"] = self.active_backend_name
        return stats


stt_service = STTService.get_instance()
