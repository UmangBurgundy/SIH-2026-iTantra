"""Configuration for Voice Activity Detection (VAD) and Sentence Segmentation."""

from pydantic import BaseModel, Field


class VADConfig(BaseModel):
    """Configurable parameters for VAD, pause detection, and utterance segmentation."""

    sample_rate: int = Field(
        default=16000,
        description="Audio sample rate in Hz. Must be 8000, 16000, 32000, or 48000 for WebRTC VAD."
    )
    frame_duration_ms: int = Field(
        default=30,
        description="Duration of each audio analysis frame in milliseconds (10, 20, or 30 ms)."
    )
    vad_mode: int = Field(
        default=2,
        ge=0,
        le=3,
        description="WebRTC VAD aggressiveness mode: 0 (least aggressive) to 3 (most aggressive)."
    )
    energy_threshold: float = Field(
        default=0.008,
        ge=0.0,
        description="Minimum RMS energy floor below which frames are treated as silence."
    )
    speech_pad_ms: int = Field(
        default=240,
        ge=0,
        description="Duration in ms to include before speech onset (pre-roll) and after speech offset."
    )
    short_pause_threshold_ms: int = Field(
        default=400,
        ge=50,
        description="Duration in ms of silence tolerated as a short intra-sentence pause without splitting."
    )
    silence_threshold_ms: int = Field(
        default=800,
        ge=100,
        description="Continuous silence in ms required to trigger sentence/utterance completion."
    )
    min_speech_duration_ms: int = Field(
        default=400,
        ge=100,
        description="Minimum speech duration in ms to qualify as a valid utterance (rejects noise bursts)."
    )
    max_utterance_duration_ms: int = Field(
        default=15000,
        ge=1000,
        description="Maximum duration in ms of a single utterance before forcing sentence finalization."
    )

    @property
    def frame_samples(self) -> int:
        """Calculate number of samples in one frame."""
        return int(self.sample_rate * self.frame_duration_ms / 1000)

    @property
    def frame_bytes(self) -> int:
        """Calculate byte size of one 16-bit PCM frame."""
        return self.frame_samples * 2


default_vad_config = VADConfig()
