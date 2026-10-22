package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredCardColors
import com.example.ui.theme.abredFlatCardElevation
import com.example.ui.theme.abredOutlineBorder

@Composable
internal fun BookDetailActionsCard(content: @Composable ColumnScope.() -> Unit) {
    BookDetailSectionCard(modifier = Modifier.padding(top = AbredSpacing.Sm), content = content)
}

@Composable
internal fun BookDetailSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AbredSpacing.Md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal),
        shape = MaterialTheme.shapes.large,
        colors = abredCardColors(),
        elevation = abredFlatCardElevation(),
        border = abredOutlineBorder(opacity = AbredOpacity.SectionOutline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}


