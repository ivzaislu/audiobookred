package com.example.data.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.google.common.collect.ImmutableList

@OptIn(UnstableApi::class)
internal fun audiobookSeekBackIcon(seconds: Int): Int = when (seconds) {
    5 -> CommandButton.ICON_SKIP_BACK_5
    10 -> CommandButton.ICON_SKIP_BACK_10
    15 -> CommandButton.ICON_SKIP_BACK_15
    30 -> CommandButton.ICON_SKIP_BACK_30
    else -> CommandButton.ICON_SKIP_BACK
}

@OptIn(UnstableApi::class)
internal fun audiobookSeekForwardIcon(seconds: Int): Int = when (seconds) {
    5 -> CommandButton.ICON_SKIP_FORWARD_5
    10 -> CommandButton.ICON_SKIP_FORWARD_10
    15 -> CommandButton.ICON_SKIP_FORWARD_15
    30 -> CommandButton.ICON_SKIP_FORWARD_30
    else -> CommandButton.ICON_SKIP_FORWARD
}

/**
 * Media-session button preferences for an audiobook player.
 *
 * The commands remain the standard Media3 seek commands, so Android Auto,
 * system UI and other controllers use ExoPlayer's configured seek increments.
 * Previous/next media-item commands remain available to hardware controllers;
 * these preferences only ask visual surfaces to put seek-back/seek-forward in
 * the primary navigation slots instead of chapter previous/next.
 */
@OptIn(UnstableApi::class)
internal fun audiobookMediaButtonPreferences(
    rewindSeconds: Int,
    forwardSeconds: Int,
): ImmutableList<CommandButton> = ImmutableList.of(
    CommandButton.Builder(audiobookSeekBackIcon(rewindSeconds))
        .setDisplayName("Назад $rewindSeconds сек")
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setSlots(CommandButton.SLOT_BACK)
        .build(),
    CommandButton.Builder(audiobookSeekForwardIcon(forwardSeconds))
        .setDisplayName("Вперёд $forwardSeconds сек")
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setSlots(CommandButton.SLOT_FORWARD)
        .build(),
)
