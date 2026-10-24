package com.podclatch.domain

import kotlin.math.pow

/** The text on the Speed button of the session (notification and car): "1.3x" when ASSIGNED, "1.0x" when NORMAL. */
fun speedButtonLabel(state: PlaybackSpeedState): String = formatSpeed(state.currentSpeed)

/**
 * The drawable that shows [label] for a controller that can only use a resource icon (the system's media
 * controls and Android Auto read a custom action's icon from a resource), for example "ic_speed_1_25x".
 * There is one for every tenth from 0.5x to 3.0x and one for a stored 1.25x.
 */
fun speedIconResName(label: String): String = "ic_speed_" + label.replace('.', '_')

/**
 * The look of the Speed button icon (US-16): plain numbers, so the contrast and the size can be checked
 * without an Android runtime. The icon is a transparent square with the label drawn in [TEXT_COLOR]; the
 * system tints notification icons, and a car draws them on its dark surface, so the text is checked
 * against [SURFACE_COLOR], the dark surface the icon sits on.
 */
object SpeedIconSpec {
    /** The bitmap is square; 96 pixels stays legible at the notification's action size. */
    const val SIZE_PX = 96

    /** Opaque white text (ARGB). */
    const val TEXT_COLOR = 0xFFFFFFFF.toInt()

    /** The dark surface the text sits on (ARGB); media notifications and car screens are dark. */
    const val SURFACE_COLOR = 0xFF202124.toInt()

    /** Text height as a fraction of the bitmap size, shrunk for longer labels so "1.25x" (an older stored speed) never clips. */
    fun textSizePx(label: String): Float {
        val fraction = when {
            label.length <= 4 -> 0.40f
            else -> 0.34f
        }
        return SIZE_PX * fraction
    }

    /** WCAG contrast ratio between two opaque ARGB colours, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}

/**
 * Makes a value from a label and keeps the last one: the icon is rebuilt only when the text changes
 * (US-16), not on every settings change.
 */
class LabelCache<T : Any>(private val make: (String) -> T) {
    private var lastLabel: String? = null
    private var lastValue: T? = null

    /** How many times [make] ran; a test reads it. */
    var builds = 0
        private set

    fun get(label: String): T {
        val cached = lastValue
        if (label == lastLabel && cached != null) return cached
        val value = make(label)
        builds++
        lastLabel = label
        lastValue = value
        return value
    }
}
