package com.podclatch.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.podclatch.R
import com.podclatch.domain.SpeedIconSpec

/** The one custom session command of the Speed button; its name is stable and in the app's namespace. */
const val SPEED_COMMAND_ACTION = "com.podclatch.session.TOGGLE_SPEED"

val speedSessionCommand: SessionCommand by lazy { SessionCommand(SPEED_COMMAND_ACTION, android.os.Bundle.EMPTY) }

/** True when [command] is the Speed button's command. */
fun isSpeedCommand(command: SessionCommand): Boolean =
    command.commandCode == SessionCommand.COMMAND_CODE_CUSTOM && isSpeedAction(command.customAction)

/** True when [customAction] is the Speed command's action name. */
fun isSpeedAction(customAction: String): Boolean = customAction == SPEED_COMMAND_ACTION

/**
 * Runs the Speed command: the mode is flipped by [toggle] (`PlaybackSpeedUseCase.toggle`, the same use
 * case as the phone's button) and nothing else; the player follows the setting. Returns false when the
 * command is not the Speed command.
 */
class SpeedCommandHandler(private val toggle: suspend () -> Unit) {
    suspend fun handle(command: SessionCommand): Boolean = isSpeedCommand(command) && handleAction(command.customAction)

    /** The same, by action name (what the JVM tests call, since a session command needs an Android Bundle). */
    suspend fun handleAction(customAction: String): Boolean {
        if (!isSpeedAction(customAction)) return false
        toggle()
        return true
    }
}

/** Draws the label into a [SpeedIconSpec.SIZE_PX] square: opaque text, transparent elsewhere. */
fun renderSpeedIcon(label: String): Bitmap {
    val size = SpeedIconSpec.SIZE_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SpeedIconSpec.TEXT_COLOR
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = SpeedIconSpec.textSizePx(label)
    }
    val metrics = paint.fontMetrics
    val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
    Canvas(bitmap).drawText(label, size / 2f, baseline, paint)
    return bitmap
}

/**
 * The Speed button: the first button outside the back and forward slots (it takes the default overflow
 * slot). The display name and the generated icon both read [label]; the resource icon is the fallback for
 * a controller that cannot load an address.
 */
fun speedButton(label: String, iconUri: Uri, iconRes: Int): CommandButton =
    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setCustomIconResId(iconRes)
        .setIconUri(iconUri)
        .setSessionCommand(speedSessionCommand)
        .setDisplayName(label)
        .build()
