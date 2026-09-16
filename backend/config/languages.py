"""Central language configuration and capability matrix for iTantra.

iTantra target languages:
1. Hindi (hi)
2. Gujarati (gu)
3. Marathi (mr)
4. Kannada (kn)
5. Malayalam (ml)
6. Tamil (ta)
7. Telugu (te)
8. Odia (or)
9. Bengali (bn)
10. English (en)

Model Support:
- IndicConformer STT (ai4bharat/indic-conformer-600m-multilingual) natively supports 22 Indian scheduled languages:
  ['as', 'bn', 'brx', 'doi', 'gu', 'hi', 'kn', 'kok', 'ks', 'mai', 'ml', 'mni', 'mr', 'ne', 'or', 'pa', 'sa', 'sat', 'sd', 'ta', 'te', 'ur']
  Notice: English ('en') is NOT in IndicConformer's vocabulary/masks.
- IndicF5 TTS (ai4bharat/IndicF5) natively supports Indian scripts as well as Latin/English in its vocabulary.
"""

from typing import Dict, List, Any

# Primary target 10 languages
SUPPORTED_LANGUAGES: Dict[str, str] = {
    "hi": "Hindi",
    "gu": "Gujarati",
    "mr": "Marathi",
    "kn": "Kannada",
    "ml": "Malayalam",
    "ta": "Tamil",
    "te": "Telugu",
    "or": "Odia",
    "bn": "Bengali",
    "en": "English",
}

LANGUAGE_DETAILS: Dict[str, Dict[str, Any]] = {
    "hi": {"name": "Hindi", "native": "हिन्दी", "script": "Devanagari"},
    "gu": {"name": "Gujarati", "native": "ગુજરાતી", "script": "Gujarati"},
    "mr": {"name": "Marathi", "native": "मराठी", "script": "Devanagari"},
    "kn": {"name": "Kannada", "native": "ಕನ್ನಡ", "script": "Kannada"},
    "ml": {"name": "Malayalam", "native": "മലയാളം", "script": "Malayalam"},
    "ta": {"name": "Tamil", "native": "தமிழ்", "script": "Tamil"},
    "te": {"name": "Telugu", "native": "తెలుగు", "script": "Telugu"},
    "or": {"name": "Odia", "native": "ଓଡ଼ିଆ", "script": "Odia"},
    "bn": {"name": "Bengali", "native": "বাংলা", "script": "Bengali"},
    "en": {"name": "English", "native": "English", "script": "Latin"},
}

# STT-supported languages by the installed IndicConformer model
STT_SUPPORTED_LANGUAGES = frozenset([
    "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"
])

# TTS-supported languages by the installed IndicF5 model
TTS_SUPPORTED_LANGUAGES = frozenset([
    "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"
])

# Full union of all recognized system languages
ALL_LANGUAGES = frozenset(SUPPORTED_LANGUAGES.keys())


def is_valid_language(code: str) -> bool:
    """Check if language code is recognized by the iTantra system."""
    if not code or not isinstance(code, str):
        return False
    return code.strip().lower() in ALL_LANGUAGES


def is_stt_supported(code: str) -> bool:
    """Check if the language is actively supported by the installed STT model."""
    if not code or not isinstance(code, str):
        return False
    return code.strip().lower() in STT_SUPPORTED_LANGUAGES


def is_tts_supported(code: str) -> bool:
    """Check if the language is actively supported by the installed TTS model."""
    if not code or not isinstance(code, str):
        return False
    return code.strip().lower() in TTS_SUPPORTED_LANGUAGES


def get_languages_response() -> List[Dict[str, Any]]:
    """Return formatted list of verified languages and their component capabilities."""
    response = []
    for code, name in SUPPORTED_LANGUAGES.items():
        details = LANGUAGE_DETAILS.get(code, {})
        response.append({
            "code": code,
            "name": name,
            "native_name": details.get("native", name),
            "stt_supported": code in STT_SUPPORTED_LANGUAGES,
            "tts_supported": code in TTS_SUPPORTED_LANGUAGES,
            "stt_model": "IndicConformer-600M" if code in STT_SUPPORTED_LANGUAGES else None,
            "tts_model": "IndicF5" if code in TTS_SUPPORTED_LANGUAGES else None,
        })
    return response
