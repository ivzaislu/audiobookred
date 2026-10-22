package com.example.data.narration

/**
 * Android TextToSpeech input validation shared by RuVoice narration, preview tasks
 * and exported audiobook chapters. No local accentuation or acoustic model is used.
 */
internal object NarrationInput {
    const val MAX_TEXT_LENGTH = 1_000

    fun prepare(text: String): String {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").trim()
        require(normalized.isNotBlank()) { "Нет текста для озвучки." }
        require(normalized.length <= MAX_TEXT_LENGTH) {
            "Озвучиваемый фрагмент слишком длинный: максимум $MAX_TEXT_LENGTH символов."
        }
        require(normalized.none { it.isISOControl() && it != '\n' && it != '\t' }) {
            "В тексте есть неподдерживаемые управляющие символы."
        }
        return normalized
    }
}
