package com.example.ui.viewmodel

enum class BrowseKind { Author, Narrator, Genre, Search, Source }

data class BrowseTarget(
    val kind: BrowseKind,
    val id: String,
    val name: String,
    val excludeSource: String? = null,
    val seriesName: String? = null,
)

/** Browse owns only the navigation target. Book rows are owned by Paging 3. */
data class BrowseUiState(val target: BrowseTarget)
