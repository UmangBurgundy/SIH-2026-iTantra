"""Voice Activity Detection (VAD) Service using Dual-Gate WebRTC + RMS Energy."""

import logging
from typing import Union
import numpy as np
import webrtcvad

from backend.config.vad_config import VADConfig, default_vad_config

logger = logging.getLogger("itanta.vad")


class VADService:
    """Dual-Gate Voice Activity Detector combining WebRTC VAD with RMS Energy floor."""

    def __init__(self, config: VADConfig = default_vad_config):
        self.config = config
        self._vad = webrtcvad.Vad(self.config.vad_mode)

    def set_mode(self, mode: int) -> None:
        """Update WebRTC VAD aggressiveness mode (0-3)."""
        mode = max(0, min(3, mode))
        self.config.vad_mode = mode
        self._vad.set_mode(mode)

    def compute_energy(self, pcm_samples: np.ndarray) -> float:
        """Compute Root Mean Square (RMS) energy normalized to [0.0, 1.0]."""
        if pcm_samples.size == 0:
            return 0.0

        if pcm_samples.dtype == np.int16:
            normalized = pcm_samples.astype(np.float32) / 32768.0
        else:
            normalized = pcm_samples.astype(np.float32)

        return float(np.sqrt(np.mean(normalized ** 2)))

    def is_speech_frame(self, frame_data: Union[bytes, np.ndarray]) -> bool:
        """
        Evaluate if an audio frame contains active voice speech.

        Dual-Gate evaluation:
        1. Energy gate: If frame RMS energy is below floor, treat as silence.
        2. WebRTC VAD: Evaluates spectral sub-band Gaussian mixture models.

        Args:
            frame_data: Exactly frame_samples (e.g. 480 for 30ms @ 16kHz)
                        as 16-bit PCM bytes or int16/float32 numpy array.

        Returns:
            bool: True if voice activity is detected, False otherwise.
        """
        # Convert input to both int16 PCM bytes and numpy array
        if isinstance(frame_data, bytes):
            pcm_bytes = frame_data
            pcm_array = np.frombuffer(pcm_bytes, dtype=np.int16)
        elif isinstance(frame_data, np.ndarray):
            if frame_data.dtype == np.int16:
                pcm_array = frame_data
                pcm_bytes = frame_data.tobytes()
            elif frame_data.dtype in (np.float32, np.float64):
                pcm_array = (np.clip(frame_data, -1.0, 1.0) * 32767.0).astype(np.int16)
                pcm_bytes = pcm_array.tobytes()
            else:
                pcm_array = frame_data.astype(np.int16)
                pcm_bytes = pcm_array.tobytes()
        else:
            raise ValueError(f"Unsupported frame data type: {type(frame_data)}")

        expected_samples = self.config.frame_samples
        if len(pcm_array) != expected_samples:
            raise ValueError(
                f"Expected {expected_samples} samples ({self.config.frame_duration_ms}ms), "
                f"but received {len(pcm_array)} samples."
            )

        # Gate 1: Energy floor check
        energy = self.compute_energy(pcm_array)
        if energy < self.config.energy_threshold:
            return False

        # Gate 2: WebRTC VAD analysis
        try:
            return self._vad.is_speech(pcm_bytes, self.config.sample_rate)
        except Exception as e:
            logger.warning(f"WebRTC VAD execution error: {e}")
            return False
