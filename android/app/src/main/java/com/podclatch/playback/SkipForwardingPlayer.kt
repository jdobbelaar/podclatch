package com.podclatch.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Wraps the ExoPlayer so that the skip amounts follow the Settings live (ExoPlayer's increments are
 * fixed when it is built), and so that the lock screen, headset, and notification show no next or
 * previous (there is no Queue yet).
 */
@OptIn(markerClass = [UnstableApi::class])
class SkipForwardingPlayer(
    player: Player,
    private val skipBackMs: () -> Long,
    private val skipForwardMs: () -> Long,
) : ForwardingPlayer(player) {

    override fun getSeekBackIncrement(): Long = skipBackMs()

    override fun getSeekForwardIncrement(): Long = skipForwardMs()

    override fun seekBack() {
        seekTo((currentPosition - skipBackMs()).coerceAtLeast(0))
    }

    override fun seekForward() {
        val target = currentPosition + skipForwardMs()
        val duration = duration
        seekTo(if (duration > 0) minOf(target, duration) else target)
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().removeAll(*QUEUE_COMMANDS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command !in QUEUE_COMMANDS && super.isCommandAvailable(command)

    private companion object {
        val QUEUE_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
