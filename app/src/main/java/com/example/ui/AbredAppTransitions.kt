package com.example.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.example.ui.theme.AbredMotion

internal fun appTabTransition(): ContentTransform =
    fadeIn(
        animationSpec = tween(durationMillis = AbredMotion.FadeInDurationMillis),
    ) togetherWith fadeOut(
        animationSpec = tween(durationMillis = AbredMotion.FadeOutDurationMillis),
    )

internal fun appForwardTransition(): ContentTransform =
    (slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth / AbredMotion.EnterSlideDivisor },
        animationSpec = tween(durationMillis = AbredMotion.NavigationDurationMillis),
    ) + fadeIn(
        animationSpec = tween(durationMillis = AbredMotion.FadeInDurationMillis),
    )) togetherWith
        (slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / AbredMotion.ExitSlideDivisor },
            animationSpec = tween(durationMillis = AbredMotion.NavigationDurationMillis),
        ) + fadeOut(
            animationSpec = tween(durationMillis = AbredMotion.FadeOutDurationMillis),
        ))

internal fun appBackTransition(): ContentTransform =
    (slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / AbredMotion.ExitSlideDivisor },
        animationSpec = tween(durationMillis = AbredMotion.NavigationDurationMillis),
    ) + fadeIn(
        animationSpec = tween(durationMillis = AbredMotion.FadeInDurationMillis),
    )) togetherWith
        (slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth / AbredMotion.EnterSlideDivisor },
            animationSpec = tween(durationMillis = AbredMotion.NavigationDurationMillis),
        ) + fadeOut(
            animationSpec = tween(durationMillis = AbredMotion.FadeOutDurationMillis),
        ))


