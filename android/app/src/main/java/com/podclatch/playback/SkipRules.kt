package com.podclatch.playback

/** Which way a skip goes. */
enum class SkipDirection { BACK, FORWARD }

/**
 * Where a skip lands: [positionMs] moved by [skipBackMs] or [skipForwardMs]. Never below 0, and never
 * past [durationMs] when it is known (zero or negative, such as `C.TIME_UNSET`, means unknown). The one
 * computation behind Fast-forward, Rewind, Next, and Previous.
 */
fun skipTargetMs(
    direction: SkipDirection,
    positionMs: Long,
    durationMs: Long,
    skipBackMs: Long,
    skipForwardMs: Long,
): Long = when (direction) {
    SkipDirection.BACK -> (positionMs - skipBackMs).coerceAtLeast(0)
    SkipDirection.FORWARD -> {
        val target = positionMs + skipForwardMs
        if (durationMs > 0) minOf(target, durationMs) else target
    }
}

private const val KEYCODE_MEDIA_NEXT = 87
private const val KEYCODE_MEDIA_PREVIOUS = 88
private const val ACTION_DOWN = 0

/**
 * The skip a media-button key event asks for, or null when it asks for none. Only the first key-down of
 * a press counts: not the key-up, and not the repeats of a held button.
 */
fun skipForKeyEvent(keyCode: Int, action: Int, repeatCount: Int): SkipDirection? {
    if (action != ACTION_DOWN || repeatCount != 0) return null
    return when (keyCode) {
        KEYCODE_MEDIA_NEXT -> SkipDirection.FORWARD
        KEYCODE_MEDIA_PREVIOUS -> SkipDirection.BACK
        else -> null
    }
}

/** True for the two keys handled here, whatever their action (so the key-up and repeats are consumed too). */
fun isSkipKey(keyCode: Int): Boolean = keyCode == KEYCODE_MEDIA_NEXT || keyCode == KEYCODE_MEDIA_PREVIOUS
