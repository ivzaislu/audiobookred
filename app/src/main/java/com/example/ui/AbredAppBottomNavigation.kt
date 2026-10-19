package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.NavigationDesign
import com.example.ui.theme.abredNavigationItemColors

private data class AppBottomNavigationItem(
    val destination: TopLevelDestination,
    val label: String,
    val iconRes: Int,
)

private val AppBottomNavigationItems = listOf(
    AppBottomNavigationItem(TopLevelDestination.Home, "Главная", R.drawable.ic_abred_nav_home),
    AppBottomNavigationItem(TopLevelDestination.Catalog, "Каталог", R.drawable.ic_abred_nav_catalog),
    AppBottomNavigationItem(TopLevelDestination.Library, "Библиотека", R.drawable.ic_abred_nav_library),
    AppBottomNavigationItem(TopLevelDestination.Settings, "Настройки", R.drawable.ic_abred_nav_settings),
)

@Composable
internal fun AppBottomNavigation(
    selectedDestination: TopLevelDestination,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    showTopDivider: Boolean,
) {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val navigationMinHeight = when {
        extraLargeText -> NavigationDesign.ExtraLargeTextHeight
        largeText -> NavigationDesign.LargeTextHeight
        else -> NavigationDesign.Height
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showTopDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AbredOpacity.SectionOutline),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = AbredElevation.Flat,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                NavigationBar(
                    modifier = Modifier
                        .widthIn(max = AbredSizes.BottomBarContentMaxWidth)
                        .fillMaxWidth()
                        .heightIn(min = navigationMinHeight),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = AbredElevation.Flat,
                ) {
                    AppBottomNavigationItems.forEach { item ->
                        val selected = selectedDestination == item.destination
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onDestinationSelected(item.destination) },
                            icon = {
                                Icon(
                                    painter = painterResource(item.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(AbredSizes.Icon),
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    style = if (largeText) {
                                        MaterialTheme.typography.labelSmall
                                    } else {
                                        MaterialTheme.typography.labelMedium
                                    },
                                    maxLines = if (largeText) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            },
                            alwaysShowLabel = true,
                            colors = abredNavigationItemColors(),
                        )
                    }
                }
            }
        }
    }
}

