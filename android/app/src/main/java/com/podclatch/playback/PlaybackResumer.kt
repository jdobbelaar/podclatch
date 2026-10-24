package com.podclatch.playback

import com.podclatch.data.db.ResumeRow
import com.podclatch.domain.QueueKey
import com.podclatch.domain.chooseResume

/**
 * Resumption after the process died (US-16, decision 4). It picks the Episode with [chooseResume], resolves
 * it like any other play, and starts at the saved position. The rows, the resolver, and the download request
 * are parameters so a plain unit test drives it.
 *
 * With `isForPlayback` false nothing is requested; with true the play path's download request runs exactly
 * once (the same one as for a tap on the phone). With nothing to resume, or an Episode that cannot be
 * resolved any more, it answers null: no error, no playback.
 */
class PlaybackResumer(
    private val inProgress: suspend () -> List<ResumeRow>,
    private val queue: suspend () -> List<QueueKey>,
    private val resolve: suspend (Long) -> ResolvedEpisode?,
    private val onPlayRequested: suspend (Long) -> Unit,
) {
    suspend fun resume(isForPlayback: Boolean): AdvanceTarget? {
        val choice = chooseResume(inProgress(), queue()) ?: return null
        val episode = resolve(choice.episodeId) ?: return null
        if (isForPlayback) onPlayRequested(choice.episodeId)
        return AdvanceTarget(episode, choice.startPositionMs)
    }
}
