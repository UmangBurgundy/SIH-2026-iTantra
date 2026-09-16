"""Memory Lifecycle Manager for on-device RAM optimization."""

import enum
import gc
import logging
import os
import psutil
import torch
from typing import Dict, Any

logger = logging.getLogger("itantra.lifecycle")


class LifecycleMode(str, enum.Enum):
    """Operational memory lifecycle modes."""
    LOW_LATENCY = "low_latency"  # Keep active models warm in RAM for minimum latency
    LOW_MEMORY = "low_memory"    # Aggressively release unneeded caches and unused weights


class MemoryLifecycleManager:
    """
    Manages process memory footprint, model caching, and garbage collection.
    Designed for memory-constrained mobile environments (250MB–500MB target RAM).
    """

    def __init__(self, initial_mode: LifecycleMode = LifecycleMode.LOW_LATENCY):
        self.mode = initial_mode
        self._process = psutil.Process(os.getpid())

    def set_mode(self, mode: LifecycleMode) -> None:
        """Switch operational mode."""
        self.mode = mode
        logger.info(f"MemoryLifecycleManager mode set to: {self.mode}")
        if self.mode == LifecycleMode.LOW_MEMORY:
            self.cleanup_memory()

    def cleanup_memory(self) -> Dict[str, float]:
        """
        Run aggressive memory reclamation:
        Python garbage collector + PyTorch allocator cache release.
        """
        before_mb = self.get_rss_mb()
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        after_mb = self.get_rss_mb()
        freed_mb = max(0.0, before_mb - after_mb)
        logger.debug(f"Memory cleanup: freed {freed_mb:.1f} MB (RSS: {after_mb:.1f} MB)")
        return {
            "before_mb": before_mb,
            "after_mb": after_mb,
            "freed_mb": freed_mb,
        }

    def get_rss_mb(self) -> float:
        """Return resident set size (RSS) memory in megabytes."""
        try:
            return self._process.memory_info().rss / (1024 * 1024)
        except Exception:
            return 0.0

    def get_memory_diagnostics(self) -> Dict[str, Any]:
        """Return comprehensive system memory diagnostics."""
        vmem = psutil.virtual_memory()
        return {
            "mode": self.mode.value,
            "process_rss_mb": round(self.get_rss_mb(), 2),
            "system_ram_total_mb": round(vmem.total / (1024 * 1024), 2),
            "system_ram_available_mb": round(vmem.available / (1024 * 1024), 2),
            "system_ram_percent": vmem.percent,
        }


# Global singleton
lifecycle_manager = MemoryLifecycleManager()
