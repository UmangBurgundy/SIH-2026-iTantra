package org.itantra.speech.tts

import org.json.JSONObject
import java.io.InputStream
import java.text.Normalizer

/**
 * Text normalizer and character tokenizer for Meta MMS-TTS Hindi VITS model.
 *
 * Handles:
 * 1. Unicode NFC canonical decomposition/composition
 * 2. Hindi number expansion (digits 0-99 to spoken Devanagari words)
 * 3. Punctuation mapping (danda '।', dots, commas mapped to pause spaces)
 * 4. Character-level token ID lookup from vocab.json
 * 5. VITS blank token interleaving (pad_token = 0 between each phoneme/character)
 */
class HindiTextNormalizer(private val vocab: Map<String, Long>) {

    companion object {
        private const val BLANK_TOKEN_ID = 0L

        private val DIGIT_WORDS = mapOf(
            '0' to "शून्य",
            '1' to "एक",
            '2' to "दो",
            '3' to "तीन",
            '4' to "चार",
            '5' to "पाँच",
            '6' to "छह",
            '7' to "सात",
            '8' to "आठ",
            '9' to "नौ"
        )

        private val TWO_DIGIT_WORDS = mapOf(
            10 to "दस", 11 to "ग्यारह", 12 to "बारह", 13 to "तेरह", 14 to "चौदह",
            15 to "पंद्रह", 16 to "सोलह", 17 to "सत्रह", 18 to "अठारह", 19 to "उन्नीस",
            20 to "बीस", 21 to "इक्कीस", 22 to "बाईस", 23 to "तेईस", 24 to "चौबीस",
            25 to "पच्चीस", 26 to "छब्बीस", 27 to "सत्ताईस", 28 to "अट्ठाईस", 29 to "उनतीस",
            30 to "तीस", 40 to "चालीस", 50 to "पचास", 60 to "साठ", 70 to "सत्तर",
            80 to "अस्सी", 90 to "नब्बे", 100 to "सौ"
        )

        /**
         * Expands ASCII digits (0-99) in text to Hindi words.
         */
        fun expandDigits(text: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch.isDigit()) {
                    // Check for 2-digit numbers
                    if (i + 1 < text.length && text[i + 1].isDigit()) {
                        val num = text.substring(i, i + 2).toIntOrNull()
                        if (num != null && TWO_DIGIT_WORDS.containsKey(num)) {
                            sb.append(TWO_DIGIT_WORDS[num]).append(" ")
                            i += 2
                            continue
                        }
                    }
                    sb.append(DIGIT_WORDS[ch] ?: ch.toString()).append(" ")
                } else {
                    sb.append(ch)
                }
                i++
            }
            return sb.toString().trim()
        }

        /**
         * Normalizes Hindi text for TTS synthesis.
         */
        fun normalize(text: String): String {
            if (text.isBlank()) return ""

            // 1. Canonical Unicode normalization
            var norm = Normalizer.normalize(text, Normalizer.Form.NFC)

            // 2. Expand numbers/digits to Hindi words
            norm = expandDigits(norm)

            // 3. Replace punctuation (danda ।, commas, dots, exclamation) with spaces
            norm = norm.replace(Regex("[।॥.,!?;:\"'()\\[\\]\\-_]"), " ")

            // 4. Collapse multiple whitespace into single space
            norm = norm.replace(Regex("\\s+"), " ").trim()

            return norm
        }

        /**
         * Factory method to load vocab map from InputStream (e.g. Android assets).
         */
        fun fromInputStream(inputStream: InputStream): HindiTextNormalizer {
            val jsonStr = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonStr)
            val map = mutableMapOf<String, Long>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonObject.getLong(key)
            }
            return HindiTextNormalizer(map)
        }
    }

    /**
     * Normalizes Hindi text for TTS synthesis (instance delegate).
     */
    fun normalize(text: String): String = Companion.normalize(text)

    /**
     * Converts normalized text into token IDs with VITS blank interleaving.
     *
     * Example: "नमस्ते" -> [0, 10, 0, 71, 0, 40, 0, 53, 0, 69, 0, 52, 0]
     */
    fun tokenize(text: String): LongArray {
        val normalized = normalize(text)
        if (normalized.isBlank()) return longArrayOf()

        val tokens = mutableListOf<Long>()
        tokens.add(BLANK_TOKEN_ID)

        for (ch in normalized) {
            val key = ch.toString()
            val tokenId = vocab[key]
            if (tokenId != null) {
                tokens.add(tokenId)
                tokens.add(BLANK_TOKEN_ID)
            }
        }

        return tokens.toLongArray()
    }

    val vocabSize: Int get() = vocab.size
}
