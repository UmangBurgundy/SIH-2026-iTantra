"""Abstract base interfaces for pluggable STT and TTS backends."""

from abc import ABC, abstractmethod
from typing import Tuple, Dict, Any, Optional
import torch


class BaseSTTBackend(ABC):
    """Abstract interface for Speech-to-Text inference backends."""

    def __init__(self, model_name: str):
        self.model_name = model_name
        self.load_time_sec: float = 0.0

    @abstractmethod
    def load(self) -> None:
        """Load model weights and tokenizer into memory."""
        pass

    @abstractmethod
    def unload(self) -> None:
        """Release model weights and free RAM/VRAM."""
        pass

    @abstractmethod
    def transcribe(
        self,
        wav_tensor: torch.Tensor,
        language: str,
        decoding: str = "ctc",
    ) -> Tuple[str, float]:
        """
        Transcribe audio tensor.
        Returns:
            Tuple of (transcript_text, inference_time_sec)
        """
        pass

    @property
    @abstractmethod
    def is_ready(self) -> bool:
        """Check if model is loaded and ready for inference."""
        pass

    @abstractmethod
    def get_stats(self) -> Dict[str, Any]:
        """Return backend runtime statistics (parameter count, memory footprint, etc.)."""
        pass


class BaseTTSBackend(ABC):
    """Abstract interface for Text-to-Speech synthesis backends."""

    def __init__(self, model_name: str):
        self.model_name = model_name
        self.load_time_sec: float = 0.0

    @abstractmethod
    def load(self) -> None:
        """Load synthesis model into memory."""
        pass

    @abstractmethod
    def unload(self) -> None:
        """Release synthesis model and free RAM/VRAM."""
        pass

    @abstractmethod
    def synthesize(
        self,
        text: str,
        language: str,
        ref_audio_path: Optional[str] = None,
        ref_text: Optional[str] = None,
    ) -> Tuple[bytes, float, float]:
        """
        Synthesize text into speech WAV bytes.
        Returns:
            Tuple of (wav_bytes, inference_time_sec, audio_duration_sec)
        """
        pass

    @property
    @abstractmethod
    def is_ready(self) -> bool:
        """Check if model is loaded and ready for inference."""
        pass

    @abstractmethod
    def get_stats(self) -> Dict[str, Any]:
        """Return backend runtime statistics."""
        pass
