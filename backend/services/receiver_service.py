"""Receiver service handling message validation, deduplication, TTS routing, and playback."""

import collections
import logging
import queue
import threading
import time
from typing import Optional, Dict, Any, Callable, List
from pydantic import ValidationError

from backend.schemas.protocol import TranscriptMessage, AckMessage
from backend.services.tts_service import TTSService
from backend.services.audio_player import AudioPlayer
from backend.transport.base import BaseTransport

logger = logging.getLogger("itantra.receiver")


class ReceiverService:
    """
    Device B receiver service.
    Receives messages from Transport, validates them against protocol schema,
    deduplicates, orders, routes to TTSService, and queues audio for sequential playback.
    """

    def __init__(
        self,
        transport: BaseTransport,
        tts_service: Optional[TTSService] = None,
        audio_player: Optional[AudioPlayer] = None,
        max_seen_ids: int = 1000,
    ):
        self.transport = transport
        self.tts_service = tts_service or TTSService.get_instance()
        self.audio_player = audio_player or AudioPlayer()

        self._incoming_queue: queue.Queue = queue.Queue()
        self._stop_event = threading.Event()
        self._tts_worker_thread: Optional[threading.Thread] = None

        # Reliability & deduplication
        self._seen_message_ids: collections.deque = collections.deque(maxlen=max_seen_ids)
        self._seen_message_set: set = set()
        self._last_sequence_by_sender: Dict[str, int] = {}
        self._lock = threading.Lock()

        # Listeners / observers for UI / CLI metrics
        self.listeners: List[Callable[[Dict[str, Any]], None]] = []

        # Metrics
        self.received_count = 0
        self.duplicate_count = 0
        self.invalid_count = 0
        self.processed_count = 0
        self.tts_fail_count = 0
        self.last_latency_ms: Optional[float] = None

        # Attach to transport incoming dispatch
        self.transport.register_handler(self._on_transport_message)
        self._start_tts_worker()

    def add_listener(self, listener: Callable[[Dict[str, Any]], None]) -> None:
        """Register listener for receiver events (e.g. text_received, tts_done, playing)."""
        if listener not in self.listeners:
            self.listeners.append(listener)

    def _notify(self, event_type: str, data: Dict[str, Any]) -> None:
        payload = {"event": event_type, "timestamp": time.time(), **data}
        for listener in list(self.listeners):
            try:
                listener(payload)
            except Exception as e:
                logger.debug(f"Receiver listener notification error: {e}")

    def _on_transport_message(self, raw_dict: Dict[str, Any]) -> None:
        """Transport incoming message callback."""
        msg_type = raw_dict.get("type")

        if msg_type == "ack":
            logger.debug(f"Received ACK for message: {raw_dict.get('ack_message_id')}")
            return

        if msg_type == "transcript":
            self._handle_transcript_raw(raw_dict)
        else:
            logger.debug(f"Ignoring non-transcript message type: {msg_type}")

    def _handle_transcript_raw(self, raw_dict: Dict[str, Any]) -> None:
        """Validate and deduplicate incoming transcript message."""
        self.received_count += 1
        try:
            msg = TranscriptMessage(**raw_dict)
        except ValidationError as e:
            self.invalid_count += 1
            logger.warning(f"Rejected invalid TranscriptMessage: {e}")
            return
        except Exception as e:
            self.invalid_count += 1
            logger.warning(f"Error parsing TranscriptMessage: {e}")
            return

        msg_id = msg.header.message_id
        sender_id = msg.header.sender_id
        seq = msg.header.sequence

        with self._lock:
            # Deduplication
            if msg_id in self._seen_message_set:
                self.duplicate_count += 1
                logger.warning(f"Deduplicated message ID {msg_id} from {sender_id}")
                return

            if len(self._seen_message_ids) >= self._seen_message_ids.maxlen:
                oldest = self._seen_message_ids.popleft()
                self._seen_message_set.discard(oldest)

            self._seen_message_ids.append(msg_id)
            self._seen_message_set.add(msg_id)

            # Sequence check
            last_seq = self._last_sequence_by_sender.get(sender_id, 0)
            if seq <= last_seq:
                logger.warning(f"Out-of-order or repeated sequence {seq} <= {last_seq} from {sender_id}")
            self._last_sequence_by_sender[sender_id] = max(last_seq, seq)

        # Send ACK back through transport
        try:
            ack = AckMessage(
                ack_message_id=msg_id,
                sequence=seq,
                sender_id="receiver",
            )
            self.transport.send_message(ack)
        except Exception as e:
            logger.debug(f"Failed to send ACK: {e}")

        # Queue for TTS synthesis and playback
        self._notify("text_received", {
            "message_id": msg_id,
            "sender_id": sender_id,
            "text": msg.text,
            "language": msg.language,
            "sequence": seq,
            "sent_timestamp": msg.header.timestamp,
        })
        self._incoming_queue.put(msg)

    def _start_tts_worker(self) -> None:
        """Start worker thread to process queued transcripts into speech."""
        self._stop_event.clear()
        self._tts_worker_thread = threading.Thread(
            target=self._tts_worker_loop,
            name="ReceiverTTSWorker",
            daemon=True,
        )
        self._tts_worker_thread.start()

    def _tts_worker_loop(self) -> None:
        """Background loop taking validated transcript messages and calling TTS."""
        while not self._stop_event.is_set():
            try:
                msg: TranscriptMessage = self._incoming_queue.get(timeout=0.2)
            except queue.Empty:
                continue

            if msg is None:
                self._incoming_queue.task_done()
                break

            msg_id = msg.header.message_id
            text = msg.text
            lang = msg.language
            sent_time = msg.header.timestamp

            logger.info(f"Receiver TTS synthesis starting for: '{text}' ({lang})")
            self._notify("tts_start", {"message_id": msg_id, "text": text, "language": lang})

            tts_start = time.perf_counter()
            try:
                wav_bytes, tts_infer_sec, audio_dur_sec = self.tts_service.synthesize(
                    text=text,
                    language=lang,
                )
                self.processed_count += 1
                logger.info(f"TTS synthesis done in {tts_infer_sec:.2f}s ({audio_dur_sec:.2f}s audio).")

                self._notify("tts_done", {
                    "message_id": msg_id,
                    "infer_time_sec": tts_infer_sec,
                    "audio_duration_sec": audio_dur_sec,
                })

                # Callbacks for audio playback
                def _on_start():
                    play_start = time.time()
                    e2e_ms = (play_start - sent_time) * 1000.0
                    self.last_latency_ms = e2e_ms
                    logger.info(f"Audio playback started for message {msg_id}. Network-to-Speaker latency: {e2e_ms:.1f}ms")
                    self._notify("playback_start", {
                        "message_id": msg_id,
                        "e2e_network_to_speaker_ms": e2e_ms,
                    })

                def _on_finish():
                    logger.info(f"Audio playback finished for message {msg_id}")
                    self._notify("playback_finish", {"message_id": msg_id})

                # Queue in AudioPlayer for sequential playback
                self.audio_player.play(
                    wav_bytes=wav_bytes,
                    on_start=_on_start,
                    on_finish=_on_finish,
                )

            except Exception as e:
                self.tts_fail_count += 1
                logger.error(f"TTS synthesis failed for message {msg_id}: {e}")
                self._notify("tts_error", {"message_id": msg_id, "error": str(e)})
            finally:
                self._incoming_queue.task_done()

    def get_stats(self) -> Dict[str, Any]:
        """Return receiver statistics."""
        return {
            "received_count": self.received_count,
            "duplicate_count": self.duplicate_count,
            "invalid_count": self.invalid_count,
            "processed_count": self.processed_count,
            "tts_fail_count": self.tts_fail_count,
            "queue_size": self._incoming_queue.qsize(),
            "audio_player_queue": self.audio_player.queue_size(),
            "last_latency_ms": self.last_latency_ms,
        }

    def shutdown(self) -> None:
        """Stop worker and audio player."""
        self._stop_event.set()
        self._incoming_queue.put(None)
        if self._tts_worker_thread and self._tts_worker_thread.is_alive():
            self._tts_worker_thread.join(timeout=2.0)
        self.audio_player.shutdown()
