"""Comprehensive API test suite for iTantra backend."""

import pytest


class TestHealthAndLanguagesEndpoints:
    """Test health and language configuration APIs."""

    def test_health_endpoint(self, client):
        """1. Verify GET /api/health returns ok status."""
        response = client.get("/api/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert "version" in data
        assert "stt_status" in data
        assert "tts_status" in data

    def test_languages_endpoint(self, client):
        """2. Verify GET /api/languages lists supported languages and capabilities."""
        response = client.get("/api/languages")
        assert response.status_code == 200
        data = response.json()
        assert "languages" in data
        langs = data["languages"]
        assert len(langs) == 10

        lang_map = {l["code"]: l for l in langs}
        expected_codes = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"]
        for code in expected_codes:
            assert code in lang_map
            assert lang_map[code]["tts_supported"] is True

        # Indic languages must support STT; English must not claim STT support
        for code in ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"]:
            assert lang_map[code]["stt_supported"] is True

        assert lang_map["en"]["stt_supported"] is False


class TestSpeechToTextEndpoint:
    """Test Speech-to-Text API and validations."""

    def test_stt_valid_hindi_audio(self, client, real_audio_bytes):
        """3. Verify valid STT request with real Hindi audio."""
        files = {"audio": ("audio.wav", real_audio_bytes, "audio/wav")}
        data = {"language": "hi", "decoding": "ctc"}

        response = client.post("/api/speech-to-text", files=files, data=data)
        assert response.status_code == 200
        result = response.json()

        assert "text" in result
        assert result["language"] == "hi"
        assert result["audio_duration_sec"] > 0
        assert result["inference_time_sec"] > 0
        assert "rtf" in result
        assert len(result["text"]) > 0

    def test_stt_invalid_language(self, client, synthetic_wav_bytes):
        """4. Verify invalid language code returns 400 Bad Request."""
        files = {"audio": ("test.wav", synthetic_wav_bytes, "audio/wav")}
        data = {"language": "xyz_invalid"}

        response = client.post("/api/speech-to-text", files=files, data=data)
        assert response.status_code == 400
        result = response.json()
        assert result["error"] == "unsupported_language"

    def test_stt_missing_audio(self, client):
        """5. Verify request without audio file returns 422 Unprocessable Entity."""
        data = {"language": "hi"}
        response = client.post("/api/speech-to-text", data=data)
        assert response.status_code == 422

    def test_stt_empty_audio_file(self, client):
        """Verify empty audio file (0 bytes) returns 400."""
        files = {"audio": ("empty.wav", b"", "audio/wav")}
        data = {"language": "hi"}

        response = client.post("/api/speech-to-text", files=files, data=data)
        assert response.status_code == 400
        result = response.json()
        assert result["error"] == "invalid_audio"

    def test_stt_unsupported_language_combination(self, client, synthetic_wav_bytes):
        """8. Verify English (en) returns 400 with honest capability gap explanation."""
        files = {"audio": ("test.wav", synthetic_wav_bytes, "audio/wav")}
        data = {"language": "en"}

        response = client.post("/api/speech-to-text", files=files, data=data)
        assert response.status_code == 400
        result = response.json()
        assert result["error"] == "unsupported_language"
        assert "IndicConformer" in result["detail"] or "Whisper" in result["detail"]


class TestTextToSpeechEndpoint:
    """Test Text-to-Speech API and validations."""

    def test_tts_valid_hindi_request(self, client):
        """6. Verify valid TTS request returns playable WAV audio bytes."""
        payload = {
            "text": "नमस्ते",
            "language": "hi"
        }
        response = client.post("/api/text-to-speech", json=payload)
        assert response.status_code == 200
        assert response.headers["content-type"] == "audio/wav"
        assert len(response.content) > 1000
        assert "X-Inference-Time-Sec" in response.headers
        assert "X-Audio-Duration-Sec" in response.headers

    def test_tts_empty_text(self, client):
        """7. Verify empty text returns 422 Unprocessable Entity."""
        payload = {
            "text": "",
            "language": "hi"
        }
        response = client.post("/api/text-to-speech", json=payload)
        assert response.status_code == 422

    def test_tts_whitespace_only_text(self, client):
        """Verify whitespace only text returns 422."""
        payload = {
            "text": "     ",
            "language": "hi"
        }
        response = client.post("/api/text-to-speech", json=payload)
        assert response.status_code == 422

    def test_tts_unsupported_language(self, client):
        """Verify unsupported TTS language returns 400."""
        payload = {
            "text": "Hello world",
            "language": "xx"
        }
        response = client.post("/api/text-to-speech", json=payload)
        assert response.status_code == 400
        result = response.json()
        assert result["error"] == "unsupported_language"
