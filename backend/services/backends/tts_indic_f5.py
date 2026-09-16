"""IndicF5 TTS Backend (Baseline)."""

import gc
import logging
import os
import threading
import time
from typing import Tuple, Dict, Any, Optional
import numpy as np
import soundfile as sf
import torch
import torchaudio
import transformers.modeling_utils as mu
from transformers import AutoModel

from backend.config.settings import settings
from backend.config.languages import is_tts_supported, TTS_SUPPORTED_LANGUAGES
from backend.services.backends.base import BaseTTSBackend
from backend.utils.audio import array_to_wav_bytes
from backend.utils.errors import ModelUnavailableError, UnsupportedLanguageError

logger = logging.getLogger("itantra.backends.tts.indic_f5")

# Patch transformers meta initialization for Windows torchaudio
mu.PreTrainedModel.get_init_context = classmethod(lambda cls, *a, **k: [])

def _sf_load(filepath, *args, **kwargs):
    data, sr = sf.read(filepath)
    if data.ndim == 1:
        data = data[np.newaxis, :]
    else:
        data = data.T
    return torch.tensor(data, dtype=torch.float32), sr

torchaudio.load = _sf_load


class IndicF5Backend(BaseTTSBackend):
    """
    AI4Bharat IndicF5 TTS Backend.
    Flow-matching diffusion model with reference voice cloning.
    ~350M parameters, ~1.3 GB disk, RTF ~22-28 on CPU.
    """

    def __init__(self, model_name: Optional[str] = None):
        super().__init__(model_name=model_name or settings.TTS_MODEL_ID)
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.model: Optional[AutoModel] = None
        self._lock = threading.Lock()

    def load(self) -> None:
        if self.is_ready:
            return

        with self._lock:
            if self.is_ready:
                return

            logger.info(f"Loading IndicF5 model '{self.model_name}' on {self.device}...")
            start_time = time.perf_counter()
            try:
                model = AutoModel.from_pretrained(
                    self.model_name,
                    trust_remote_code=True,
                )
                model = model.to(self.device)
                self.model = model
                self.load_time_sec = time.perf_counter() - start_time
                logger.info(f"IndicF5 loaded successfully in {self.load_time_sec:.2f}s")
            except Exception as e:
                logger.error(f"Failed to load IndicF5 model: {e}", exc_info=True)
                raise ModelUnavailableError(f"TTS model initialization failed: {e}")

    def unload(self) -> None:
        with self._lock:
            if self.model is not None:
                del self.model
                self.model = None
                gc.collect()
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
                logger.info("IndicF5 unloaded from memory.")

    def synthesize(
        self,
        text: str,
        language: str,
        ref_audio_path: Optional[str] = None,
        ref_text: Optional[str] = None,
    ) -> Tuple[bytes, float, float]:
        if not self.is_ready:
            raise ModelUnavailableError("IndicF5 model is not loaded yet")

        lang = language.strip().lower()
        if not is_tts_supported(lang):
            raise UnsupportedLanguageError(
                lang,
                component=f"TTS (Supported: {', '.join(sorted(TTS_SUPPORTED_LANGUAGES))})"
            )

        ref_audio = ref_audio_path or settings.REF_AUDIO_PATH
        ref_transcript = ref_text or settings.REF_TEXT

        if not os.path.exists(ref_audio):
            raise ModelUnavailableError(f"Reference voice audio file '{ref_audio}' not found.")

        start_time = time.perf_counter()
        with self._lock:
            try:
                raw_audio = self.model(
                    text,
                    ref_audio_path=ref_audio,
                    ref_text=ref_transcript,
                )
            except Exception as e:
                logger.error(f"IndicF5 synthesis error for language '{lang}': {e}", exc_info=True)
                raise

            inference_time_sec = time.perf_counter() - start_time
            audio_array = raw_audio if isinstance(raw_audio, np.ndarray) else raw_audio.cpu().numpy()
            sample_rate = 24000

            if audio_array.ndim > 1:
                audio_array = audio_array.squeeze()

            if np.issubdtype(audio_array.dtype, np.floating):
                max_val = np.max(np.abs(audio_array))
                if max_val > 1.0:
                    audio_array = audio_array / max_val

            wav_bytes = array_to_wav_bytes(audio_array, sample_rate)
            audio_duration_sec = len(audio_array) / float(sample_rate)

            return wav_bytes, inference_time_sec, audio_duration_sec

    @property
    def is_ready(self) -> bool:
        return self.model is not None

    def get_stats(self) -> Dict[str, Any]:
        params = sum(p.numel() for p in self.model.parameters()) if hasattr(self.model, "parameters") else 350_628_454
        return {
            "backend": "IndicF5Backend",
            "model_name": self.model_name,
            "parameters": params,
            "is_ready": self.is_ready,
            "load_time_sec": self.load_time_sec,
            "device": self.device,
        }
