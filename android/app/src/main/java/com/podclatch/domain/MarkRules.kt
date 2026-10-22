package com.podclatch.domain

import com.podclatch.data.db.EpisodeStatus

/*
 * Which Episodes a "Mark" action really changes (spec 5.3). An action never touches an Episode it would
 * leave as it is: that would restart the Played clock of auto-delete and write useless outbox rows.
 */

/** Mark Played (single or all) changes only New Episodes. */
fun changesOnMarkPlayed(status: EpisodeStatus): Boolean = status == EpisodeStatus.NEW

/** Mark New changes a Played Episode, and a New one that has progress (the progress is reset). */
fun changesOnMarkNew(status: EpisodeStatus, positionSec: Int): Boolean =
    status == EpisodeStatus.PLAYED || positionSec > 0
