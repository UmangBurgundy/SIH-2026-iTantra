package org.itantra.speech.tts

/**
 * Modular Indic & English MMS-TTS backends powered by [MmsTTSBackend].
 *
 * Each language inherits the full Meta MMS-TTS VITS inference pipeline.
 * When a language pack is installed in persistent storage, passing `modelDir`
 * enables offline on-device speech synthesis.
 */

class GujaratiMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Gujarati (vits-guj)", "gu")
class MarathiMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Marathi (vits-mar)", "mr")
class KannadaMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Kannada (vits-kan)", "kn")
class MalayalamMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Malayalam (vits-mal)", "ml")
class TamilMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Tamil (vits-tam)", "ta")
class TeluguMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Telugu (vits-tel)", "te")
class OdiaMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Odia (vits-ory)", "or")
class BengaliMmsTTSBackend : MmsTTSBackend("Meta MMS-TTS Bengali (vits-ben)", "bn")
class EnglishTTSBackend : MmsTTSBackend("Meta MMS-TTS English (vits-eng)", "en")
