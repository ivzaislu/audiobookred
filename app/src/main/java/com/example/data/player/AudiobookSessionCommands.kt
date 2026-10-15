package com.example.data.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/** A book plays in chapter order once, including through system/car controllers. */
@OptIn(UnstableApi::class)
internal fun audiobookPlayerCommands(commands: Player.Commands): Player.Commands =
    commands.buildUpon()
        .remove(Player.COMMAND_SET_REPEAT_MODE)
        .remove(Player.COMMAND_SET_SHUFFLE_MODE)
        .build()

@OptIn(UnstableApi::class)
internal fun audiobookConnectionResult(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
): MediaSession.ConnectionResult {
    val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
    return builder.setAvailablePlayerCommands(
        audiobookPlayerCommands(builder.build().availablePlayerCommands)
    ).build()
}
