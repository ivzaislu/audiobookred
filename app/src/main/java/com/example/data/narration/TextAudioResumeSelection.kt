package com.example.data.narration

/** Null selects live narration. A reader position is deliberately not an input. */
internal fun textAudioResumeSource(
    liveSavedAtMs: Long?,
    savedSources: List<Pair<String, Long?>>,
): String? {
    val latest = savedSources.filter { it.second != null }.maxByOrNull { it.second!! }
    return when {
        latest != null && (liveSavedAtMs == null || latest.second!! >= liveSavedAtMs) -> latest.first
        liveSavedAtMs != null -> null
        else -> savedSources.firstOrNull()?.first
    }
}
