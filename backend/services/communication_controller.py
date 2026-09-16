"""High-level Communication Controller orchestrating PTT, Continuous mode, STT transmit, and TTS playback."""

import enum
import logging
import threading
import time
from typing import Optional, Callable, Dict, Any, List, Union
import uuid

from backend.schemas.events import TranscriptEvent
from backend.schemas.protocol import TranscriptMessage, MessageHeader
from backend.services.microphone_service import LiveMicrophoneService
from backend.services.receiver_service import ReceiverService
from backend.transport.base import BaseTransport

logger = logging.getLogger("itantra.controller")


class CommunicationMode(str, enum.Enum):
    PTT = "ptt"
    CONTINUOUS = "continuous"


class CommunicationState(str, enum.Enum):
    IDLE = "IDLE"
    RECORDING = "RECORDING"
    PROCESSING = "PROCESSING"
    TRANSMITTING = "TRANSMITTING"
    RECEIVING = "RECEIVING"
    PLAYING = "PLAYING"


class CommunicationController:
    """
    Main communication controller coordinating:
    1. Local audio capture (LiveMicrophoneService)
    2. STT transcript generation
    3. Network transport transmission (WiFiTransport or BluetoothTransport)
    4. Remote peer message reception and TTS synthesis (ReceiverService)
    5. Push-to-Talk (walkie-talkie) and Continuous (phone-like) operational modes
    """

    def __init__(
        self,
        device_id: str = "device-a",
        session_id: Optional[str] = None,
        language: str = "hi",
        mode: CommunicationMode = CommunicationMode.PTT,
        transport: Optional[BaseTransport] = None,
        mic_service: Optional[LiveMicrophoneService] = None,
        receiver_service: Optional[ReceiverService] = None,
    ):
        self.device_id = device_id
        self.session_id = session_id or f"session-{str(uuid.uuid4())[:8]}"
        self.language = language
        self.mode = mode
        self.state = CommunicationState.IDLE

        self.transport = transport
        self.mic_service = mic_service or LiveMicrophoneService()
        self.receiver_service = receiver_service or (ReceiverService(transport=self.transport) if self.transport else None)

        self._lock = threading.Lock()
        self._sequence_counter = 0
        self.listeners: List[Callable[[Dict[str, Any]], None]] = []
        self._is_active = False

        # Latency tracking
        self.latest_transmission_metrics: Dict[str, Any] = {}

        # Wire up listeners
        self.mic_service.add_listener(self._on_local_transcript)
        if self.receiver_service:
            self.receiver_service.add_listener(self._on_receiver_event)

    def add_listener(self, listener: Callable[[Dict[str, Any]], None]) -> None:
        """Register state change and communication event listener."""
        if listener not in self.listeners:
            self.listeners.append(listener)

    def _set_state(self, new_state: CommunicationState, details: Optional[Dict[str, Any]] = None) -> None:
        """Transition state machine and notify listeners."""
        with self._lock:
            old_state = self.state
            self.state = new_state
        logger.info(f"State transition: {old_state} -> {new_state} (Mode: {self.mode})")
        payload = {
            "event": "state_changed",
            "old_state": old_state.value,
            "new_state": new_state.value,
            "mode": self.mode.value,
            "timestamp": time.time(),
            **(details or {}),
        }
        for listener in list(self.listeners):
            try:
                listener(payload)
            except Exception as e:
                logger.debug(f"Listener error on state transition: {e}")

    def _on_local_transcript(self, event: TranscriptEvent) -> None:
        """
        Callback fired when local speech pipeline finalizes an utterance and runs STT.
        Packages the transcript into a protocol TranscriptMessage and transmits over Transport.
        """
        if not event.text or not event.text.strip():
            logger.info("Empty transcript produced by STT; skipping transmission.")
            if self.mode == CommunicationMode.CONTINUOUS and self._is_active:
                self._set_state(CommunicationState.RECORDING)
            else:
                self._set_state(CommunicationState.IDLE)
            return

        with self._lock:
            self._sequence_counter += 1
            seq = self._sequence_counter

        self._set_state(CommunicationState.TRANSMITTING, {"text": event.text, "seq": seq})

        # Form protocol message
        msg = TranscriptMessage(
            header=MessageHeader(
                session_id=self.session_id,
                sender_id=self.device_id,
                sequence=seq,
                timestamp=time.time(),
            ),
            language=self.language,
            text=event.text.strip(),
            speech_duration_ms=event.speech_duration_ms,
            silence_duration_ms=event.silence_duration_ms,
        )

        # Transmit via transport (non-blocking)
        success = False
        t_send_start = time.perf_counter()
        if self.transport:
            success = self.transport.send_message(msg)
        t_send_dur = (time.perf_counter() - t_send_start) * 1000.0

        self.latest_transmission_metrics = {
            "message_id": msg.header.message_id,
            "sequence": seq,
            "text": msg.text,
            "stt_latency_ms": event.stt_latency_ms,
            "transport_send_time_ms": round(t_send_dur, 2),
            "transport_success": success,
        }

        logger.info(f"Transmitted text message #{seq}: '{msg.text}' (Success: {success}, SendTime: {t_send_dur:.2f}ms)")

        # Return to appropriate state
        if self.mode == CommunicationMode.CONTINUOUS and self._is_active:
            self._set_state(CommunicationState.RECORDING)
        else:
            self._set_state(CommunicationState.IDLE)

    def _on_receiver_event(self, event_data: Dict[str, Any]) -> None:
        """Handle events from the incoming ReceiverService."""
        event_name = event_data.get("event")
        if event_name == "text_received":
            self._set_state(CommunicationState.RECEIVING, event_data)
        elif event_name == "playback_start":
            self._set_state(CommunicationState.PLAYING, event_data)
        elif event_name == "playback_finish":
            if self.mode == CommunicationMode.CONTINUOUS and self._is_active:
                self._set_state(CommunicationState.RECORDING)
            else:
                self._set_state(CommunicationState.IDLE)

    # --- PTT (Push-to-Talk) Controls ---

    def press_ptt(self) -> None:
        """
        User presses PTT button.
        Starts/unpauses microphone and transitions to RECORDING state.
        """
        if self.mode != CommunicationMode.PTT:
            logger.warning("press_ptt() called while not in PTT mode.")
            return

        with self._lock:
            if self.state == CommunicationState.RECORDING:
                return

        logger.info("PTT PRESSED: Activating microphone recording.")
        if not self.mic_service.is_active():
            self.mic_service.start(language=self.language)
        else:
            self.mic_service.resume()

        self._set_state(CommunicationState.RECORDING)

    def release_ptt(self) -> None:
        """
        User releases PTT button.
        Forces segmenter flush, pauses microphone capture, transitions to PROCESSING.
        """
        if self.mode != CommunicationMode.PTT:
            return

        with self._lock:
            if self.state != CommunicationState.RECORDING:
                return

        logger.info("PTT RELEASED: Flushing pending utterance to STT.")
        self._set_state(CommunicationState.PROCESSING)

        # Force segmenter to finalize any speech accumulated so far
        try:
            forced_utterance = self.mic_service.segmenter.flush()
            if forced_utterance is not None:
                self.mic_service.stt_queue.put(forced_utterance)
        except Exception as e:
            logger.warning(f"Error flushing segmenter on PTT release: {e}")

        # Pause mic so background audio is not recorded while idle
        self.mic_service.pause()

    # --- Lifecycle Controls ---

    def start(self, device_input_index: Optional[int] = None) -> None:
        """Start communication controller and underlying services."""
        if self._is_active:
            return
        self._is_active = True

        # Start transport
        if self.transport:
            self.transport.start()

        # Start mic according to mode
        if self.mode == CommunicationMode.CONTINUOUS:
            self.mic_service.start(language=self.language, device=device_input_index)
            self._set_state(CommunicationState.RECORDING)
        else:
            # PTT mode: start mic in paused state so opening audio stream doesn't delay button press
            self.mic_service.start(language=self.language, device=device_input_index)
            self.mic_service.pause()
            self._set_state(CommunicationState.IDLE)

        logger.info(f"CommunicationController started in {self.mode} mode.")

    def stop(self) -> None:
        """Stop all services gracefully."""
        if not self._is_active:
            return
        self._is_active = False

        self.mic_service.stop()
        if self.receiver_service:
            self.receiver_service.shutdown()
        if self.transport:
            self.transport.stop()

        self._set_state(CommunicationState.IDLE)
        logger.info("CommunicationController stopped.")

    def set_mode(self, mode: Union[str, CommunicationMode]) -> None:
        """Switch between PTT and Continuous modes at runtime."""
        target_mode = CommunicationMode(mode.lower() if isinstance(mode, str) else mode.value)
        if target_mode == self.mode:
            return

        logger.info(f"Switching communication mode: {self.mode} -> {target_mode}")
        self.mode = target_mode

        if self._is_active:
            if self.mode == CommunicationMode.CONTINUOUS:
                self.mic_service.resume()
                self._set_state(CommunicationState.RECORDING)
            else:
                self.mic_service.pause()
                self._set_state(CommunicationState.IDLE)
