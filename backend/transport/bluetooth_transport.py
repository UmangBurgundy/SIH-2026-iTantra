"""Bluetooth Transport implementation using native socket RFCOMM or simulated loopback."""

import json
import logging
import socket
import threading
import time
from typing import Dict, Any, Union, Optional
from pydantic import BaseModel

from backend.transport.base import BaseTransport

logger = logging.getLogger("itantra.transport.bluetooth")


class BluetoothTransport(BaseTransport):
    """
    Bluetooth transport implementation behind the BaseTransport abstraction.
    Designed for future Android Bluetooth SPP/RFCOMM compatibility.
    Uses standard socket-level communication (native AF_BLUETOOTH or loopback bridge)
    without proprietary third-party SDKs.
    """

    def __init__(
        self,
        mode: str = "server",  # "server" or "client"
        channel: int = 1,
        target_mac: Optional[str] = None,
        use_hardware_rfcomm: bool = False,
        loopback_port: int = 9876,
    ):
        super().__init__()
        self.mode = mode.lower()
        if self.mode not in ("server", "client"):
            raise ValueError("BluetoothTransport mode must be 'server' or 'client'")

        self.channel = channel
        self.target_mac = target_mac
        self.use_hardware_rfcomm = use_hardware_rfcomm
        self.loopback_port = loopback_port

        self._is_active = False
        self._connected = False
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._server_sock: Optional[socket.socket] = None
        self._peer_sock: Optional[socket.socket] = None
        self._lock = threading.Lock()

    def start(self) -> None:
        """Start the Bluetooth transport worker thread."""
        if self._is_active:
            return

        self._stop_event.clear()
        self._is_active = True
        self._thread = threading.Thread(
            target=self._worker,
            name=f"BluetoothTransport-{self.mode.capitalize()}",
            daemon=True,
        )
        self._thread.start()
        logger.info(f"BluetoothTransport ({self.mode}, hw_rfcomm={self.use_hardware_rfcomm}) started.")

    def _create_socket(self) -> socket.socket:
        """Create socket based on whether hardware RFCOMM is enabled or fallback TCP loopback."""
        if self.use_hardware_rfcomm and hasattr(socket, "AF_BLUETOOTH"):
            return socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        else:
            return socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    def _worker(self) -> None:
        """Background thread handling connection and frame reading."""
        if self.mode == "server":
            self._server_loop()
        else:
            self._client_loop()

    def _server_loop(self) -> None:
        """Server mode: bind channel and accept connection."""
        try:
            sock = self._create_socket()
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if self.use_hardware_rfcomm:
                # RFCOMM bind takes ("", channel)
                sock.bind(("", self.channel))
            else:
                sock.bind(("127.0.0.1", self.loopback_port))

            sock.listen(1)
            sock.settimeout(1.0)
            self._server_sock = sock

            logger.info(f"Bluetooth Server: Listening on channel/port {self.channel if self.use_hardware_rfcomm else self.loopback_port}")

            while not self._stop_event.is_set():
                try:
                    client, addr = sock.accept()
                    logger.info(f"Bluetooth Server: Peer connected from {addr}")
                    with self._lock:
                        self._peer_sock = client
                        self._connected = True
                    self._read_stream(client)
                except socket.timeout:
                    continue
                except Exception as e:
                    if not self._stop_event.is_set():
                        logger.debug(f"Bluetooth server accept exception: {e}")
                    break
        except Exception as e:
            logger.error(f"Bluetooth Server error: {e}")
        finally:
            self._cleanup()

    def _client_loop(self) -> None:
        """Client mode: connect to server and read frames."""
        while not self._stop_event.is_set():
            try:
                sock = self._create_socket()
                sock.settimeout(3.0)
                if self.use_hardware_rfcomm:
                    if not self.target_mac:
                        raise ValueError("target_mac required for hardware RFCOMM client")
                    sock.connect((self.target_mac, self.channel))
                else:
                    sock.connect(("127.0.0.1", self.loopback_port))

                logger.info("Bluetooth Client: Connected to peer.")
                with self._lock:
                    self._peer_sock = sock
                    self._connected = True
                self._read_stream(sock)
            except Exception as e:
                with self._lock:
                    self._peer_sock = None
                    self._connected = False
                if not self._stop_event.is_set():
                    logger.debug(f"Bluetooth client connect failed: {e}. Retrying in 2s...")
                    time.sleep(2.0)

    def _read_stream(self, sock: socket.socket) -> None:
        """Read length-prefixed or newline-delimited JSON frames from socket."""
        sock.settimeout(1.0)
        buffer = ""
        while not self._stop_event.is_set():
            try:
                data = sock.recv(4096)
                if not data:
                    logger.info("Bluetooth peer closed connection.")
                    break
                buffer += data.decode("utf-8")
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        payload = json.loads(line)
                        if isinstance(payload, dict):
                            self._dispatch_message(payload)
                    except json.JSONDecodeError as e:
                        logger.warning(f"Malformed JSON on bluetooth stream: {e}")
                        self.failed_messages_count += 1
            except socket.timeout:
                continue
            except Exception as e:
                logger.debug(f"Bluetooth stream read error: {e}")
                break

        with self._lock:
            if self._peer_sock == sock:
                self._peer_sock = None
                self._connected = False

    def send_message(self, message: Union[Dict[str, Any], BaseModel, str]) -> bool:
        """Send message with newline framing over Bluetooth socket."""
        if not self.is_connected():
            logger.warning("Cannot send message: Bluetooth not connected.")
            self.failed_messages_count += 1
            return False

        try:
            if isinstance(message, BaseModel):
                json_str = message.model_dump_json()
            elif isinstance(message, dict):
                json_str = json.dumps(message)
            elif isinstance(message, str):
                json_str = message
            else:
                raise ValueError(f"Unsupported message type: {type(message)}")

            wire_data = (json_str + "\n").encode("utf-8")
            with self._lock:
                if self._peer_sock is not None:
                    self._peer_sock.sendall(wire_data)
                    self.sent_messages_count += 1
                    return True
                else:
                    self.failed_messages_count += 1
                    return False
        except Exception as e:
            logger.error(f"Bluetooth send_message failed: {e}")
            self.failed_messages_count += 1
            return False

    def is_connected(self) -> bool:
        with self._lock:
            return self._connected and self._peer_sock is not None

    def status(self) -> str:
        if not self._is_active:
            return "STOPPED"
        if self.is_connected():
            return f"CONNECTED ({self.mode})"
        return f"WAITING_FOR_PEER ({self.mode})"

    def _cleanup(self) -> None:
        with self._lock:
            self._connected = False
            if self._peer_sock:
                try:
                    self._peer_sock.close()
                except Exception:
                    pass
                self._peer_sock = None
            if self._server_sock:
                try:
                    self._server_sock.close()
                except Exception:
                    pass
                self._server_sock = None

    def stop(self) -> None:
        """Stop transport worker and release sockets."""
        if not self._is_active:
            return
        logger.info("Stopping BluetoothTransport...")
        self._stop_event.set()
        self._is_active = False
        self._cleanup()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2.0)
        logger.info("BluetoothTransport stopped.")
