package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

internal data class PlayerPortraitStyle(
    val horizontalPadding: Dp,
    val bookInfo: PlayerBookInfoStyle,
    val playbackControls: PlayerPlaybackControlsStyle,
)

internal data class PlayerLandscapeStyle(
    val infoWeight: Float,
    val controlsWeight: Float,
    val overlayReserve: Dp,
    val bookInfo: PlayerBookInfoStyle,
    val playbackControls: PlayerPlaybackControlsStyle,
)

internal data class PlayerPortraitMetrics(
    val tiny: Boolean,
    val horizontalPadding: Dp,
    val coverWidth: Dp,
    val coverElevation: Dp,
    val sectionGap: Dp,
    val playSize: Dp,
    val seekSize: Dp,
)

internal data class PlayerLandscapeMetrics(
    val coverWidth: Dp,
    val infoWeight: Float,
    val controlsWeight: Float,
    val overlayReserve: Dp,
    val playSize: Dp,
    val seekSize: Dp,
)

internal fun playerPortraitMetrics(
    maxHeight: Dp,
    largeText: Boolean,
    extraLargeText: Boolean,
): PlayerPortraitMetrics {
    val tiny = maxHeight < 650.dp
    val compact = maxHeight < 760.dp
    val coverWidth = when {
        extraLargeText -> 112.dp
        largeText -> 128.dp
        tiny -> 112.dp
        compact -> 144.dp
        else -> 176.dp
    }
    val sectionGap = if (tiny || largeText) {
        AbredSpacing.Xxs
    } else {
        AbredSpacing.Xs
    }
    val playSize = if (tiny && !largeText) 64.dp else 72.dp
    val seekSize = when {
        extraLargeText -> 60.dp
        largeText -> 56.dp
        tiny -> 48.dp
        else -> 52.dp
    }

    return PlayerPortraitMetrics(
        tiny = tiny,
        horizontalPadding = if (tiny) AbredSpacing.Sm else AbredSpacing.Lg,
        coverWidth = coverWidth,
        coverElevation = if (tiny) 2.dp else 6.dp,
        sectionGap = sectionGap,
        playSize = playSize,
        seekSize = seekSize,
    )
}

internal fun playerLandscapeMetrics(
    maxHeight: Dp,
    largeText: Boolean,
    extraLargeText: Boolean,
    hasTransientOverlay: Boolean,
): PlayerLandscapeMetrics {
    val coverWidth = when {
        extraLargeText -> 88.dp
        largeText -> 104.dp
        maxHeight < 400.dp -> 112.dp
        else -> 128.dp
    }
    val infoWeight = if (extraLargeText) 0.32f else 0.38f
    val overlayReserve = when {
        !hasTransientOverlay -> 0.dp
        extraLargeText -> 120.dp
        largeText -> 88.dp
        else -> 64.dp
    }

    return PlayerLandscapeMetrics(
        coverWidth = coverWidth,
        infoWeight = infoWeight,
        controlsWeight = 1f - infoWeight,
        overlayReserve = overlayReserve,
        playSize = 64.dp,
        seekSize = AbredSizes.MinimumTouchTarget,
    )
}

