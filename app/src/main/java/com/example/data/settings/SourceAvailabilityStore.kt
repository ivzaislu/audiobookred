package com.example.data.settings

import android.content.Context
import com.example.data.source.StandaloneSourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-local source availability.
 *
 * All sources are enabled by default. At least one source always remains enabled
 * so Catalog never ends up without a selectable provider.
 */
class SourceAvailabilityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val allCodes: Set<String>
        get() = StandaloneSourceRegistry.activeSources.mapTo(linkedSetOf()) { it.code }

    private val _enabled = MutableStateFlow(read())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    fun isEnabled(sourceCode: String): Boolean =
        StandaloneSourceRegistry.normalize(sourceCode) in _enabled.value

    fun setEnabled(sourceCode: String, enabled: Boolean) {
        val code = StandaloneSourceRegistry.normalize(sourceCode)
        if (!StandaloneSourceRegistry.isActive(code)) return

        val current = _enabled.value
        val next = if (enabled) {
            current + code
        } else {
            if (current.size <= 1 && code in current) return
            current - code
        }
        if (next == current) return
        write(next)
        _enabled.value = next
    }

    fun reset() {
        val next = allCodes
        prefs.edit().remove(KEY_DISABLED).apply()
        _enabled.value = next
    }

    internal fun snapshot(): Set<String> = _enabled.value

    internal fun replaceEnabled(values: Set<String>): Boolean {
        val known = allCodes
        val normalized = values
            .mapTo(linkedSetOf(), StandaloneSourceRegistry::normalize)
            .filterTo(linkedSetOf()) { it in known }
        val next = normalized.takeIf { it.isNotEmpty() } ?: known
        val committed = prefs.edit()
            .putStringSet(KEY_DISABLED, known - next)
            .commit()
        if (committed) _enabled.value = next
        return committed
    }

    private fun read(): Set<String> {
        val known = allCodes
        val storedDisabled = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty()
            .mapTo(linkedSetOf(), StandaloneSourceRegistry::normalize)
            .filterTo(linkedSetOf()) { it in known }
        val enabled = known - storedDisabled
        return enabled.takeIf { it.isNotEmpty() } ?: known
    }

    private fun write(enabled: Set<String>) {
        val disabled = allCodes - enabled
        prefs.edit().putStringSet(KEY_DISABLED, disabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "audiobookred_source_availability"
        const val KEY_DISABLED = "disabled_sources"
    }
}
