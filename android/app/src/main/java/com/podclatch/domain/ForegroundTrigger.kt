package com.podclatch.domain

/**
 * Counts started Activities to find the moments the app comes to the foreground, without a lifecycle
 * dependency. Pure, so it is unit-testable.
 *
 * [onStarted] returns true when a Sync trigger should fire: the first Activity of the process starting,
 * or the app returning to the foreground (zero started Activities to one). A configuration change
 * (rotation) stops and restarts the Activity, which does not count as leaving the foreground.
 */
class StartedActivityCounter {
    private var started = 0
    private var restartAfterConfigChange = false

    fun onStarted(): Boolean {
        val wasBackground = started == 0
        started++
        val fire = wasBackground && !restartAfterConfigChange
        restartAfterConfigChange = false
        return fire
    }

    fun onStopped(isChangingConfigurations: Boolean) {
        if (started > 0) started--
        restartAfterConfigChange = started == 0 && isChangingConfigurations
    }
}