@Composable
internal fun playerPortraitStyle(
    layoutState: PlayerLayoutState,
): PlayerPortraitStyle {
    val largeText = layoutState.largeText
    val extraLargeText = layoutState.extraLargeText
    val metrics = playerPortraitMetrics(
        maxHeight = layoutState.maxHeight,
        largeText = largeText,
        extraLargeText = extraLargeText,
    )
    val tiny = metrics.tiny

    return PlayerPortraitStyle(
        horizontalPadding = metrics.horizontalPadding,
        bookInfo = PlayerBookInfoStyle(
            coverTopSpacing = AbredSpacing.Xxs,
            coverWidth = metrics.coverWidth,
            coverElevation = metrics.coverElevation,
            progressTopSpacing = if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs,
            progressSuffix = " прослушано",
            progressHorizontalPadding = AbredSpacing.Sm,
            progressVerticalPadding = 3.dp,
            progressTextStyle = MaterialTheme.typography.labelMedium,
            titleTopSpacing = if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs,
            titleTextStyle = if (tiny) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.headlineSmall
            },
            titleMaxLines = when {
                extraLargeText -> 4
                largeText -> 3
                tiny -> 2
                else -> 2
            },
            peopleTopSpacing = AbredSpacing.Xxs,
            peopleTiny = tiny,
            seriesTopPadding = 2.dp,
            seriesTextStyle = MaterialTheme.typography.labelMedium,
            seriesMaxLines = if (largeText) 3 else 1,
            seriesTextAlign = TextAlign.Start,
            bottomSpacing = 0.dp,
        ),
        playbackControls = PlayerPlaybackControlsStyle(
            chapterTopSpacing = metrics.sectionGap,
            chapterHorizontalPadding = AbredSpacing.Md,
            chapterVerticalPadding = 6.dp,
            chapterMaxLines = if (largeText) 2 else 1,
            timelineTopSpacing = if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs,
            transportTopSpacing = if (tiny || largeText) AbredSpacing.Xxs else AbredSpacing.Xs,
            quickActionsTopSpacing = if (tiny || largeText) AbredSpacing.Xs else AbredSpacing.Sm,
            bottomSpacing = AbredSpacing.Sm,
            playSize = metrics.playSize,
            seekSize = metrics.seekSize,
            compactIcons = tiny,
            stackedQuickActions = false,
        ),
    )
}

@Composable
internal fun playerLandscapeStyle(
    layoutState: PlayerLayoutState,
    hasTransientOverlay: Boolean,
): PlayerLandscapeStyle {
    val largeText = layoutState.largeText
    val extraLargeText = layoutState.extraLargeText
    val metrics = playerLandscapeMetrics(
        maxHeight = layoutState.maxHeight,
        largeText = largeText,
        extraLargeText = extraLargeText,
        hasTransientOverlay = hasTransientOverlay,
    )

    return PlayerLandscapeStyle(
        infoWeight = metrics.infoWeight,
        controlsWeight = metrics.controlsWeight,
        overlayReserve = metrics.overlayReserve,
        bookInfo = PlayerBookInfoStyle(
            coverTopSpacing = 0.dp,
            coverWidth = metrics.coverWidth,
            coverElevation = 2.dp,
            progressTopSpacing = AbredSpacing.Xxs,
            progressSuffix = "",
            progressHorizontalPadding = AbredSpacing.Xs,
            progressVerticalPadding = 2.dp,
            progressTextStyle = MaterialTheme.typography.labelSmall,
            titleTopSpacing = AbredSpacing.Xxs,
            titleTextStyle = MaterialTheme.typography.titleMedium,
            titleMaxLines = when {
                extraLargeText -> Int.MAX_VALUE
                largeText -> 4
                else -> 2
            },
            peopleTopSpacing = 0.dp,
            peopleTiny = true,
            seriesTopPadding = 0.dp,
            seriesTextStyle = MaterialTheme.typography.labelSmall,
            seriesMaxLines = when {
                extraLargeText -> Int.MAX_VALUE
                largeText -> 3
                else -> 1
            },
            seriesTextAlign = TextAlign.Center,
            bottomSpacing = AbredSpacing.Xxs,
        ),
        playbackControls = PlayerPlaybackControlsStyle(
            chapterTopSpacing = 0.dp,
            chapterHorizontalPadding = AbredSpacing.Sm,
            chapterVerticalPadding = AbredSpacing.Xxs,
            chapterMaxLines = when {
                extraLargeText -> Int.MAX_VALUE
                largeText -> 3
                else -> 1
            },
            timelineTopSpacing = 0.dp,
            transportTopSpacing = AbredSpacing.Xxs,
            quickActionsTopSpacing = AbredSpacing.Xxs,
            bottomSpacing = AbredSpacing.Xs,
            playSize = metrics.playSize,
            seekSize = metrics.seekSize,
            compactIcons = true,
            stackedQuickActions = extraLargeText,
        ),
    )
}
