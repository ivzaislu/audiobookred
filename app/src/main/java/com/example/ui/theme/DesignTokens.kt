package com.example.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Shared layout values for the app UI.
 *
 * Screen-level spacing and common control sizes live here so feature screens do not
 * drift into their own 10/12/13/14/16 dp systems. Shape values intentionally stay in
 * MaterialTheme.shapes because they are part of the active theme.
 */
object AbredSpacing {
    val Xxs = 4.dp
    val Xs = 8.dp
    val Sm = 12.dp
    val Md = 16.dp
    val Lg = 20.dp
    val Xl = 24.dp
    val Xxl = 32.dp

    val ScreenHorizontal = Md
    val ScreenVertical = Md
    val SectionHeaderTop = Lg
    val SectionHeaderBottom = Xs
}

object AbredSizes {
    val MinimumTouchTarget = 48.dp
    val ControlHeight = 48.dp
    val SmallControlHeight = 40.dp
    val IconSmall = 18.dp
    val Icon = 24.dp
    val ProgressTrack = 4.dp
    val MiniPlayerProgressTrack = 3.dp

    val RootHeaderHeight = 88.dp
    val RootHeaderBrandWidth = 176.dp
    val RootHeaderMarkSize = 44.dp
    val RootHeaderSearchHeight = 56.dp
    val BookDetailInlineChallengeHeight = 420.dp

    val ContentMaxWidth = 720.dp
    val BottomBarContentMaxWidth = 640.dp
    val SectionIconContainer = 32.dp
    val SettingsIconContainer = 40.dp
    val EmptyStateIconContainer = 64.dp
    val PlayerOverlayBottomClearance = 78.dp
    val PlayerSheetContentMaxWidth = 640.dp

    val BookRowCoverWidth = 72.dp
    val BookRowCoverHeight = 108.dp
    val BookHeroCoverWidth = 88.dp
    val BookHeroCoverHeight = 132.dp
    val BookShelfCardWidth = 136.dp
    val BookShelfCoverHeight = 180.dp
    val BookShelfCardWidthLargeText = 160.dp
    val BookShelfCoverHeightLargeText = 216.dp
    val BookShelfCardWidthExtraLargeText = 184.dp
    val BookShelfCoverHeightExtraLargeText = 252.dp
    val BookCardSourceReserve = 24.dp
    val BookCardSourceReserveLargeText = 40.dp

    val PosterWidth = BookShelfCardWidth
    val PosterHeight = BookShelfCoverHeight
    val CompactCoverWidth = 64.dp
    val CompactCoverHeight = 94.dp
    val MiniPlayerCoverWidth = 42.dp
    val MiniPlayerCoverHeight = 56.dp
}

object AbredElevation {
    val Flat = 0.dp
    val Raised = 2.dp
    val Hero = 4.dp
}
