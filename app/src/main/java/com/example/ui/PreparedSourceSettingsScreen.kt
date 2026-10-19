package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.source.StandaloneSourceRegistry
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.SettingsDesign
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
        verticalArrangement = Arrangement.spacedBy(SettingsDesign.SourceRowSpacing),
    ) {
        item {
            AbredSubpageHeader(
                title = "Источники",
                onBack = onBack,
            )
        }

        item {
            Text(
                text = "Выберите источники для каталога и общего поиска",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = AbredSpacing.Xs),
            )
        }

        StandaloneSourceRegistry.activeSources.forEach { source ->
            item(key = source.code) {
                val checked = source.code in enabled
                AbredSettingsSwitchRow(
                    title = source.displayName,
                    subtitle = if (checked && enabled.size == 1) {
                        "Последний включённый источник нельзя отключить."
                    } else {
                        null
                    },
                    trailingControl = true,
                    verticalPadding = SettingsDesign.SourceRowVerticalPadding,
                    checked = checked,
                    onCheckedChange = { value -> vm.setEnabled(source.code, value) },
                )
            }
        }

        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}

