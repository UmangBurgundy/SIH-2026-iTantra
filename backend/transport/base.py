"""Abstract base transport interface for iTantra two-device communication."""

from abc import ABC, abstractmethod
from typing import Callable, Dict, Any, Union, List, Optional
from pydantic import BaseModel


class BaseTransport(ABC):
    """
    Abstract transport interface defining the communication contract.
    The speech pipeline communicates strictly through this contract without
    knowing whether the underlying transport is Wi-Fi, Bluetooth, or another medium.
    """

    def __init__(self):
        self.handlers: List[Callable[[Dict[str, Any]], None]] = []
        self.sent_messages_count = 0
        self.received_messages_count = 0
        self.failed_messages_count = 0

    def register_handler(self, handler: Callable[[Dict[str, Any]], None]) -> None:
        """Register a callback for incoming messages."""
        if handler not in self.handlers:
            self.handlers.append(handler)

    def remove_handler(self, handler: Callable[[Dict[str, Any]], None]) -> None:
        """Unregister an incoming message callback."""
        if handler in self.handlers:
            self.handlers.remove(handler)

    def _dispatch_message(self, message_dict: Dict[str, Any]) -> None:
        """Internal helper to dispatch received messages to all registered handlers."""
        self.received_messages_count += 1
        for handler in list(self.handlers):
            try:
                handler(message_dict)
            except Exception as e:
                # Handlers must not break the transport receiver
                pass

    @abstractmethod
    def start(self) -> None:
        """Start or bind the transport service."""
        pass

    @abstractmethod
    def stop(self) -> None:
        """Stop transport and release network sockets."""
        pass

    @abstractmethod
    def send_message(self, message: Union[Dict[str, Any], BaseModel, str]) -> bool:
        """Send a message to the connected peer."""
        pass

    @abstractmethod
    def is_connected(self) -> bool:
        """Check if an active connection to a peer is established."""
        pass

    @abstractmethod
    def status(self) -> str:
        """Return human-readable status string."""
        pass

    def get_stats(self) -> Dict[str, Any]:
        """Return transport statistics."""
        return {
            "is_connected": self.is_connected(),
            "status": self.status(),
            "sent_messages": self.sent_messages_count,
            "received_messages": self.received_messages_count,
            "failed_messages": self.failed_messages_count,
        }
