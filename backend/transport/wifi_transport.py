"""Wi-Fi Transport implementation using asyncio and WebSockets."""

import asyncio
import json
import logging
import threading
import time
from typing import Dict, Any, Union, Optional
from pydantic import BaseModel
import websockets

from backend.transport.base import BaseTransport

logger = logging.getLogger("itantra.transport.wifi")


class WiFiTransport(BaseTransport):
    """
    Wi-Fi transport implementation using WebSockets.
    Supports Host (Server) mode and Client mode for local P2P device communication.
    Runs asynchronously on a background thread to prevent blocking speech capture or STT workers.
    """

    def __init__(
        self,
        mode: str = "host",  # "host" or "client"
        host: str = "0.0.0.0",
        port: int = 8765,
        remote_host: Optional[str] = None,
        remote_port: Optional[int] = None,
        reconnect_interval_s: float = 2.0,
        max_payload_bytes: int = 65536,
    ):
        super().__init__()
        self.mode = mode.lower()
        if self.mode not in ("host", "client"):
            raise ValueError("WiFiTransport mode must be either 'host' or 'client'")

        self.host = host
        self.port = port
        self.remote_host = remote_host or "127.0.0.1"
        self.remote_port = remote_port or port
        self.reconnect_interval_s = reconnect_interval_s
        self.max_payload_bytes = max_payload_bytes

        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._server = None
        self._client_ws = None
        self._connected_clients: set = set()
        self._is_active = False

    def start(self) -> None:
        """Start the background transport thread and event loop."""
        if self._is_active:
            logger.warning("WiFiTransport already started.")
            return

        self._stop_event.clear()
        self._is_active = True
        self._thread = threading.Thread(
            target=self._run_event_loop,
            name=f"WiFiTransport-{self.mode.capitalize()}",
            daemon=True,
        )
        self._thread.start()
        logger.info(f"WiFiTransport ({self.mode}) started.")

    def _run_event_loop(self) -> None:
        """Run asyncio loop on background thread."""
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)

        if self.mode == "host":
            self._loop.run_until_complete(self._host_worker())
        else:
            self._loop.run_until_complete(self._client_worker())

        self._loop.close()

    async def _host_worker(self) -> None:
        """Host mode: WebSocket server accepting peer connections."""
        async def _handler(websocket):
            peer_addr = websocket.remote_address
            logger.info(f"Host: Peer connected from {peer_addr}")
            self._connected_clients.add(websocket)
            try:
                async for message in websocket:
                    self._process_raw_incoming(message, websocket)
            except websockets.exceptions.ConnectionClosed:
                logger.info(f"Host: Peer {peer_addr} disconnected.")
            except Exception as e:
                logger.error(f"Host: Error in peer connection: {e}")
            finally:
                self._connected_clients.discard(websocket)

        try:
            self._server = await websockets.serve(
                _handler,
                self.host,
                self.port,
                max_size=self.max_payload_bytes,
            )
            logger.info(f"Host: Listening on ws://{self.host}:{self.port}")
            while not self._stop_event.is_set():
                await asyncio.sleep(0.1)
        except Exception as e:
            logger.error(f"Host server error: {e}")
        finally:
            if self._server:
                self._server.close()
                await self._server.wait_closed()
            for ws in list(self._connected_clients):
                await ws.close()
            self._connected_clients.clear()

    async def _client_worker(self) -> None:
        """Client mode: WebSocket client connecting to remote host."""
        uri = f"ws://{self.remote_host}:{self.remote_port}"
        logger.info(f"Client: Target URI {uri}")

        while not self._stop_event.is_set():
            try:
                async with websockets.connect(
                    uri,
                    max_size=self.max_payload_bytes,
                ) as websocket:
                    self._client_ws = websocket
                    logger.info(f"Client: Connected to {uri}")
                    async for message in websocket:
                        self._process_raw_incoming(message, websocket)
            except (websockets.exceptions.ConnectionClosed, OSError) as e:
                self._client_ws = None
                if not self._stop_event.is_set():
                    logger.debug(f"Client: Connection failed/dropped ({e}). Reconnecting in {self.reconnect_interval_s}s...")
                    await asyncio.sleep(self.reconnect_interval_s)
            except Exception as e:
                self._client_ws = None
                logger.error(f"Client error: {e}")
                if not self._stop_event.is_set():
                    await asyncio.sleep(self.reconnect_interval_s)
            finally:
                self._client_ws = None

    def _process_raw_incoming(self, raw_data: Union[str, bytes], websocket) -> None:
        """Parse raw incoming message string and dispatch to handlers."""
        try:
            if isinstance(raw_data, bytes):
                raw_text = raw_data.decode("utf-8")
            else:
                raw_text = str(raw_data)

            if len(raw_text) > self.max_payload_bytes:
                logger.warning(f"Rejected oversized payload: {len(raw_text)} bytes")
                self.failed_messages_count += 1
                return

            payload = json.loads(raw_text)
            if not isinstance(payload, dict):
                logger.warning("Rejected non-dictionary JSON payload.")
                self.failed_messages_count += 1
                return

            # Dispatch
            self._dispatch_message(payload)

        except json.JSONDecodeError as e:
            logger.warning(f"Rejected malformed JSON: {e}")
            self.failed_messages_count += 1
        except Exception as e:
            logger.error(f"Unexpected error processing incoming packet: {e}")
            self.failed_messages_count += 1

    def send_message(self, message: Union[Dict[str, Any], BaseModel, str]) -> bool:
        """
        Thread-safe message transmission to connected peers.
        Accepts dict, Pydantic BaseModel, or serialized JSON string.
        """
        if not self.is_connected():
            logger.warning("Cannot send message: no active peer connection.")
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

            if self._loop is None or not self._loop.is_running():
                logger.warning("Event loop is not running.")
                self.failed_messages_count += 1
                return False

            try:
                running_loop = asyncio.get_running_loop()
            except RuntimeError:
                running_loop = None

            if running_loop is self._loop:
                # Already running inside transport event loop: schedule without blocking!
                asyncio.create_task(self._async_broadcast(json_str))
                self.sent_messages_count += 1
                return True

            future = asyncio.run_coroutine_threadsafe(
                self._async_broadcast(json_str),
                self._loop,
            )
            success = future.result(timeout=5.0)
            if success:
                self.sent_messages_count += 1
            else:
                self.failed_messages_count += 1
            return success
        except Exception as e:
            logger.error(f"Error sending message: {repr(e)}")
            self.failed_messages_count += 1
            return False

    async def _async_broadcast(self, json_str: str) -> bool:
        """Internal async broadcast to active websockets."""
        sent_any = False
        if self.mode == "host":
            disconnected = set()
            for ws in list(self._connected_clients):
                try:
                    await ws.send(json_str)
                    sent_any = True
                except Exception:
                    disconnected.add(ws)
            self._connected_clients.difference_update(disconnected)
        else:
            if self._client_ws is not None:
                try:
                    await self._client_ws.send(json_str)
                    sent_any = True
                except Exception as e:
                    logger.warning(f"Client failed to send: {e}")
                    sent_any = False

        return sent_any

    def is_connected(self) -> bool:
        """Check if at least one peer is connected."""
        if not self._is_active:
            return False
        if self.mode == "host":
            return len(self._connected_clients) > 0
        else:
            return self._client_ws is not None and self._client_ws.state.name == "OPEN"

    def status(self) -> str:
        """Human-readable connection status."""
        if not self._is_active:
            return "STOPPED"
        if self.mode == "host":
            count = len(self._connected_clients)
            return f"HOST_LISTENING ({count} peer{'s' if count != 1 else ''} connected)"
        else:
            if self.is_connected():
                return f"CLIENT_CONNECTED to {self.remote_host}:{self.remote_port}"
            return f"CLIENT_DISCONNECTED (reconnecting to {self.remote_host}:{self.remote_port})"

    def stop(self) -> None:
        """Stop transport and join thread."""
        if not self._is_active:
            return

        logger.info("Stopping WiFiTransport...")
        self._stop_event.set()
        self._is_active = False

        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3.0)
        self._server = None
        self._client_ws = None
        self._connected_clients.clear()
        logger.info("WiFiTransport stopped.")
