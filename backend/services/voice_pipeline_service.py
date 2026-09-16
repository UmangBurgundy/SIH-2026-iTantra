"""Streaming-ready voice processing pipeline integrating VAD, segmentation, and STT."""

import logging
import time
from typing import List, Optional
import numpy as np
import torch

from backend.config.vad_config import VADConfig, default_vad_config
from backend.schemas.voice import FinalizedSentence, VoiceProcessResponse
from backend.services.sentence_segmenter import SentenceSegmenter, SegmentedUtterance
from backend.services.stt_service import stt_service
from backend.services.language_service import language_service
from backend.utils.audio import load_audio_bytes

logger = logging.getLogger("itanta.pipeline")


class VoicePipelineSession:
    """
    Streaming session manager.
    Can ingest small microphone PCM chunks continuously from an Android/client app,
    detect pause/sentence boundaries, and return transcripts.
    """

    def __init__(self, language: str = "hi", config: Optional[VADConfig] = None):
        self.language = language_service.validate_for_stt(language)
        self.config = config or default_vad_config
        self.segmenter = SentenceSegmenter(self.config)
        self.partial_byte_buffer = bytearray()
        self.sentence_counter = 0

        # Latency accumulators
        self.total_vad_time_sec = 0.0
        self.total_stt_time_sec = 0.0

    def reset_session(self) -> None:
        """Reset the session buffers and state."""
        self.segmenter.reset()
        self.partial_byte_buffer.clear()
        self.sentence_counter = 0
        self.total_vad_time_sec = 0.0
        self.total_stt_time_sec = 0.0

    def process_chunk(self, chunk_bytes: bytes) -> List[FinalizedSentence]:
        """
        Ingest an arbitrary-sized chunk of 16-bit 16kHz PCM audio bytes.
        Slices into frame_samples frames, feeds to segmenter, and triggers STT on sentence completion.
        """
        if not chunk_bytes:
            return []

        self.partial_byte_buffer.extend(chunk_bytes)
        frame_bytes_len = self.config.frame_bytes
        finalized_sentences: List[FinalizedSentence] = []

        while len(self.partial_byte_buffer) >= frame_bytes_len:
            frame_raw = bytes(self.partial_byte_buffer[:frame_bytes_len])
            del self.partial_byte_buffer[:frame_bytes_len]

            # Convert to int16 numpy array
            frame_array = np.frombuffer(frame_raw, dtype=np.int16)

            vad_start = time.perf_counter()
            utterance: Optional[SegmentedUtterance] = self.segmenter.process_frame(frame_array)
            self.total_vad_time_sec += (time.perf_counter() - vad_start)

            if utterance is not None:
                sentence_record = self._transcribe_utterance(utterance)
                if sentence_record:
                    finalized_sentences.append(sentence_record)

        return finalized_sentences

    def finalize_session(self) -> List[FinalizedSentence]:
        """
        Flush any remaining speech when a stream concludes or PTT is released.
        """
        finalized_sentences: List[FinalizedSentence] = []

        # Process any remaining full frame in partial buffer
        frame_bytes_len = self.config.frame_bytes
        if len(self.partial_byte_buffer) >= frame_bytes_len:
            frame_raw = bytes(self.partial_byte_buffer[:frame_bytes_len])
            frame_array = np.frombuffer(frame_raw, dtype=np.int16)
            utterance = self.segmenter.process_frame(frame_array)
            if utterance is not None:
                sent = self._transcribe_utterance(utterance)
                if sent:
                    finalized_sentences.append(sent)

        # Flush segmenter buffer
        utterance = self.segmenter.flush()
        if utterance is not None:
            sent = self._transcribe_utterance(utterance)
            if sent:
                finalized_sentences.append(sent)

        self.partial_byte_buffer.clear()
        return finalized_sentences

    def _transcribe_utterance(self, utterance: SegmentedUtterance) -> Optional[FinalizedSentence]:
        """Convert segmented audio array to tensor and run IndicConformer STT."""
        self.sentence_counter += 1
        wav_tensor = torch.tensor(utterance.pcm_audio, dtype=torch.float32).unsqueeze(0)

        stt_start = time.perf_counter()
        transcript, inference_time_sec = stt_service.transcribe(
            wav_tensor=wav_tensor,
            language=self.language,
            decoding="ctc",
        )
        self.total_stt_time_sec += inference_time_sec
        total_time_ms = (time.perf_counter() - stt_start) * 1000

        return FinalizedSentence(
            sentence_index=self.sentence_counter,
            transcript=transcript,
            speech_start_ms=utterance.start_ms,
            speech_end_ms=utterance.end_ms,
            speech_duration_ms=utterance.speech_duration_ms,
            silence_duration_ms=utterance.silence_duration_ms,
            stt_latency_ms=round(inference_time_sec * 1000, 2),
            total_processing_latency_ms=round(total_time_ms, 2),
        )


