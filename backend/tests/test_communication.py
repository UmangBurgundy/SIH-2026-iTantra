"""Unit and integration tests for Phase 6 Live Communication System."""

import time
import io
import threading
import numpy as np
import pytest
import soundfile as sf
from unittest.mock import MagicMock, patch

from backend.schemas.protocol import (
    MessageHeader,
    TranscriptMessage,
    AckMessage,
    HeartbeatMessage,
)
from backend.schemas.events import TranscriptEvent
from backend.transport.wifi_transport import WiFiTransport
from backend.transport.bluetooth_transport import BluetoothTransport
from backend.services.audio_player import AudioPlayer
from backend.services.receiver_service import ReceiverService
from backend.services.communication_controller import (
    CommunicationController,
    CommunicationMode,
    CommunicationState,
)


# ============================================================================
# 1. PROTOCOL SERIALIZATION & VALIDATION TESTS
# ============================================================================

class TestMessageProtocol:
    """Test protocol schemas, serialization, and input validation."""

    def test_valid_transcript_message_serialization(self):
        msg = TranscriptMessage(
            header=MessageHeader(
                session_id="sess-123",
                sender_id="device-a",
                sequence=1,
            ),
            language="hi",
            text="नमस्ते",
            speech_duration_ms=1200,
            silence_duration_ms=600,
        )
        data = msg.to_dict()
        assert data["type"] == "transcript"
        assert data["header"]["sender_id"] == "device-a"
        assert data["language"] == "hi"
        assert data["text"] == "नमस्ते"
        assert data["speech_duration_ms"] == 1200
        assert data["header"]["sequence"] == 1

    def test_invalid_language_rejected(self):
        with pytest.raises(ValueError, match="Language code 'klingon' is not recognized"):
            TranscriptMessage(
                language="klingon",
                text="Some text",
            )

    def test_empty_or_whitespace_text_rejected(self):
        with pytest.raises(ValueError, match="cannot be empty"):
            TranscriptMessage(
                language="hi",
                text="   ",
            )

    def test_ack_message_serialization(self):
        ack = AckMessage(
            ack_message_id="msg-999",
            sequence=3,
            sender_id="device-b",
        )
        data = ack.to_dict()
        assert data["type"] == "ack"
        assert data["ack_message_id"] == "msg-999"
        assert data["sequence"] == 3

    def test_heartbeat_message(self):
        hb = HeartbeatMessage(sender_id="device-a", type="ping")
        assert hb.type == "ping"
        assert hb.sender_id == "device-a"


# ============================================================================
# 2. WI-FI TRANSPORT TESTS
# ============================================================================

class TestWiFiTransport:
    """Test Wi-Fi WebSocket transport connection, transmission, and error handling."""

    def test_wifi_host_client_send_and_receive(self):
        test_port = 8781
        host = WiFiTransport(mode="host", port=test_port)
        client = WiFiTransport(mode="client", remote_host="127.0.0.1", remote_port=test_port)

        received_at_host = []
        received_at_client = []

        host.register_handler(lambda msg: received_at_host.append(msg))
        client.register_handler(lambda msg: received_at_client.append(msg))

        try:
            host.start()
            time.sleep(0.2)
            client.start()

            # Wait for connection
            connected = False
            for _ in range(25):
                if host.is_connected() and client.is_connected():
                    connected = True
                    break
                time.sleep(0.1)

            assert connected, "Host and Client failed to connect via WebSocket"
            assert "HOST_LISTENING" in host.status()
            assert "CLIENT_CONNECTED" in client.status()

            # Client -> Host message
            test_msg = TranscriptMessage(
                header=MessageHeader(sender_id="client-device", sequence=1),
                language="hi",
                text="नमस्ते भारत",
            )
            success = client.send_message(test_msg)
            assert success is True

            # Wait for delivery
            for _ in range(20):
                if len(received_at_host) > 0:
                    break
                time.sleep(0.1)

            assert len(received_at_host) == 1
            assert received_at_host[0]["text"] == "नमस्ते भारत"
            assert received_at_host[0]["language"] == "hi"

            # Host -> Client response
            ack = AckMessage(
                ack_message_id=test_msg.header.message_id,
                sequence=1,
                sender_id="host-device",
            )
            success_ack = host.send_message(ack)
            assert success_ack is True

            for _ in range(20):
                if len(received_at_client) > 0:
                    break
                time.sleep(0.1)

            assert len(received_at_client) == 1
            assert received_at_client[0]["type"] == "ack"
            assert received_at_client[0]["ack_message_id"] == test_msg.header.message_id

        finally:
            client.stop()
            host.stop()

    def test_wifi_send_when_disconnected_fails_gracefully(self):
        client = WiFiTransport(mode="client", remote_host="127.0.0.1", remote_port=9999)
        # Not connected
        assert client.is_connected() is False
        msg = TranscriptMessage(language="hi", text="परीक्षण")
        success = client.send_message(msg)
        assert success is False
        assert client.failed_messages_count == 1


