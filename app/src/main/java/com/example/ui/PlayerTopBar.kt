package com.example.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.AbredSizes

@Composable
internal fun PlayerTopBar(
    preparationActive: Boolean,
    maxLines: Int,
    overflow: TextOverflow,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AbredSizes.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
        ) {
            Icon(Icons.Default.KeyboardArrowDown, "Свернуть")
        }
        Text(
            if (preparationActive) "Подготовка воспроизведения" else "Сейчас играет",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = overflow,
        )
        Spacer(Modifier.size(AbredSizes.MinimumTouchTarget))
    }
}
