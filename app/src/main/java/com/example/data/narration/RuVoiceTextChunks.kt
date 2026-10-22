package com.example.data.narration

/**
 * Split an unusually long audiobook fragment into bounded Android TTS requests.
 * Prefer whole sentences, then whitespace. RuVoice still receives ordinary text:
 * ABRed never annotates stress, adds syllables or changes word spelling.
 */
internal object RuVoiceTextChunks {
    const val MAX_CHARS = 300
    private const val MIN_CHARS = 100
    private val sentenceEnds = setOf('.', '!', '?', ';', '…', ':')
    
    fun split(text: String): List<String> {
        if (text.length <= MAX_CHARS) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + MAX_CHARS, text.length)
            if (end < text.length) {
                val minimum = minOf(start + MIN_CHARS, end)
                val afterSentence = (end - 1 downTo minimum).firstOrNull { position ->
                    text[position] in sentenceEnds &&
                        (position + 1 == text.length || text[position + 1].isWhitespace())
                }?.plus(1)
                val afterWord = (end downTo minimum).firstOrNull { position ->
                    text[position - 1].isWhitespace()
                }
                end = afterSentence ?: afterWord ?: end
                // A very long unbroken token may contain an emoji or a
                // supplementary-plane letter. Never split UTF-16 surrogate pairs.
                if (end < text.length && Character.isHighSurrogate(text[end - 1]) &&
                    Character.isLowSurrogate(text[end])
                ) end--
            }
            val chunk = text.substring(start, end)
            if (chunk.isNotBlank()) parts += chunk
            start = end
        }
        return parts
    }
}
