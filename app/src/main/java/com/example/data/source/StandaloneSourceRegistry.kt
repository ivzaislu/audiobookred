package com.example.data.source

/**
 * Stable metadata shared by UI, playback identity and source-neutral repository code.
 *
 * Parser construction and provider-specific transport stay behind LiveProviderRegistry;
 * this registry intentionally contains only cross-cutting source metadata/capabilities.
 * AndroidLiveParserHub verifies that active metadata and registered providers stay aligned.
 */
internal data class StandaloneSourceDefinition(
    val code: String,
    val displayName: String,
    val supportsGenres: Boolean = true,
    val supportsSeries: Boolean = true,
    val usesPagedSearch: Boolean = false,
)

internal object StandaloneSourceRegistry {
    const val DEFAULT_SOURCE_CODE = "audiopolka"

    val activeSources: List<StandaloneSourceDefinition> = listOf(
        StandaloneSourceDefinition(
            code = "audiopolka",
            displayName = "Audiopolka",
        ),
        StandaloneSourceDefinition(
            code = "uknig",
            displayName = "уКниг",
        ),
        StandaloneSourceDefinition(
            code = "audioboo",
            displayName = "Audioboo",
        ),
        StandaloneSourceDefinition(
            code = "knigavuhe",
            displayName = "Книга в ухе",
            usesPagedSearch = true,
        ),
        StandaloneSourceDefinition(
            code = "bazaknig",
            displayName = "Baza-Knig",
        ),
        StandaloneSourceDefinition(
            code = "myaudiobooks",
            displayName = "MY-AUDIOBOOKS",
            // The dedicated /knigi-po-zhanram.html directory is parsed for the
            // complete source genre list. The seven homepage sidebar genres are
            // retained only as a fallback if that directory is temporarily unavailable.
            supportsGenres = true,
            // Book detail supplies the source-series id and the live series
            // directory confirms the same /xfsearch/series/<id>/ route. Some
            // provider series links are broken and return 404; those are handled
            // as unavailable source collections without disabling working series.
            supportsSeries = true,
        ),
        StandaloneSourceDefinition(
            code = "audioknigalife",
            displayName = "Audiokniga.Life",
            supportsGenres = true,
            supportsSeries = true,
        ),
        StandaloneSourceDefinition(
            code = "rutracker",
            displayName = "RuTracker",
            // The audiobook forum ids are fixed from the captured RuTracker
            // audiobook index supplied for this integration.
            supportsGenres = true,
            supportsSeries = false,
            usesPagedSearch = true,
        ),
    )

    init {
        val codes = activeSources.map { it.code }
        require(codes.size == codes.distinct().size) { "Duplicate standalone source code: $codes" }
    }

    private val activeCodes: Set<String> = activeSources.mapTo(linkedSetOf()) { it.code }

    private val activeByCode: Map<String, StandaloneSourceDefinition> =
        activeSources.associateBy(StandaloneSourceDefinition::code)

    fun normalize(code: String?): String = code?.trim()?.lowercase().orEmpty()

    private fun definition(code: String?): StandaloneSourceDefinition? = activeByCode[normalize(code)]

    fun isActive(code: String?): Boolean = normalize(code) in activeCodes

    fun supportsGenres(code: String?): Boolean = definition(code)?.supportsGenres == true

    fun supportsSeries(code: String?): Boolean = definition(code)?.supportsSeries == true

    fun usesPagedSearch(code: String?): Boolean = definition(code)?.usesPagedSearch == true

    fun displayNameOrNull(code: String?): String? {
        val normalized = normalize(code)
        if (normalized.isBlank()) return null
        return activeByCode[normalized]?.displayName
    }
}
