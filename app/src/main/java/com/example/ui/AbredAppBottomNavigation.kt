package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSizes

private data class AppBottomNavigationItem(
    val destination: TopLevelDestination,
    val label: String,
    val icon: ImageVector,
)

private val AppBottomNavigationItems = listOf(
    AppBottomNavigationItem(TopLevelDestination.Home, "Главная", Icons.Default.Home),
    AppBottomNavigationItem(TopLevelDestination.Catalog, "Каталог", Icons.Default.AutoStories),
    AppBottomNavigationItem(TopLevelDestination.Library, "Библиотека", Icons.Default.LibraryBooks),
    AppBottomNavigationItem(TopLevelDestination.Settings, "Настройки", Icons.Default.Settings),
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
        extraLargeText -> 112.dp
        largeText -> 96.dp
        else -> 80.dp
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showTopDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
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
                    tonalElevation = 0.dp,
                ) {
                    AppBottomNavigationItems.forEach { item ->
                        val selected = selectedDestination == item.destination
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onDestinationSelected(item.destination) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
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
                            colors = appNavigationItemColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun appNavigationItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onSurface,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
