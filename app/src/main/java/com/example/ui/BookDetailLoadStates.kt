package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredShapes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredStroke

@Composable
internal fun BookDetailUnavailableState(
    error: String?,
    loading: Boolean,
    alternateQuery: String?,
    excludedSource: String?,
    onSearchOtherSources: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (error == null && loading) {
            CircularProgressIndicator()
            return@Box
        }

        Column(
            modifier = Modifier.padding(AbredSpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                error ?: "Не удалось открыть книгу",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(AbredSpacing.Sm))
            if (!alternateQuery.isNullOrBlank() && !excludedSource.isNullOrBlank()) {
                Button(
                    onClick = { onSearchOtherSources(alternateQuery, excludedSource) },
                ) {
                    Text("Поиск в других источниках")
                }
                Spacer(Modifier.height(AbredSpacing.Xs))
                OutlinedButton(onClick = onRetry) {
                    Text("Повторить")
                }
            } else {
                Button(onClick = onRetry) {
                    Text("Повторить")
                }
            }
        }
    }
}

@Composable
internal fun BookDetailLoadFooter(
    loadError: String?,
    alternateQuery: String?,
    excludedSource: String?,
    onSearchOtherSources: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    if (!alternateQuery.isNullOrBlank() && !excludedSource.isNullOrBlank()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.Md, vertical = AbredSpacing.Xs),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AbredSpacing.InsetMedium),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    loadError ?: "Доступен только ознакомительный фрагмент",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(AbredSpacing.InsetSmall))
                Button(
                    onClick = { onSearchOtherSources(alternateQuery, excludedSource) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text("Поиск в других источниках")
                }
                Spacer(Modifier.height(AbredSpacing.Xs))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    border = BorderStroke(
                        AbredStroke.Hairline,
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = AbredOpacity.ErrorOutline),
                    ),
                ) {
                    Text("Повторить")
                }
            }
        }
        return
    }

    if (loadError != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.Md, vertical = AbredSpacing.Xs),
            shape = AbredShapes.LoadError,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AbredSpacing.InsetMedium, vertical = AbredSpacing.InsetSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    loadError,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(AbredSpacing.InsetSmall))
                OutlinedButton(onClick = onRetry) {
                    Text("Повторить")
                }
            }
        }
    }
}

