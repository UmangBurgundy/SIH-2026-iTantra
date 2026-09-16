"""Audio Player service for sequential voice playback on local speaker."""

import io
import logging
import queue
import threading
import time
from typing import Optional, Callable
import soundfile as sf
import sounddevice as sd

logger = logging.getLogger("itantra.audio_player")


class AudioPlayer:
    """
    Dedicated audio playback service with sequential queuing.
    Ensures that incoming TTS synthesized audio messages are played sequentially without overlapping.
    Operates in a background daemon thread so network receiving and TTS workers are never blocked.
    """

    def __init__(self):
        self._queue: queue.Queue = queue.Queue()
        self._stop_event = threading.Event()
        self._worker_thread: Optional[threading.Thread] = None
        self._is_playing_flag = threading.Event()
        self.played_count = 0
        self.failed_count = 0
        self._start_worker()

    def _start_worker(self) -> None:
        """Start background worker thread."""
        self._stop_event.clear()
        self._worker_thread = threading.Thread(
            target=self._playback_loop,
            name="AudioPlayerWorker",
            daemon=True,
        )
        self._worker_thread.start()

    def _playback_loop(self) -> None:
        """Loop reading audio items from queue and playing them sequentially."""
        while not self._stop_event.is_set():
            try:
                item = self._queue.get(timeout=0.2)
            except queue.Empty:
                continue

            if item is None:
                # Poison pill
                self._queue.task_done()
                break

            wav_bytes, on_start, on_finish = item
            self._is_playing_flag.set()

            try:
                if on_start:
                    try:
                        on_start()
                    except Exception as e:
                        logger.warning(f"AudioPlayer on_start callback error: {e}")

                # Read wav bytes
                data, sample_rate = sf.read(io.BytesIO(wav_bytes))

                # Play synchronously on this background thread
                sd.play(data, sample_rate)
                sd.wait()

                self.played_count += 1
                logger.info(f"Finished playing audio message ({len(wav_bytes)} bytes, sr={sample_rate}).")

                if on_finish:
                    try:
                        on_finish()
                    except Exception as e:
                        logger.warning(f"AudioPlayer on_finish callback error: {e}")

            except Exception as e:
                self.failed_count += 1
                logger.error(f"Error during audio playback: {e}")
                if on_finish:
                    try:
                        on_finish()
                    except Exception:
                        pass
            finally:
                self._is_playing_flag.clear()
                self._queue.task_done()

    def play(
        self,
        wav_bytes: bytes,
        on_start: Optional[Callable[[], None]] = None,
        on_finish: Optional[Callable[[], None]] = None,
    ) -> None:
        """Queue a WAV audio payload for playback."""
        if not wav_bytes:
            logger.warning("Empty audio bytes provided to AudioPlayer.play, ignoring.")
            return
        self._queue.put((wav_bytes, on_start, on_finish))

    def stop_current(self) -> None:
        """Stop currently playing audio immediately."""
        try:
            sd.stop()
        except Exception as e:
            logger.debug(f"sd.stop error: {e}")
        self._is_playing_flag.clear()

    def clear_queue(self) -> int:
        """Clear all pending audio messages in queue."""
        cleared = 0
        while not self._queue.empty():
            try:
                self._queue.get_nowait()
                self._queue.task_done()
                cleared += 1
            except queue.Empty:
                break
        return cleared

    def is_playing(self) -> bool:
        """Check if audio is currently playing."""
        return self._is_playing_flag.is_set()

    def queue_size(self) -> int:
        """Get number of pending audio items in queue."""
        return self._queue.qsize()

    def shutdown(self) -> None:
        """Stop worker thread and wait for completion."""
        self._stop_event.set()
        self.stop_current()
        self._queue.put(None)
        if self._worker_thread and self._worker_thread.is_alive():
            self._worker_thread.join(timeout=2.0)