class VoicePipelineService:
    """High-level service managing voice pipeline execution for requests and sessions."""

    def create_session(
        self,
        language: str = "hi",
        vad_config: Optional[VADConfig] = None
    ) -> VoicePipelineSession:
        """Create a new streaming session."""
        return VoicePipelineSession(language=language, config=vad_config)

    def process_audio(
        self,
        audio_content: bytes,
        language: str = "hi",
        vad_config: Optional[VADConfig] = None,
    ) -> VoiceProcessResponse:
        """
        Process recorded audio file bytes through the real-time VAD & segmentation pipeline.

        - Converts uploaded audio into 16kHz 16-bit PCM.
        - Streams frame-by-frame through VoicePipelineSession.
        - Detects speech, short pauses, long stoppages, and sentence boundaries.
        - Returns structured sentences with granular latency benchmarks.
        """
        overall_start = time.perf_counter()
        config = vad_config or default_vad_config
        session = self.create_session(language=language, vad_config=config)

        # Preprocess input audio to 16kHz mono tensor using audio utils
        wav_tensor, duration_sec = load_audio_bytes(audio_content, target_sr=config.sample_rate)
        audio_samples = wav_tensor.squeeze().numpy()

        # Convert float32 [-1.0, 1.0] to 16-bit PCM bytes
        pcm_int16 = (np.clip(audio_samples, -1.0, 1.0) * 32767.0).astype(np.int16)
        raw_pcm_bytes = pcm_int16.tobytes()

        # Stream through session in simulated microphone chunks (e.g. 100ms chunks)
        chunk_size_bytes = int(config.sample_rate * 0.1 * 2)  # 100ms
        all_sentences: List[FinalizedSentence] = []

        for offset in range(0, len(raw_pcm_bytes), chunk_size_bytes):
            chunk = raw_pcm_bytes[offset : offset + chunk_size_bytes]
            sentences = session.process_chunk(chunk)
            all_sentences.extend(sentences)

        # Flush any speech left at the end of recording
        flushed = session.finalize_session()
        all_sentences.extend(flushed)

        total_proc_time_ms = (time.perf_counter() - overall_start) * 1000
        total_audio_ms = int(duration_sec * 1000)

        status = "completed" if all_sentences else "no_speech_detected"

        # Calculate user-perceived latency (silence stoppage wait + STT latency)
        user_perceived_ms = None
        if all_sentences:
            last_sent = all_sentences[-1]
            user_perceived_ms = round(last_sent.silence_duration_ms + last_sent.stt_latency_ms, 2)

        return VoiceProcessResponse(
            language=session.language,
            status=status,
            sentences=all_sentences,
            total_audio_duration_ms=total_audio_ms,
            vad_latency_ms=round(session.total_vad_time_sec * 1000, 2),
            total_processing_latency_ms=round(total_proc_time_ms, 2),
            user_perceived_latency_ms=user_perceived_ms,
        )


voice_pipeline_service = VoicePipelineService()
