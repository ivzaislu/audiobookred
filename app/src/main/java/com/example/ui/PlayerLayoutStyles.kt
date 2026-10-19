package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.example.ui.theme.AbredAdaptive
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.PlayerDesign

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
    val tiny = maxHeight < AbredAdaptive.PlayerTinyHeight
    val compact = maxHeight < AbredAdaptive.PlayerCompactHeight
    val coverWidth = when {
        extraLargeText -> PlayerDesign.CoverCompactWidth
        largeText -> PlayerDesign.CoverMediumWidth
        tiny -> PlayerDesign.CoverCompactWidth
        compact -> PlayerDesign.CoverRegularWidth
        else -> PlayerDesign.CoverExpandedWidth
    }
    val sectionGap = if (tiny || largeText) {
        AbredSpacing.Xxs
    } else {
        AbredSpacing.Xs
    }
    val playSize = if (tiny && !largeText) PlayerDesign.CompactPlaySize else PlayerDesign.PlaySize
    val seekSize = when {
        extraLargeText -> PlayerDesign.ExtraLargeTextSeekSize
        largeText -> PlayerDesign.LargeTextSeekSize
        tiny -> PlayerDesign.TinySeekSize
        else -> PlayerDesign.SeekSize
    }

    return PlayerPortraitMetrics(
        tiny = tiny,
        horizontalPadding = if (tiny) AbredSpacing.Sm else AbredSpacing.Lg,
        coverWidth = coverWidth,
        coverElevation = if (tiny) AbredElevation.Raised else AbredElevation.Overlay,
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
        extraLargeText -> PlayerDesign.LandscapeCoverExtraLargeTextWidth
        largeText -> PlayerDesign.LandscapeCoverLargeTextWidth
        maxHeight < AbredAdaptive.PlayerLowLandscapeHeight -> PlayerDesign.CoverCompactWidth
        else -> PlayerDesign.CoverMediumWidth
    }
    val infoWeight = if (extraLargeText) PlayerDesign.ExtraLargeTextLandscapeInfoWeight else PlayerDesign.LandscapeInfoWeight
    val overlayReserve = when {
        !hasTransientOverlay -> AbredSpacing.None
        extraLargeText -> PlayerDesign.OverlayExtraLargeTextReserve
        largeText -> PlayerDesign.OverlayLargeTextReserve
        else -> PlayerDesign.OverlayReserve
    }

    return PlayerLandscapeMetrics(
        coverWidth = coverWidth,
        infoWeight = infoWeight,
        controlsWeight = 1f - infoWeight,
        overlayReserve = overlayReserve,
        playSize = PlayerDesign.CompactPlaySize,
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
            progressVerticalPadding = AbredSpacing.Tight,
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
            seriesTopPadding = AbredSpacing.Micro,
            seriesTextStyle = MaterialTheme.typography.labelMedium,
            seriesMaxLines = if (largeText) 3 else 1,
            seriesTextAlign = TextAlign.Start,
            bottomSpacing = AbredSpacing.None,
        ),
        playbackControls = PlayerPlaybackControlsStyle(
            chapterTopSpacing = metrics.sectionGap,
            chapterHorizontalPadding = AbredSpacing.Md,
            chapterVerticalPadding = AbredSpacing.Compact,
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
            coverTopSpacing = AbredSpacing.None,
            coverWidth = metrics.coverWidth,
            coverElevation = AbredElevation.Raised,
            progressTopSpacing = AbredSpacing.Xxs,
            progressSuffix = "",
            progressHorizontalPadding = AbredSpacing.Xs,
            progressVerticalPadding = AbredSpacing.Micro,
            progressTextStyle = MaterialTheme.typography.labelSmall,
            titleTopSpacing = AbredSpacing.Xxs,
            titleTextStyle = MaterialTheme.typography.titleMedium,
            titleMaxLines = when {
                extraLargeText -> Int.MAX_VALUE
                largeText -> 4
                else -> 2
            },
            peopleTopSpacing = AbredSpacing.None,
            peopleTiny = true,
            seriesTopPadding = AbredSpacing.None,
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
            chapterTopSpacing = AbredSpacing.None,
            chapterHorizontalPadding = AbredSpacing.Sm,
            chapterVerticalPadding = AbredSpacing.Xxs,
            chapterMaxLines = when {
                extraLargeText -> Int.MAX_VALUE
                largeText -> 3
                else -> 1
            },
            timelineTopSpacing = AbredSpacing.None,
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

