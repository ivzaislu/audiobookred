package com.example.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween

internal fun appTabTransition(): ContentTransform =
    fadeIn(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_IN_DURATION_MS),
    ) togetherWith fadeOut(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_OUT_DURATION_MS),
    )

internal fun appForwardTransition(): ContentTransform =
    (slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth / 5 },
        animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
    ) + fadeIn(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_IN_DURATION_MS),
    )) togetherWith
        (slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / 10 },
            animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
        ) + fadeOut(
            animationSpec = tween(durationMillis = NAVIGATION_FADE_OUT_DURATION_MS),
        ))

internal fun appBackTransition(): ContentTransform =
    (slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 10 },
        animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
    ) + fadeIn(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_IN_DURATION_MS),
    )) togetherWith
        (slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth / 5 },
            animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
        ) + fadeOut(
            animationSpec = tween(durationMillis = NAVIGATION_FADE_OUT_DURATION_MS),
        ))

private const val NAVIGATION_MOTION_DURATION_MS = 240
private const val NAVIGATION_FADE_IN_DURATION_MS = 180
private const val NAVIGATION_FADE_OUT_DURATION_MS = 140
