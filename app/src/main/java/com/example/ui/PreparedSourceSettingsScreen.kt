package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.source.StandaloneSourceRegistry
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.SourceSettingsViewModel

@Composable
internal fun PreparedSourceSettingsScreen(
    onBack: () -> Unit,
) {
    val vm: SourceSettingsViewModel = hiltViewModel()
    val enabled by vm.enabledSources.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item {
            AbredSubpageHeader(
                title = "Источники",
                onBack = onBack,
            )
        }

        item { AbredSettingsSectionTitle("Доступные источники") }

        StandaloneSourceRegistry.activeSources.forEach { source ->
            item(key = source.code) {
                val checked = source.code in enabled
                AbredSettingsSwitchRow(
                    title = source.displayName,
                    subtitle = when {
                        checked && enabled.size == 1 ->
                            "Последний включённый источник нельзя отключить."
                        checked ->
                            "Используется в каталоге и общем поиске."
                        else ->
                            "Скрыт из каталога и общего поиска."
                    },
                    checked = checked,
                    onCheckedChange = { value -> vm.setEnabled(source.code, value) },
                )
            }
        }

        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}
