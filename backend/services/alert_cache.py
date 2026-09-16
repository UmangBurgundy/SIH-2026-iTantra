"""Pre-synthesized Alert Audio Cache for Instant Emergency Voice Playback."""

import hashlib
import logging
import os
from typing import Optional, Dict, Tuple
from pathlib import Path

logger = logging.getLogger("itantra.alert_cache")

# Standard critical emergency phrases
STANDARD_EMERGENCY_PHRASES = {
    "hi": [
        "आग लग गई है, कृपया तुरंत बाहर निकलें",
        "सहायता की आवश्यकता है",
        "सावधान! खतरा है",
        "धुआं पाया गया है",
        "इमरजेंसी अलार्म",
    ],
    "en": [
        "Fire detected! Please evacuate immediately.",
        "Emergency assistance required.",
        "Warning! Hazard detected.",
        "Smoke detected in the area.",
        "Emergency broadcast alert.",
    ],
}


class AlertAudioCache:
    """
    Cache for pre-synthesized emergency voice alert messages.
    Fixed critical safety phrases are synthesized once and cached on disk/in-memory,
    enabling INSTANT (sub-millisecond) voice playback on the speaker during emergency events.
    """

    def __init__(self, cache_dir: Optional[str] = None):
        if cache_dir is None:
            self.cache_dir = Path(os.path.expanduser("~")) / ".cache" / "itantra" / "alert_audio"
        else:
            self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)

        # In-memory index: {cache_key: wav_bytes}
        self._memory_cache: Dict[str, bytes] = {}
        self._load_existing_cache()

    def _make_key(self, text: str, language: str) -> str:
        """Create a deterministic hash key for text + language."""
        raw = f"{language.strip().lower()}::{text.strip()}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]

    def _load_existing_cache(self) -> None:
        """Load any previously cached alert audio files from disk into memory."""
        count = 0
        for f in self.cache_dir.glob("*.wav"):
            try:
                self._memory_cache[f.stem] = f.read_bytes()
                count += 1
            except Exception as e:
                logger.debug(f"Could not load cached alert {f.name}: {e}")
        if count > 0:
            logger.info(f"Loaded {count} pre-synthesized alert audio clips from disk cache.")

    def get(self, text: str, language: str) -> Optional[bytes]:
        """Retrieve pre-synthesized audio if present in cache."""
        key = self._make_key(text, language)
        return self._memory_cache.get(key)

    def put(self, text: str, language: str, wav_bytes: bytes) -> None:
        """Store synthesized audio into cache."""
        if not wav_bytes:
            return
        key = self._make_key(text, language)
        self._memory_cache[key] = wav_bytes

        # Persist to disk
        file_path = self.cache_dir / f"{key}.wav"
        try:
            file_path.write_bytes(wav_bytes)
        except Exception as e:
            logger.warning(f"Failed to persist alert audio cache to disk: {e}")

    def preload_standard_alerts(self, tts_service, languages=("hi", "en")) -> int:
        """Pre-synthesize all standard emergency alerts using the provided TTS service."""
        preloaded = 0
        for lang in languages:
            phrases = STANDARD_EMERGENCY_PHRASES.get(lang, [])
            for phrase in phrases:
                if self.get(phrase, lang) is None:
                    try:
                        logger.info(f"Pre-synthesizing emergency alert [{lang}]: '{phrase}'")
                        wav_bytes, _, _ = tts_service.synthesize(phrase, language=lang)
                        self.put(phrase, lang, wav_bytes)
                        preloaded += 1
                    except Exception as e:
                        logger.warning(f"Could not pre-synthesize alert '{phrase}': {e}")
                else:
                    preloaded += 1
        logger.info(f"Alert Audio Cache ready with {len(self._memory_cache)} cached phrases.")
        return preloaded

    def is_cached(self, text: str, language: str) -> bool:
        """Check if an alert phrase is already pre-synthesized."""
        return self.get(text, language) is not None

    def clear(self) -> None:
        """Clear memory and disk cache."""
        self._memory_cache.clear()
        for f in self.cache_dir.glob("*.wav"):
            try:
                f.unlink()
            except Exception:
                pass


# Global singleton instance
alert_cache = AlertAudioCache()
