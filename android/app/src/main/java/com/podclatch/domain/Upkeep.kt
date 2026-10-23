package com.podclatch.domain

import kotlinx.coroutines.CancellationException

/** One New Episode a pull inserted. [feedWasCached] is false for a Feed the pull itself brought in. */
data class InsertedEpisode(val episodeId: Long, val feedId: Long, val feedWasCached: Boolean)

/** What one successful pull did, handed to the local upkeep. It holds ids only, never an address or a title. */
data class PullSummary(
    val trigger: SyncTrigger,
    /** True when the device had never pulled before (`lastSyncRev` was 0). */
    val firstPull: Boolean,
    /** The Episodes the pull inserted as New; an Episode that only changed is not in it. */
    val insertedNew: List<InsertedEpisode> = emptyList(),
)

/** One piece of local housekeeping that runs after a successful pull (the sweep, the top-up, the notification). */
fun interface UpkeepStep {
    suspend fun run(pull: PullSummary)
}

/** Housekeeping that runs after a successful pull, never after a failed push or pull. Sync calls it once. */
fun interface LocalUpkeep {
    suspend fun run(pull: PullSummary)
}

/**
 * Runs [steps] in order. A step that throws is reported through [onStepFailed] (its name and the exception's
 * class only, never the message, which could hold an address) and does not stop the next step; nothing here
 * ever throws except cancellation, so a successful Sync stays successful.
 */
class OrderedLocalUpkeep(
    private val steps: List<Pair<String, UpkeepStep>>,
    private val onStepFailed: (stepName: String, errorClass: String) -> Unit = { _, _ -> },
) : LocalUpkeep {
    override suspend fun run(pull: PullSummary) {
        for ((name, step) in steps) {
            try {
                step.run(pull)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    onStepFailed(name, e.javaClass.simpleName)
                } catch (_: Exception) {
                    // Reporting is best effort.
                }
            }
        }
    }
}
