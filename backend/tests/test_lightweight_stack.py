"""Unit tests for Phase 7 Lightweight Speech Stack and Modular Backends."""

import time
import numpy as np
import pytest
import torch
from unittest.mock import MagicMock, patch

from backend.services.backends.base import BaseSTTBackend, BaseTTSBackend
from backend.services.backends.stt_indic_conformer import IndicConformerBackend
from backend.services.backends.stt_whisper import WhisperSTTBackend
from backend.services.backends.tts_indic_f5 import IndicF5Backend
from backend.services.backends.tts_mms import MMSTTSBackend, MMS_LANG_MAP
from backend.services.stt_service import STTService
from backend.services.tts_service import TTSService
from backend.services.alert_cache import AlertAudioCache
from backend.services.lifecycle_manager import MemoryLifecycleManager, LifecycleMode


# ============================================================================
# 1. BACKEND INTERFACES & REGISTRATION
# ============================================================================

class TestModularBackends:
    """Test backend interfaces, stats, and registration."""

    def test_stt_backend_switching(self):
        stt = STTService.get_instance()
        assert stt.active_backend_name == "baseline"

        stt.set_backend("lightweight")
        assert stt.active_backend_name == "lightweight"
        stats = stt.get_stats()
        assert stats["backend"] == "WhisperSTTBackend"

        # Switch back to baseline
        stt.set_backend("baseline")
        assert stt.active_backend_name == "baseline"
        stats = stt.get_stats()
        assert stats["backend"] == "IndicConformerBackend"

    def test_invalid_stt_backend_raises(self):
        stt = STTService.get_instance()
        with pytest.raises(ValueError, match="Unknown STT backend"):
            stt.set_backend("nonexistent_backend")

    def test_tts_backend_switching(self):
        tts = TTSService.get_instance()
        assert tts.active_backend_name == "baseline"

        tts.set_backend("lightweight")
        assert tts.active_backend_name == "lightweight"
        stats = tts.get_stats()
        assert stats["backend"] == "MMSTTSBackend"

        # Switch back
        tts.set_backend("baseline")
        assert tts.active_backend_name == "baseline"
        stats = tts.get_stats()
        assert stats["backend"] == "IndicF5Backend"

    def test_invalid_tts_backend_raises(self):
        tts = TTSService.get_instance()
        with pytest.raises(ValueError, match="Unknown TTS backend"):
            tts.set_backend("nonexistent_backend")


# ============================================================================
# 2. MMS-TTS LIGHTWEIGHT BACKEND TESTS
# ============================================================================

class TestMMSTTSBackend:
    """Test Meta MMS-TTS backend functionality, LRU caching, and error handling."""

    def test_mms_language_coverage(self):
        required = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"]
        for lang in required:
            assert lang in MMS_LANG_MAP, f"Missing target language {lang} in MMS_LANG_MAP"

    @patch("transformers.AutoTokenizer.from_pretrained")
    @patch("transformers.VitsModel.from_pretrained")
    def test_mms_synthesis_mock(self, mock_vits, mock_tok):
        mock_model = MagicMock()
        mock_model.to.return_value = mock_model
        mock_tok_inst = MagicMock()
        mock_tok_inst.return_value = {}
        mock_tok.return_value = mock_tok_inst

        # Mock waveform output as real numpy array
        mock_output = MagicMock()
        mock_output.waveform = np.ones(16000, dtype=np.float32) * 0.5
        mock_model.return_value = mock_output
        mock_model.config.sampling_rate = 16000

        mock_vits.return_value = mock_model

        backend = MMSTTSBackend()
        wav_bytes, infer_time, audio_dur = backend.synthesize("परीक्षण", language="hi")

        assert isinstance(wav_bytes, bytes)
        assert len(wav_bytes) > 44  # Valid WAV header size
        assert audio_dur == 1.0
        assert infer_time >= 0.0

    def test_mms_unsupported_language_rejected(self):
        backend = MMSTTSBackend()
        with pytest.raises(Exception):
            backend.synthesize("Hello", language="klingon")


# ============================================================================
# 3. WHISPER LIGHTWEIGHT STT BACKEND TESTS
# ============================================================================

class TestWhisperSTTBackend:
    """Test Whisper-Tiny lightweight STT backend."""

    @patch("transformers.AutoProcessor.from_pretrained")
    @patch("transformers.AutoModelForSpeechSeq2Seq.from_pretrained")
    def test_whisper_transcription_mock(self, mock_model_cls, mock_proc_cls):
        mock_model = MagicMock()
        mock_proc = MagicMock()

        mock_inputs = MagicMock()
        mock_inputs.input_features = torch.zeros(1, 80, 3000)
        mock_proc.return_value = mock_inputs
        mock_proc.batch_decode.return_value = ["परीक्षण सफल"]

        mock_model_cls.return_value = mock_model
        mock_proc_cls.return_value = mock_proc

        backend = WhisperSTTBackend()
        backend.load()

        dummy_audio = torch.zeros(1, 16000)
        text, infer_time = backend.transcribe(dummy_audio, language="hi")

        assert text == "परीक्षण सफल"
        assert infer_time >= 0.0


# ============================================================================
# 4. ALERT AUDIO CACHE TESTS
# ============================================================================

class TestAlertAudioCache:
    """Test pre-synthesized alert audio caching for instant playback."""

    def test_alert_cache_put_and_get(self, tmp_path):
        cache = AlertAudioCache(cache_dir=str(tmp_path))
        phrase = "आग लग गई है"
        lang = "hi"
        fake_wav = b"RIFFdummywavbytes"

        assert cache.is_cached(phrase, lang) is False
        assert cache.get(phrase, lang) is None

        cache.put(phrase, lang, fake_wav)
        assert cache.is_cached(phrase, lang) is True
        assert cache.get(phrase, lang) == fake_wav

    def test_tts_service_uses_alert_cache(self, tmp_path):
        import sys
        cache = AlertAudioCache(cache_dir=str(tmp_path))
        phrase = "इमरजेंसी अलार्म"
        lang = "hi"
        fake_wav = b"RIFFalertwavbytes12345"
        cache.put(phrase, lang, fake_wav)

        tts_mod = sys.modules["backend.services.tts_service"]
        with patch.object(tts_mod, "alert_cache", cache):
            tts = TTSService.get_instance()
            # Synthesize phrase that exists in alert cache
            wav_bytes, infer_time, audio_dur = tts.synthesize(phrase, language=lang, use_alert_cache=True)
            assert wav_bytes == fake_wav
            assert infer_time <= 0.005  # Sub-millisecond instant hit


# ============================================================================
# 5. MEMORY LIFECYCLE MANAGER TESTS
# ============================================================================

class TestMemoryLifecycleManager:
    """Test memory diagnostics, mode switching, and cleanup."""

    def test_lifecycle_manager_diagnostics(self):
        mgr = MemoryLifecycleManager(initial_mode=LifecycleMode.LOW_LATENCY)
        diag = mgr.get_memory_diagnostics()

        assert diag["mode"] == "low_latency"
        assert diag["process_rss_mb"] > 0
        assert diag["system_ram_total_mb"] > 0

    def test_lifecycle_mode_switch_and_cleanup(self):
        mgr = MemoryLifecycleManager(initial_mode=LifecycleMode.LOW_LATENCY)
        mgr.set_mode(LifecycleMode.LOW_MEMORY)
        assert mgr.mode == LifecycleMode.LOW_MEMORY

        result = mgr.cleanup_memory()
        assert "before_mb" in result
        assert "after_mb" in result
        assert "freed_mb" in result
