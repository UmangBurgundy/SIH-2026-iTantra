"""Lightweight Whisper STT Backend (OpenAI Whisper-Tiny)."""

import gc
import logging
import threading
import time
from typing import Tuple, Dict, Any, Optional
import torch
import numpy as np
from transformers import AutoProcessor, AutoModelForSpeechSeq2Seq

from backend.services.backends.base import BaseSTTBackend
from backend.utils.errors import ModelUnavailableError

logger = logging.getLogger("itantra.backends.stt.whisper")


class WhisperSTTBackend(BaseSTTBackend):
    """
    Lightweight STT backend using OpenAI Whisper-Tiny.
    ~37.8M parameters, ~150 MB disk, ~40 MB RAM delta, RTF ~0.075 on CPU.
    Provides multilingual support (Hindi, Gujarati, Marathi, Tamil, Telugu, Bengali, Kannada, Malayalam, English).
    """

    def __init__(self, model_name: str = "openai/whisper-tiny"):
        super().__init__(model_name=model_name)
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.model: Optional[AutoModelForSpeechSeq2Seq] = None
        self.processor: Optional[AutoProcessor] = None
        self._lock = threading.Lock()

    def load(self) -> None:
        if self.is_ready:
            return

        with self._lock:
            if self.is_ready:
                return

            logger.info(f"Loading Lightweight Whisper model '{self.model_name}' on {self.device}...")
            start_time = time.perf_counter()
            try:
                self.processor = AutoProcessor.from_pretrained(self.model_name)
                model = AutoModelForSpeechSeq2Seq.from_pretrained(self.model_name)
                model = model.to(self.device)
                model.eval()
                self.model = model
                self.load_time_sec = time.perf_counter() - start_time
                logger.info(f"Whisper-Tiny loaded successfully in {self.load_time_sec:.2f}s")
            except Exception as e:
                logger.error(f"Failed to load Whisper model: {e}", exc_info=True)
                raise ModelUnavailableError(f"Whisper STT initialization failed: {e}")

    def unload(self) -> None:
        with self._lock:
            if self.model is not None:
                del self.model
                del self.processor
                self.model = None
                self.processor = None
                gc.collect()
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
                logger.info("Whisper STT unloaded from memory.")

    def transcribe(
        self,
        wav_tensor: torch.Tensor,
        language: str,
        decoding: str = "greedy",
    ) -> Tuple[str, float]:
        if not self.is_ready:
            raise ModelUnavailableError("Whisper model is not loaded yet")

        # Convert tensor to numpy 1D array at 16kHz
        with self._lock:
            if isinstance(wav_tensor, torch.Tensor):
                audio_np = wav_tensor.squeeze().cpu().numpy()
            else:
                audio_np = np.asarray(wav_tensor).squeeze()

            if audio_np.dtype == np.int16:
                audio_np = audio_np.astype(np.float32) / 32768.0

            lang = language.strip().lower()
            start_time = time.perf_counter()
            try:
                inputs = self.processor(audio_np, sampling_rate=16000, return_tensors="pt")
                input_features = inputs.input_features.to(self.device)

                with torch.no_grad():
                    gen_ids = self.model.generate(
                        input_features,
                        language=lang if lang in ["hi", "en", "mr", "ta", "te", "kn", "ml", "gu", "bn"] else None,
                        task="transcribe",
                    )

                transcript = self.processor.batch_decode(gen_ids, skip_special_tokens=True)[0]
                inference_time_sec = time.perf_counter() - start_time
                return transcript.strip(), inference_time_sec
            except Exception as e:
                logger.error(f"Whisper transcription error: {e}", exc_info=True)
                raise

    @property
    def is_ready(self) -> bool:
        return self.model is not None and self.processor is not None

    def get_stats(self) -> Dict[str, Any]:
        params = sum(p.numel() for p in self.model.parameters()) if self.is_ready else 37_760_640
        return {
            "backend": "WhisperSTTBackend",
            "model_name": self.model_name,
            "parameters": params,
            "is_ready": self.is_ready,
            "load_time_sec": self.load_time_sec,
            "device": self.device,
        }
