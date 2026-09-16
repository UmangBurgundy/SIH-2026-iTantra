"""Audio preprocessing and encoding utilities for iTantra backend."""

import io
from typing import Tuple
import numpy as np
import soundfile as sf
import torch
from scipy import signal

from backend.config.settings import settings


class AudioProcessingError(Exception):
    """Exception raised when audio cannot be decoded or processed."""
    pass


def load_audio_bytes(file_bytes: bytes, target_sr: int = 16000) -> Tuple[torch.Tensor, float]:
    """
    Decode raw audio bytes, convert to mono, resample to target_sr if needed,
    and return as float32 torch.Tensor of shape (1, num_samples) along with duration in seconds.
    """
    if not file_bytes:
        raise AudioProcessingError("Audio data is empty")

    if len(file_bytes) > settings.MAX_AUDIO_SIZE_BYTES:
        raise AudioProcessingError(
            f"Audio upload exceeds maximum size limit of {settings.MAX_AUDIO_SIZE_BYTES // (1024 * 1024)} MB"
        )

    try:
        buffer = io.BytesIO(file_bytes)
        audio_data, sr = sf.read(buffer, dtype="float32")
    except Exception as e:
        raise AudioProcessingError(f"Failed to decode audio file. Unsupported or corrupted audio format: {str(e)}")

    if audio_data is None or len(audio_data) == 0:
        raise AudioProcessingError("Decoded audio contains zero samples")

    # Multi-channel to mono
    if audio_data.ndim > 1:
        audio_data = audio_data.mean(axis=1)

    # Resample to target_sr if necessary
    if sr != target_sr:
        gcd = np.gcd(sr, target_sr)
        up = target_sr // gcd
        down = sr // gcd
        audio_data = signal.resample_poly(audio_data, up, down).astype(np.float32)
        sr = target_sr

    duration_sec = len(audio_data) / sr

    if duration_sec < settings.MIN_AUDIO_DURATION_SEC:
        raise AudioProcessingError(
            f"Audio duration ({duration_sec:.2f}s) is too short. Minimum duration is {settings.MIN_AUDIO_DURATION_SEC}s"
        )

    if duration_sec > settings.MAX_AUDIO_DURATION_SEC:
        raise AudioProcessingError(
            f"Audio duration ({duration_sec:.2f}s) exceeds maximum allowed {settings.MAX_AUDIO_DURATION_SEC}s"
        )

    # Convert to torch tensor with batch/channel dimension (1, N)
    wav_tensor = torch.tensor(audio_data, dtype=torch.float32).unsqueeze(0)

    return wav_tensor, duration_sec


def array_to_wav_bytes(audio_array: np.ndarray, sample_rate: int = 24000) -> bytes:
    """
    Convert a numpy audio waveform array to standard WAV audio bytes.
    Ensures safe normalization and conversion to 16-bit PCM WAV.
    """
    if audio_array.ndim > 1:
        audio_array = audio_array.squeeze()

    # Normalize float32 audio to [-1.0, 1.0] if needed
    if audio_array.dtype != np.int16:
        max_val = np.max(np.abs(audio_array))
        if max_val > 1.0:
            audio_array = audio_array / max_val
        # Convert to 16-bit PCM for universal audio player compatibility
        audio_int16 = (audio_array * 32767.0).astype(np.int16)
    else:
        audio_int16 = audio_array

    buffer = io.BytesIO()
    sf.write(buffer, audio_int16, sample_rate, format="WAV", subtype="PCM_16")
    buffer.seek(0)
    return buffer.read()
