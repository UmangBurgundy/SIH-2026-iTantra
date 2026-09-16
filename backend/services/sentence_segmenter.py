"""Sentence and utterance segmentation state machine based on VAD and pause detection."""

import logging
from collections import deque
from typing import List, Optional, NamedTuple
import numpy as np

from backend.config.vad_config import VADConfig, default_vad_config
from backend.services.vad_service import VADService

logger = logging.getLogger("itanta.segmenter")


class SegmentedUtterance(NamedTuple):
    """Container for a finalized speech utterance."""
    pcm_audio: np.ndarray          # 1D float32 normalized audio at 16 kHz
    start_ms: int                  # Start time in the session in ms
    end_ms: int                    # End of speech in the session in ms
    speech_duration_ms: int        # Active speech duration in ms
    silence_duration_ms: int       # Terminal silence duration in ms that closed the utterance


class SentenceSegmenter:
    """
    Streaming sentence/utterance segmenter.
    Ingests 30ms audio frames, detects short pauses vs long stoppages,
    and yields complete, finalized utterances.
    """

    def __init__(self, config: VADConfig = default_vad_config):
        self.config = config
        self.vad = VADService(config)

        # Pre-roll ring buffer to capture speech onset consonants
        self.pre_roll_chunks = max(1, int(self.config.speech_pad_ms / self.config.frame_duration_ms))
        self.pre_roll_buffer = deque(maxlen=self.pre_roll_chunks)

        # Internal state
        self.state: str = "IDLE"  # "IDLE", "SPEECH", "PAUSED"
        self.speech_buffer: List[np.ndarray] = []
        self.current_session_time_ms: int = 0
        self.speech_start_time_ms: int = 0
        self.last_speech_time_ms: int = 0
        self.silence_duration_ms: int = 0

    def reset(self) -> None:
        """Reset segmenter state for a new audio stream or session."""
        self.state = "IDLE"
        self.speech_buffer.clear()
        self.pre_roll_buffer.clear()
        self.current_session_time_ms = 0
        self.speech_start_time_ms = 0
        self.last_speech_time_ms = 0
        self.silence_duration_ms = 0

    def process_frame(self, frame: np.ndarray) -> Optional[SegmentedUtterance]:
        """
        Process a single frame of audio (e.g. 30ms = 480 samples @ 16kHz).
        Returns a SegmentedUtterance if a sentence boundary was reached, otherwise None.
        """
        frame_ms = self.config.frame_duration_ms
        is_speech = self.vad.is_speech_frame(frame)
        self.current_session_time_ms += frame_ms

        finalized_utterance: Optional[SegmentedUtterance] = None

        if is_speech:
            if self.state == "IDLE":
                # Speech onset detected
                self.state = "SPEECH"
                self.speech_start_time_ms = max(
                    0,
                    self.current_session_time_ms - frame_ms - (len(self.pre_roll_buffer) * frame_ms)
                )
                # Prepend pre-roll buffer to preserve initial consonants
                self.speech_buffer.extend(list(self.pre_roll_buffer))
                self.pre_roll_buffer.clear()
            elif self.state == "PAUSED":
                # Speech resumed from short pause
                self.state = "SPEECH"

            # Append current speech frame
            self.speech_buffer.append(frame.copy())
            self.last_speech_time_ms = self.current_session_time_ms
            self.silence_duration_ms = 0

            # Check if utterance exceeds maximum allowed duration
            total_duration_ms = len(self.speech_buffer) * frame_ms
            if total_duration_ms >= self.config.max_utterance_duration_ms:
                finalized_utterance = self._finalize_current_buffer(terminal_silence_ms=0)

        else:
            # Silence frame
            if self.state == "IDLE":
                # User has not started speaking; maintain pre-roll
                self.pre_roll_buffer.append(frame.copy())

            elif self.state in ("SPEECH", "PAUSED"):
                self.state = "PAUSED"
                self.speech_buffer.append(frame.copy())
                self.silence_duration_ms += frame_ms

                # Check if silence reached the sentence finalization stoppage threshold
                if self.silence_duration_ms >= self.config.silence_threshold_ms:
                    finalized_utterance = self._finalize_current_buffer(
                        terminal_silence_ms=self.silence_duration_ms
                    )

        return finalized_utterance

    def _finalize_current_buffer(self, terminal_silence_ms: int) -> Optional[SegmentedUtterance]:
        """Finalize speech buffer into a SegmentedUtterance if speech criteria are met."""
        if not self.speech_buffer:
            self.state = "IDLE"
            self.silence_duration_ms = 0
            return None

        frame_ms = self.config.frame_duration_ms
        trailing_silence_frames = int(terminal_silence_ms / frame_ms)

        # Slice out excessive trailing silence frames, keeping small post-roll
        post_roll_frames = max(1, int(self.config.speech_pad_ms / frame_ms))
        remove_frames = max(0, trailing_silence_frames - post_roll_frames)

        if remove_frames > 0 and remove_frames < len(self.speech_buffer):
            trimmed_buffer = self.speech_buffer[:-remove_frames]
        else:
            trimmed_buffer = self.speech_buffer

        utterance_samples = np.concatenate(trimmed_buffer)
        speech_duration_ms = int(len(utterance_samples) * 1000 / self.config.sample_rate)

        result: Optional[SegmentedUtterance] = None

        if speech_duration_ms >= self.config.min_speech_duration_ms:
            # Convert to float32 normalized [-1.0, 1.0] if not already
            if utterance_samples.dtype == np.int16:
                float_audio = utterance_samples.astype(np.float32) / 32768.0
            else:
                float_audio = utterance_samples.astype(np.float32)

            result = SegmentedUtterance(
                pcm_audio=float_audio,
                start_ms=self.speech_start_time_ms,
                end_ms=self.last_speech_time_ms,
                speech_duration_ms=speech_duration_ms,
                silence_duration_ms=terminal_silence_ms,
            )

        # Reset state back to IDLE
        self.state = "IDLE"
        self.speech_buffer.clear()
        self.pre_roll_buffer.clear()
        self.silence_duration_ms = 0

        return result

    def flush(self) -> Optional[SegmentedUtterance]:
        """Flush any pending buffered speech at stream completion or PTT release."""
        if self.state in ("SPEECH", "PAUSED") and self.speech_buffer:
            return self._finalize_current_buffer(terminal_silence_ms=self.silence_duration_ms)
        return None
