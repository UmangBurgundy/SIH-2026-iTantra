"""Lightweight Meta MMS-TTS (VITS) Backend."""

import gc
import logging
import threading
import time
from typing import Tuple, Dict, Any, Optional
import numpy as np
import torch
from transformers import VitsModel, AutoTokenizer

from backend.services.backends.base import BaseTTSBackend
from backend.utils.audio import array_to_wav_bytes
from backend.utils.errors import ModelUnavailableError, UnsupportedLanguageError

logger = logging.getLogger("itantra.backends.tts.mms")

# Mapping of ISO-639-1 / project language codes to Meta MMS-TTS model IDs
MMS_LANG_MAP = {
    "hi": "facebook/mms-tts-hin",
    "gu": "facebook/mms-tts-guj",
    "mr": "facebook/mms-tts-mar",
    "kn": "facebook/mms-tts-kan",
    "ml": "facebook/mms-tts-mal",
    "ta": "facebook/mms-tts-tam",
    "te": "facebook/mms-tts-tel",
    "or": "facebook/mms-tts-ory",
    "bn": "facebook/mms-tts-ben",
    "en": "facebook/mms-tts-eng",
}


class MMSTTSBackend(BaseTTSBackend):
    """
    Lightweight, fast Text-to-Speech backend using Meta MMS-TTS (VITS architecture).
    - Parameters: ~36.3M per language (~130 MB disk)
    - RAM Footprint: ~150 MB (lazy loaded per active language)
    - RTF on CPU: ~0.25 - 0.30 (Generates 1s audio in ~0.28s)
    - Directly exportable to ONNX / ONNX Runtime Mobile / Sherpa-ONNX.
    """

    def __init__(self, default_language: str = "hi", max_cached_models: int = 2):
        super().__init__(model_name=MMS_LANG_MAP.get(default_language, "facebook/mms-tts-hin"))
        self.default_language = default_language
        self.max_cached_models = max_cached_models
        self.device = "cuda" if torch.cuda.is_available() else "cpu"

        # Cache: {lang_code: (model, tokenizer)}
        self._models: Dict[str, Tuple[VitsModel, AutoTokenizer]] = {}
        self._lru_keys = []
        self._lock = threading.Lock()

    def _load_language_model(self, lang: str) -> Tuple[VitsModel, AutoTokenizer]:
        """Load MMS-TTS VITS model for a specific language."""
        if lang not in MMS_LANG_MAP:
            raise UnsupportedLanguageError(lang, component=f"MMS-TTS (Supported: {', '.join(sorted(MMS_LANG_MAP.keys()))})")

        if lang in self._models:
            # Move to end of LRU
            self._lru_keys.remove(lang)
            self._lru_keys.append(lang)
            return self._models[lang]

        # Evict oldest if cache limit reached to conserve RAM
        if len(self._models) >= self.max_cached_models:
            oldest_lang = self._lru_keys.pop(0)
            logger.info(f"Evicting MMS-TTS model '{oldest_lang}' from cache to free memory.")
            del self._models[oldest_lang]
            gc.collect()

        model_id = MMS_LANG_MAP[lang]
        logger.info(f"Loading MMS-TTS model for '{lang}' ({model_id}) on {self.device}...")
        t0 = time.perf_counter()
        try:
            tokenizer = AutoTokenizer.from_pretrained(model_id)
            model = VitsModel.from_pretrained(model_id)
            model = model.to(self.device)
            model.eval()
            load_time = time.perf_counter() - t0
            logger.info(f"MMS-TTS model for '{lang}' loaded in {load_time:.2f}s")
            self._models[lang] = (model, tokenizer)
            self._lru_keys.append(lang)
            return model, tokenizer
        except Exception as e:
            logger.error(f"Failed to load MMS-TTS model for '{lang}': {e}", exc_info=True)
            raise ModelUnavailableError(f"MMS-TTS model initialization failed for '{lang}': {e}")

    def load(self) -> None:
        """Preload the default language model."""
        with self._lock:
            self._load_language_model(self.default_language)

    def unload(self) -> None:
        """Unload all cached models."""
        with self._lock:
            self._models.clear()
            self._lru_keys.clear()
            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            logger.info("All MMS-TTS models unloaded from memory.")

    def synthesize(
        self,
        text: str,
        language: str,
        ref_audio_path: Optional[str] = None,
        ref_text: Optional[str] = None,
    ) -> Tuple[bytes, float, float]:
        lang = language.strip().lower()
        if lang not in MMS_LANG_MAP:
            raise UnsupportedLanguageError(lang, component=f"MMS-TTS (Supported: {', '.join(sorted(MMS_LANG_MAP.keys()))})")

        with self._lock:
            model, tokenizer = self._load_language_model(lang)

            start_time = time.perf_counter()
            try:
                inputs = tokenizer(text.strip(), return_tensors="pt")
                inputs = {k: v.to(self.device) for k, v in inputs.items()}

                with torch.no_grad():
                    output = model(**inputs).waveform

                inference_time_sec = time.perf_counter() - start_time
                if hasattr(output, "waveform"):
                    wave_tensor = output.waveform
                else:
                    wave_tensor = output

                if isinstance(wave_tensor, torch.Tensor):
                    waveform = wave_tensor.squeeze().cpu().numpy()
                elif isinstance(wave_tensor, np.ndarray):
                    waveform = wave_tensor.squeeze()
                else:
                    waveform = np.asarray(wave_tensor).squeeze()

                sample_rate = getattr(model.config, "sampling_rate", 16000)

                if isinstance(waveform, np.ndarray) and np.issubdtype(waveform.dtype, np.floating) and waveform.size > 0:
                    max_val = np.max(np.abs(waveform))
                    if max_val > 1.0:
                        waveform = waveform / max_val

                wav_bytes = array_to_wav_bytes(waveform, sample_rate)
                audio_duration_sec = len(waveform) / float(sample_rate)

                return wav_bytes, inference_time_sec, audio_duration_sec
            except Exception as e:
                logger.error(f"MMS-TTS synthesis error for language '{lang}': {e}", exc_info=True)
                raise

    @property
    def is_ready(self) -> bool:
        return len(self._models) > 0

    def get_stats(self) -> Dict[str, Any]:
        return {
            "backend": "MMSTTSBackend",
            "active_languages": list(self._models.keys()),
            "cached_models_count": len(self._models),
            "max_cached_models": self.max_cached_models,
            "device": self.device,
        }