# ============================================================================
# 3. BLUETOOTH TRANSPORT TESTS
# ============================================================================

class TestBluetoothTransport:
    """Test Bluetooth transport interface in loopback mode."""

    def test_bluetooth_loopback_communication(self):
        test_port = 9882
        server = BluetoothTransport(mode="server", loopback_port=test_port, use_hardware_rfcomm=False)
        client = BluetoothTransport(mode="client", loopback_port=test_port, use_hardware_rfcomm=False)

        received_messages = []
        client.register_handler(lambda msg: received_messages.append(msg))

        try:
            server.start()
            time.sleep(0.2)
            client.start()

            # Wait for connection
            connected = False
            for _ in range(25):
                if server.is_connected() and client.is_connected():
                    connected = True
                    break
                time.sleep(0.1)

            assert connected, "Bluetooth loopback sockets failed to connect"

            msg = TranscriptMessage(
                header=MessageHeader(sender_id="bt-server", sequence=1),
                language="mr",
                text="नमस्कार",
            )
            success = server.send_message(msg)
            assert success is True

            for _ in range(20):
                if len(received_messages) > 0:
                    break
                time.sleep(0.1)

            assert len(received_messages) == 1
            assert received_messages[0]["text"] == "नमस्कार"
            assert received_messages[0]["language"] == "mr"

        finally:
            client.stop()
            server.stop()


# ============================================================================
# 4. AUDIO PLAYER & SEQUENTIAL QUEUE TESTS
# ============================================================================

class TestAudioPlayer:
    """Test AudioPlayer sequential queuing and non-blocking playback."""

    @patch("sounddevice.play")
    @patch("sounddevice.wait")
    def test_audio_player_sequential_queue(self, mock_wait, mock_play):
        player = AudioPlayer()
        try:
            # Create a small valid WAV in memory
            samplerate = 16000
            data = np.zeros(1600, dtype=np.int16)
            buf = io.BytesIO()
            sf.write(buf, data, samplerate, format="WAV")
            wav_bytes = buf.getvalue()

            callbacks_called = []

            player.play(
                wav_bytes,
                on_start=lambda: callbacks_called.append("start1"),
                on_finish=lambda: callbacks_called.append("finish1"),
            )
            player.play(
                wav_bytes,
                on_start=lambda: callbacks_called.append("start2"),
                on_finish=lambda: callbacks_called.append("finish2"),
            )

            # Wait for background thread to drain
            for _ in range(20):
                if len(callbacks_called) >= 4:
                    break
                time.sleep(0.1)

            assert callbacks_called == ["start1", "finish1", "start2", "finish2"]
            assert player.played_count == 2
            assert mock_play.call_count == 2
        finally:
            player.shutdown()


# ============================================================================
# 5. RECEIVER SERVICE & DEDUPLICATION TESTS
# ============================================================================

