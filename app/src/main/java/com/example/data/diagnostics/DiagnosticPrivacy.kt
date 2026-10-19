package com.example.data.diagnostics

/** An allowlist, not a best-effort redaction of logcat or exception messages. */
internal object DiagnosticPrivacy {
    private val events = setOf(
        "application_started", "recording_started", "recording_stopped", "time_limit",
        "http_started", "http_redirect", "http_retry_response", "parser_started", "parser_complete", "parser_failed", "parser_cancelled",
        "challenge_required", "challenge_wait", "challenge_wait_complete", "challenge_completed", "challenge_cancelled",
        "preparation_requested", "preparation_complete", "preparation_failed", "preparation_cancelled",
        "source_recovery_failed", "load_failed", "load_discarded", "torrserve_stage",
        "settings_changed", "app_seek", "app_seek_by", "app_next", "app_previous", "app_undo_seek", "completion_decision",
        "http_complete", "http_failed", "is_playing", "play_when_ready", "playback_state",
        "media_transition", "repeat_mode", "shuffle_mode", "suppression", "player_error",
        "timeline", "position_discontinuity", "service_created", "service_destroyed",
        "controller_commands", "session_resumption", "app_pause", "app_toggle",
        "load_requested", "load_applied", "source_recovery_discarded", "source_recovery_requested",
        "source_recovery_applied", "guarded_resumption_requested", "guarded_resumption_discarded",
        "guarded_resumption_applied", "guarded_resumption_failed",
    )
    private val numericFields = setOf(
        "state", "ready", "playing", "suppression", "repeat", "shuffle", "chapter", "count",
        "position", "duration", "value", "reason", "code", "generation", "targetChapter",
        "targetPosition", "autoPlay", "manual", "currentChapter", "currentPosition", "forPlayback",
        "elapsedMs", "status", "requestId", "operationId", "resultCount", "resultChapters", "hop", "redirects", "followups", "durationKnown",
        "retryAfterSeconds", "challengeId", "resolved", "cloudflare", "challengeHint", "cached",
        "sentCookie", "sentAuthorization", "sentReferer", "rangeRequest", "requestHeadersObserved",
        "deltaMs", "hasNext", "targetCount", "queueLast", "autoNext", "smartRewind",
    )
    private val sources = setOf(
        "audiopolka", "uknig", "audioboo", "knigavuhe", "bazaknig", "myaudiobooks",
        "audioknigalife", "rutracker", "other",
    )
    private val scalar = Regex("(?:-?[0-9]{1,19}|true|false)")
    private val position = Regex("-?[0-9]{1,10}:-?[0-9]{1,19}")
    private val symbol = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,199}")

    fun event(name: String, fields: Map<String, String>): String? {
        if (name !in events) return null
        return buildString {
            append("event=").append(name)
            fields.toSortedMap().forEach { (key, value) ->
                val accepted = when {
                    key in numericFields -> scalar.matches(value)
                    key == "from" || key == "to" -> position.matches(value)
                    key == "kind" -> value in setOf("timeout", "dns", "tls", "connect", "io", "cancelled",
                        "rate_limited", "authentication", "preview_only", "unavailable", "invalid_data", "invalid_state", "other")
                    key == "source" || key == "targetSource" -> value in sources
                    key == "sourceEvidence" -> value in setOf("operation", "transport", "destination", "origin", "unknown")
                    key == "operation" -> value in setOf("genres", "catalog", "search", "book", "author_books", "narrator_books",
                        "genre_books", "series", "home", "playlist", "cover", "playback_stream", "torrserve_api", "unattributed")
                    key == "route" -> value in setOf("page", "book_page", "search", "audio", "image", "playlist", "challenge", "login", "torrserve_api")
                    key == "target" -> value in setOf("provider_site", "redirectto_cdn", "fastpic_image", "torrserve", "external")
                    key == "format" -> value in setOf("html", "json", "audio", "image", "playlist", "text", "other", "missing")
                    key == "hostKey" || key == "resourceKey" || key == "targetResourceKey" -> Regex("[0-9a-f]{16}").matches(value)
                    key == "stateName" -> value in setOf("idle", "buffering", "ready", "ended", "unknown")
                    key == "reasonName" -> value in setOf("auto_transition", "seek", "seek_adjustment", "skip", "remove", "internal",
                        "repeat", "playlist_changed", "source_update", "user_request", "audio_focus_loss", "audio_becoming_noisy",
                        "remote", "end_of_item", "suppressed_too_long", "none", "transient_focus_loss", "unsuitable_output", "unknown")
                    key == "errorName" -> Regex("ERROR_CODE_[A-Z0-9_]{1,80}").matches(value)
                    key == "stage" -> value in setOf("media_items", "source_refresh", "connecting", "waiting_for_metadata", "buffering", "ready_to_play")
                    key == "method" -> value in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OTHER")
                    else -> false
                }
                if (accepted) append(' ').append(key).append('=').append(value)
            }
        }
    }

    /** Class/method/line only: Throwable.message, toString, filenames and thread names are excluded. */
    fun crash(error: Throwable): String = buildString {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var causes = 0
        while (current != null && causes++ < 8 && seen.add(current)) {
            val item = current
            append("exception=").append(safeSymbol(item.javaClass.name)).append('\n')
            item.stackTrace.take(64).forEach { frame ->
                append("  at ").append(safeSymbol(frame.className)).append('.')
                    .append(safeSymbol(frame.methodName)).append(':').append(frame.lineNumber).append('\n')
            }
            current = item.cause
        }
    }.take(64 * 1024)

    private fun safeSymbol(value: String): String = if (symbol.matches(value)) value else "omitted"
}
