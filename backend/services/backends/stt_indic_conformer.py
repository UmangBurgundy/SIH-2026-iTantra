"""IndicConformer-600M STT Backend (Baseline)."""

import gc
import logging
import threading
import time
from typing import Tuple, Dict, Any, Optional
import torch
from transformers import AutoModel

from backend.config.settings import settings
from backend.config.languages import is_stt_supported, STT_SUPPORTED_LANGUAGES
from backend.services.backends.base import BaseSTTBackend
from backend.utils.errors import ModelUnavailableError, UnsupportedLanguageError

logger = logging.getLogger("itantra.backends.stt.indic_conformer")


class IndicConformerBackend(BaseSTTBackend):
    """
    AI4Bharat IndicConformer-600M multilingual ASR backend.
    High accuracy across 22 Indic languages, ~600M parameters, 2.4 GB disk.
    """

    def __init__(self, model_name: Optional[str] = None):
        super().__init__(model_name=model_name or settings.STT_MODEL_ID)
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.model: Optional[AutoModel] = None
        self._lock = threading.Lock()

    def load(self) -> None:
        if self.is_ready:
            return

        with self._lock:
            if self.is_ready:
                return

            logger.info(f"Loading IndicConformer model '{self.model_name}' on {self.device}...")
            start_time = time.perf_counter()
            try:
                model = AutoModel.from_pretrained(
                    self.model_name,
                    trust_remote_code=True,
                )
                model = model.to(self.device)
                model.eval()
                self.model = model
                self.load_time_sec = time.perf_counter() - start_time
                logger.info(f"IndicConformer loaded successfully in {self.load_time_sec:.2f}s")
            except Exception as e:
                logger.error(f"Failed to load IndicConformer model: {e}", exc_info=True)
                raise ModelUnavailableError(f"STT model initialization failed: {e}")

    def unload(self) -> None:
        with self._lock:
            if self.model is not None:
                del self.model
                self.model = None
                gc.collect()
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
                logger.info("IndicConformer unloaded from memory.")

    def transcribe(
        self,
        wav_tensor: torch.Tensor,
        language: str,
        decoding: str = "ctc",
    ) -> Tuple[str, float]:
        if not self.is_ready:
            raise ModelUnavailableError("IndicConformer model is not loaded yet")

        lang = language.strip().lower()
        if not is_stt_supported(lang):
            raise UnsupportedLanguageError(
                lang,
                component=f"STT (Supported: {', '.join(sorted(STT_SUPPORTED_LANGUAGES))})"
            )

        with self._lock:
            wav_input = wav_tensor.to(self.device)
            start_time = time.perf_counter()
            try:
                with torch.inference_mode():
                    transcript = self.model(wav_input, lang, decoding)
                inference_time_sec = time.perf_counter() - start_time
                return str(transcript).strip(), inference_time_sec
            except Exception as e:
                logger.error(f"IndicConformer transcription error: {e}", exc_info=True)
                raise

    @property
    def is_ready(self) -> bool:
        return self.model is not None

    def get_stats(self) -> Dict[str, Any]:
        params = sum(p.numel() for p in self.model.parameters()) if self.is_ready else 600_000_000
        return {
            "backend": "IndicConformerBackend",
            "model_name": self.model_name,
            "parameters": params,
            "is_ready": self.is_ready,
            "load_time_sec": self.load_time_sec,
            "device": self.device,
        }
