"""Live Microphone capture and real-time streaming pipeline service."""

import logging
import queue
import threading
import time
from typing import Callable, List, Optional
import numpy as np
import sounddevice as sd
import torch

from backend.config.vad_config import VADConfig, default_vad_config
from backend.schemas.events import TranscriptEvent
from backend.services.sentence_segmenter import SentenceSegmenter, SegmentedUtterance
from backend.services.stt_service import stt_service
from backend.services.language_service import language_service

logger = logging.getLogger("itantra.mic")


class LiveMicrophoneService:
    """
    Service managing live microphone audio capture and feeding chunks into the Phase 5 speech pipeline.
    Uses decoupled queues and isolated worker threads so STT inference (~800ms) never blocks
    the real-time audio callback or causes frame drops.
    """

    def __init__(self, default_config: Optional[VADConfig] = None):
        self.config = default_config or default_vad_config
        self.segmenter = SentenceSegmenter(self.config)

        # Threading and synchronization
        self._lock = threading.Lock()
        self.is_running = False
        self.is_paused = False
        self.language = "hi"
        self.device = None

        # Audio stream
        self.stream: Optional[sd.InputStream] = None

        # Decoupled Queues
        # 1. audio_queue: raw PCM chunks from audio callback -> pipeline worker
        self.audio_queue: queue.Queue = queue.Queue(maxsize=2000)
        # 2. stt_queue: finalized SegmentedUtterance -> STT worker
        self.stt_queue: queue.Queue = queue.Queue(maxsize=100)

        # Health and Diagnostics counters
        self.processed_frames_count = 0
        self.dropped_chunks_count = 0
        self.callback_overflow_count = 0
        self.total_pipeline_processing_time_sec = 0.0

        # Event management
        self.listeners: List[Callable[[TranscriptEvent], None]] = []
        self.collected_events: List[TranscriptEvent] = []
        self.sentence_counter = 0

        # Worker threads
        self.pipeline_thread: Optional[threading.Thread] = None
        self.stt_thread: Optional[threading.Thread] = None

    def add_listener(self, callback: Callable[[TranscriptEvent], None]) -> None:
        """Register a callback listener for finalized transcript events."""
        with self._lock:
            if callback not in self.listeners:
                self.listeners.append(callback)

    def remove_listener(self, callback: Callable[[TranscriptEvent], None]) -> None:
        """Unregister a callback listener."""
        with self._lock:
            if callback in self.listeners:
                self.listeners.remove(callback)

    def is_active(self) -> bool:
        """Check if microphone capture session is currently active."""
        return self.is_running

    def get_stream_diagnostics(self) -> dict:
        """Return frame processing and drop diagnostics for the active/completed session."""
        avg_latency_ms = 0.0
        if self.processed_frames_count > 0:
            avg_latency_ms = (self.total_pipeline_processing_time_sec / self.processed_frames_count) * 1000.0

        return {
            "processed_frames": self.processed_frames_count,
            "dropped_chunks": self.dropped_chunks_count,
            "callback_overflows": self.callback_overflow_count,
            "avg_frame_processing_latency_ms": round(avg_latency_ms, 4),
        }

    def _audio_callback(self, indata, frames, time_info, status):
        """
        High-priority audio capture callback.
        Executes in < 0.05ms. Strictly non-blocking: only copies raw PCM bytes to queue.
        Tracks any buffer overruns or dropped chunks.
        """
        if status:
            self.callback_overflow_count += 1
            logger.warning(f"Microphone audio callback buffer status: {status} (Total events: {self.callback_overflow_count})")

        if not self.is_running or self.is_paused:
            return

        # Ensure single-channel 16-bit PCM bytes
        if indata.ndim > 1:
            channel_data = indata[:, 0].copy()
        else:
            channel_data = indata.copy()

        # indata is int16 if stream dtype='int16', otherwise convert
        if channel_data.dtype != np.int16:
            pcm_data = (np.clip(channel_data, -1.0, 1.0) * 32767.0).astype(np.int16)
        else:
            pcm_data = channel_data

        try:
            self.audio_queue.put_nowait(pcm_data.tobytes())
        except queue.Full:
            self.dropped_chunks_count += 1
            logger.error(
                f"Audio queue overflow! Dropped microphone chunk (Total dropped chunks: {self.dropped_chunks_count})"
            )

    def _pipeline_worker(self):
        """
        Pipeline worker thread:
        Pulls raw audio chunk bytes, slices into exact 30ms frames,
        and feeds into the Dual-Gate VAD + sentence segmenter.
        Measures total frame processing latency and dispatches finalized utterances.
        """
        partial_buffer = bytearray()
        frame_bytes_len = self.config.frame_bytes

        while self.is_running:
            try:
                chunk = self.audio_queue.get(timeout=0.05)
            except queue.Empty:
                continue

            partial_buffer.extend(chunk)

            while len(partial_buffer) >= frame_bytes_len:
                frame_raw = bytes(partial_buffer[:frame_bytes_len])
                del partial_buffer[:frame_bytes_len]

                frame_array = np.frombuffer(frame_raw, dtype=np.int16)

                t_frame_start = time.perf_counter()
                utterance: Optional[SegmentedUtterance] = self.segmenter.process_frame(frame_array)
                self.total_pipeline_processing_time_sec += (time.perf_counter() - t_frame_start)
                self.processed_frames_count += 1

                if utterance is not None:
                    # Non-blocking handoff to STT worker thread!
                    self.stt_queue.put(utterance)

            self.audio_queue.task_done()

        # Drain any leftover complete frames on shutdown
        while len(partial_buffer) >= frame_bytes_len:
            frame_raw = bytes(partial_buffer[:frame_bytes_len])
            del partial_buffer[:frame_bytes_len]
            frame_array = np.frombuffer(frame_raw, dtype=np.int16)

            t_frame_start = time.perf_counter()
            utterance = self.segmenter.process_frame(frame_array)
            self.total_pipeline_processing_time_sec += (time.perf_counter() - t_frame_start)
            self.processed_frames_count += 1

            if utterance is not None:
                self.stt_queue.put(utterance)

    def _stt_worker(self):
        """
        STT worker thread:
        Pulls finalized speech utterances, executes IndicConformer STT,
        and dispatches TranscriptEvents to subscribers.
        """
        while self.is_running or not self.stt_queue.empty():
            try:
                utterance = self.stt_queue.get(timeout=0.05)
            except queue.Empty:
                continue

            self._process_and_emit(utterance)
            self.stt_queue.task_done()

    def _process_and_emit(self, utterance: SegmentedUtterance) -> Optional[TranscriptEvent]:
        """Transcribe utterance and notify all registered listeners."""
        with self._lock:
            self.sentence_counter += 1
            idx = self.sentence_counter

        wav_tensor = torch.tensor(utterance.pcm_audio, dtype=torch.float32).unsqueeze(0)
        stt_start = time.perf_counter()

        try:
            transcript, inference_time_sec = stt_service.transcribe(
                wav_tensor=wav_tensor,
                language=self.language,
                decoding="ctc",
            )
        except Exception as e:
            logger.error(f"STT transcription failed during live microphone streaming: {e}")
            transcript = ""
            inference_time_sec = 0.0

        total_latency_ms = (time.perf_counter() - stt_start) * 1000 + utterance.silence_duration_ms

        event = TranscriptEvent(
            type="transcript",
            language=self.language,
            text=transcript,
            sentence_index=idx,
            speech_duration_ms=utterance.speech_duration_ms,
            silence_duration_ms=utterance.silence_duration_ms,
            stt_latency_ms=round(inference_time_sec * 1000, 2),
            total_latency_ms=round(total_latency_ms, 2),
            timestamp=time.time(),
        )

        with self._lock:
            self.collected_events.append(event)
            listeners_snapshot = list(self.listeners)

        for callback in listeners_snapshot:
            try:
                callback(event)
            except Exception as e:
                logger.error(f"Error in transcript event callback: {e}")

        return event

    def start(
        self,
        language: str = "hi",
        vad_config: Optional[VADConfig] = None,
        device: Optional[int] = None,
        on_transcript: Optional[Callable[[TranscriptEvent], None]] = None,
    ) -> None:
        """
        Start live microphone capture session.

        Args:
            language: Target language code (e.g. 'hi', 'gu', 'ta').
            vad_config: Optional custom VAD & pause detection settings.
            device: Optional audio input device index or name.
            on_transcript: Optional callback function receiving TranscriptEvent.
        """
        with self._lock:
            if self.is_running:
                logger.warning("Microphone session already running.")
                return

            # Validate language with existing language service
            self.language = language_service.validate_for_stt(language)
            self.config = vad_config or self.config
            self.segmenter = SentenceSegmenter(self.config)
            self.device = device

            # Reset state
            self.sentence_counter = 0
            self.collected_events.clear()
            self.is_paused = False
            self.is_running = True
            self.processed_frames_count = 0
            self.dropped_chunks_count = 0
            self.callback_overflow_count = 0
            self.total_pipeline_processing_time_sec = 0.0

            # Clear queues
            while not self.audio_queue.empty():
                try:
                    self.audio_queue.get_nowait()
                except queue.Empty:
                    break

            while not self.stt_queue.empty():
                try:
                    self.stt_queue.get_nowait()
                except queue.Empty:
                    break

            if on_transcript and on_transcript not in self.listeners:
                self.listeners.append(on_transcript)

            # Start worker threads
            self.pipeline_thread = threading.Thread(
                target=self._pipeline_worker,
                name="iTantra-PipelineWorker",
                daemon=True,
            )
            self.stt_thread = threading.Thread(
                target=self._stt_worker,
                name="iTantra-STTWorker",
                daemon=True,
            )
            self.pipeline_thread.start()
            self.stt_thread.start()

            # Start microphone audio stream
            try:
                self.stream = sd.InputStream(
                    samplerate=self.config.sample_rate,
                    channels=1,
                    dtype="int16",
                    blocksize=self.config.frame_samples,  # 480 samples = 30ms
                    device=self.device,
                    callback=self._audio_callback,
                )
                self.stream.start()
                logger.info(
                    f"Microphone capture started (Device: {device}, SR: {self.config.sample_rate} Hz, "
                    f"Frame: {self.config.frame_duration_ms} ms, Lang: {self.language})"
                )
            except Exception as e:
                self.is_running = False
                logger.error(f"Failed to open microphone input stream: {e}")
                raise RuntimeError(f"Microphone initialization failed: {e}")

    def pause(self) -> None:
        """Temporarily pause audio processing (e.g. muted mic)."""
        self.is_paused = True
        logger.info("Microphone audio stream paused.")

    def resume(self) -> None:
        """Resume audio processing."""
        self.is_paused = False
        logger.info("Microphone audio stream resumed.")

    def stop(self) -> List[TranscriptEvent]:
        """
        Stop microphone capture, flush pending speech through STT,
        and terminate worker threads cleanly.

        Returns:
            List[TranscriptEvent]: All transcripts produced during the session.
        """
        with self._lock:
            if not self.is_running:
                return list(self.collected_events)

            logger.info("Stopping microphone capture...")

            # 1. Stop audio input stream first so no new chunks arrive
            if self.stream is not None:
                try:
                    self.stream.stop()
                    self.stream.close()
                except Exception as e:
                    logger.warning(f"Error closing audio stream: {e}")
                self.stream = None

            # 2. Flush any pending buffered speech in segmenter
            flushed_utterance = self.segmenter.flush()
            if flushed_utterance is not None:
                self.stt_queue.put(flushed_utterance)

            # 3. Wait for audio_queue to be consumed by pipeline worker
            self.audio_queue.join()

            # 4. Wait for stt_queue to finish transcribing
            self.stt_queue.join()

            # 5. Signal workers to stop
            self.is_running = False

        # Join worker threads outside lock
        if self.pipeline_thread and self.pipeline_thread.is_alive():
            self.pipeline_thread.join(timeout=2.0)
        if self.stt_thread and self.stt_thread.is_alive():
            self.stt_thread.join(timeout=5.0)

        logger.info(f"Microphone session ended. Total sentences: {len(self.collected_events)}")
        return list(self.collected_events)


microphone_service = LiveMicrophoneService()
