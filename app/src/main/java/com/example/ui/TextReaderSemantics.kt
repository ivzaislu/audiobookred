package com.example.ui

import androidx.compose.ui.semantics.SemanticsPropertyKey

/** Source fragment currently highlighted inside a paragraph; separate from its stable test tag. */
internal val TextReaderSpokenFragmentId = SemanticsPropertyKey<String>("TextReaderSpokenFragmentId")