class TestReceiverService:
    """Test ReceiverService message validation, deduplication, and TTS routing."""

    def test_receiver_deduplication_and_ordering(self):
        mock_transport = MagicMock()
        mock_tts = MagicMock()
        mock_player = MagicMock()

        # Mock TTS synthesize
        mock_tts.synthesize.return_value = (b"RIFFdummywav", 0.05, 1.0)

        receiver = ReceiverService(
            transport=mock_transport,
            tts_service=mock_tts,
            audio_player=mock_player,
        )

        try:
            msg1 = TranscriptMessage(
                header=MessageHeader(message_id="msg-100", sender_id="dev-a", sequence=1),
                language="hi",
                text="पहला वाक्य",
            )
            # Send message 1
            receiver._on_transport_message(msg1.to_dict())

            # Send DUPLICATE of message 1
            receiver._on_transport_message(msg1.to_dict())

            # Send message 2
            msg2 = TranscriptMessage(
                header=MessageHeader(message_id="msg-200", sender_id="dev-a", sequence=2),
                language="hi",
                text="दूसरा वाक्य",
            )
            receiver._on_transport_message(msg2.to_dict())

            # Wait for TTS worker loop
            for _ in range(25):
                if receiver.processed_count >= 2:
                    break
                time.sleep(0.1)

            assert receiver.received_count == 3
            assert receiver.duplicate_count == 1
            assert receiver.processed_count == 2
            assert mock_tts.synthesize.call_count == 2

            # Verify ACK was sent for both unique messages
            assert mock_transport.send_message.call_count >= 2

        finally:
            receiver.shutdown()

    def test_receiver_invalid_payload_rejected(self):
        mock_transport = MagicMock()
        receiver = ReceiverService(transport=mock_transport)
        try:
            # Invalid payload missing 'language' and 'text'
            receiver._on_transport_message({"type": "transcript", "bad_field": 123})
            assert receiver.invalid_count == 1
            assert receiver.processed_count == 0
        finally:
            receiver.shutdown()


# ============================================================================
# 6. COMMUNICATION CONTROLLER & PTT / CONTINUOUS TESTS
# ============================================================================

class TestCommunicationController:
    """Test CommunicationController PTT and Continuous state machines."""

    def test_ptt_state_machine_flow(self):
        mock_transport = MagicMock()
        mock_mic = MagicMock()
        mock_mic.is_active.return_value = True
        mock_receiver = MagicMock()

        controller = CommunicationController(
            device_id="device-a",
            mode=CommunicationMode.PTT,
            transport=mock_transport,
            mic_service=mock_mic,
            receiver_service=mock_receiver,
        )

        state_history = []
        controller.add_listener(lambda e: state_history.append(e.get("new_state")))

        assert controller.state == CommunicationState.IDLE

        # Press PTT
        controller.press_ptt()
        assert controller.state == CommunicationState.RECORDING
        mock_mic.resume.assert_called_once()

        # Release PTT
        controller.release_ptt()
        assert controller.state == CommunicationState.PROCESSING
        mock_mic.pause.assert_called_once()

        # Simulate STT completed and emitted transcript event
        dummy_event = TranscriptEvent(
            type="transcript",
            language="hi",
            text="परीक्षण सफल",
            sentence_index=1,
            speech_duration_ms=1500,
            silence_duration_ms=500,
            stt_latency_ms=250.0,
            total_latency_ms=750.0,
            timestamp=time.time(),
        )

        controller._on_local_transcript(dummy_event)

        # Transport must have been called with TranscriptMessage
        assert mock_transport.send_message.call_count == 1
        sent_arg = mock_transport.send_message.call_args[0][0]
        assert isinstance(sent_arg, TranscriptMessage)
        assert sent_arg.text == "परीक्षण सफल"
        assert sent_arg.language == "hi"

        # Controller returns to IDLE in PTT mode
        assert controller.state == CommunicationState.IDLE

    def test_continuous_mode_flow(self):
        mock_transport = MagicMock()
        mock_mic = MagicMock()
        mock_mic.is_active.return_value = False
        mock_receiver = MagicMock()

        controller = CommunicationController(
            device_id="device-a",
            mode=CommunicationMode.CONTINUOUS,
            transport=mock_transport,
            mic_service=mock_mic,
            receiver_service=mock_receiver,
        )

        controller.start()
        assert controller.state == CommunicationState.RECORDING
        mock_mic.start.assert_called_once()

        # Simulate utterance emitted
        dummy_event = TranscriptEvent(
            type="transcript",
            language="hi",
            text="नमस्ते",
            sentence_index=1,
            speech_duration_ms=1000,
            silence_duration_ms=500,
            stt_latency_ms=200.0,
            total_latency_ms=700.0,
            timestamp=time.time(),
        )
        controller._on_local_transcript(dummy_event)

        # In continuous mode, after transmitting it automatically returns to RECORDING
        assert controller.state == CommunicationState.RECORDING
        assert mock_transport.send_message.call_count == 1

        controller.stop()
        assert controller.state == CommunicationState.IDLE
