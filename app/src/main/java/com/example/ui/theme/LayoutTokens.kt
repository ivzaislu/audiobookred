package com.example.ui.theme

import androidx.compose.ui.unit.dp

internal object AbredAdaptive {
    const val LargeFontScale = 1.3f
    const val ExtraLargeFontScale = 1.75f
    const val MetadataStackFontScale = 1.5f
    val MetadataStackWidth = 260.dp
    val PlayerTinyHeight = 650.dp
    val PlayerCompactHeight = 760.dp
    val PlayerLowLandscapeHeight = 400.dp
}

internal object NavigationDesign {
    val ExtraLargeTextHeight = 112.dp
    val LargeTextHeight = 96.dp
    val Height = 80.dp
}

internal object BrandDesign {
    val WordmarkFontSize = 18.dp
    val WordmarkLineHeight = 22.dp
}

internal object SettingsDesign {
    val CompactItemSpacing = AbredSpacing.Micro
    val CompactSwitchVerticalPadding = AbredSpacing.Micro
    val HomeSwitchVerticalPadding = AbredSpacing.None
    val CompactGroupBottomPadding = AbredSpacing.None
    val InnerItemSpacing = AbredSpacing.Xxs
    val InnerRowVerticalPadding = AbredSpacing.Xxs
    val InnerChoiceVerticalPadding = AbredSpacing.Xs
    val InnerGroupTopPadding = AbredSpacing.Sm
    val InnerGroupBottomPadding = AbredSpacing.Xxs
    val SourceRowSpacing = AbredSpacing.Xxs
    val SourceRowVerticalPadding = AbredSpacing.Xxs
    val BrandHeaderHeight = AbredSizes.RootHeaderHeight - AbredSpacing.Md
    val BrandHeaderTopPadding = AbredSpacing.Md
    val ValueColumnWidth = 48.dp
    val AboutMarkSize = 52.dp
}

internal object BookDetailDesign {
    val BookmarkPlaySize = 36.dp
    val BookmarkContentInset = 60.dp
    val DownloadSpinnerSize = 19.dp
    val LegacyGenreMaxWidth = 180.dp
    val SourceMenuMaxWidth = 260.dp
}

internal object CatalogDesign {
    val BrandHeaderHeight = AbredSizes.RootHeaderHeight - AbredSpacing.Xs
    val BrandHeaderTopPadding = AbredSpacing.Xs
    val SearchBottomSpacing = AbredSpacing.Sm
    const val SourceFilterWeight = 0.46f
    const val GenreFilterWeight = 0.54f
    val FaviconBadgeSize = 22.dp
    val FaviconSize = 16.dp
    val GenreMenuMinWidth = 210.dp
    val GenreMenuMaxWidth = 270.dp
    val GenreDialogMinWidth = 240.dp
    val GenreDialogMaxWidth = 300.dp
    val GenreDialogMaxHeight = 360.dp
    val SeriesMenuMaxWidth = 240.dp
    val UnavailableSeriesHeight = 160.dp
}

internal object LibraryDesign {
    val BookmarkCoverWidth = 58.dp
    val BookmarkCoverHeight = 82.dp
    val DeviceCoverWidth = 52.dp
    val DeviceCoverHeight = 78.dp
    val SeriesCoverWidth = 68.dp
    val SeriesCoverHeight = 100.dp
}

internal object HomeDesign {
    val PeriodSelectorMinHeight = AbredSizes.SmallControlHeight
    val PeriodSelectorHorizontalPadding = AbredSpacing.Xs
    val PeriodSelectorVerticalPadding = AbredSpacing.Xxs
    val SectionHeaderTopPadding = AbredSpacing.Sm
    const val PlaceholderTitleWidthFraction = 0.88f
    const val PlaceholderAuthorWidthFraction = 0.72f
    const val PlaceholderNarratorWidthFraction = 0.66f
    const val PlaceholderDetailWidthFraction = 0.78f
    val PlaceholderTitleHeight = 16.dp
    val PlaceholderBodyHeight = 12.dp
    val RefreshSpinnerSize = 26.dp
    val SeriesFilterMaxWidth = 120.dp
    val PeriodMenuMaxWidth = 180.dp
}

internal object PlayerDesign {
    const val LandscapeInfoWeight = 0.38f
    const val ExtraLargeTextLandscapeInfoWeight = 0.32f
    const val LandscapeOverlayWidthFraction = 0.58f
    const val SheetHeightFraction = 0.90f
    const val LargeTextSheetHeightFraction = 0.84f
    const val ExtraLargeTextSheetHeightFraction = 0.78f
    const val ListSheetHeightFraction = 0.82f
    val OverlayReserve = 64.dp
    val PreparationMaxWidth = 360.dp
    val ChapterPlaySize = 40.dp
    val EmptyStateHeight = 360.dp
    val OverlayLargeTextReserve = 88.dp
    val CoverCompactWidth = 112.dp
    val CoverMediumWidth = 128.dp
    val CoverRegularWidth = 144.dp
    val CoverExpandedWidth = 176.dp
    val CompactPlaySize = 64.dp
    val PlaySize = 72.dp
    val ExtraLargeTextSeekSize = 60.dp
    val LargeTextSeekSize = 56.dp
    val TinySeekSize = 48.dp
    val SeekSize = 52.dp
    val LandscapeCoverExtraLargeTextWidth = 88.dp
    val LandscapeCoverLargeTextWidth = 104.dp
    val OverlayExtraLargeTextReserve = 120.dp
    val LargeTextActionMinHeight = 54.dp
    val QuickActionIconSize = 23.dp
    val CompactPlayIconSize = 34.dp
    val PlayIconSize = 40.dp
}

internal object CoverDesign {
    const val PlaceholderIconFraction = 0.34f
}
