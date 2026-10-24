package com.podclatch.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.podclatch.Config
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** The address of the generated Speed icon for [label] ("1.25x"); Media3 accepts only content or resource addresses. */
fun speedIconUri(label: String): Uri = Uri.Builder()
    .scheme("content")
    .authority(Config.SPEED_ICON_AUTHORITY)
    .appendPath(label)
    .build()

/** The labels the icon can show: a number with one or two decimals, then "x" (what `formatSpeed` makes). */
fun isSpeedIconLabel(text: String): Boolean = SPEED_ICON_LABEL.matches(text)

private val SPEED_ICON_LABEL = Regex("""\d{1,2}\.\d{1,2}x""")

/**
 * Serves the generated Speed icon as a PNG (US-16): the session loads its buttons' icons from an address,
 * and this draws the current speed text on request. It is read-only, holds no data of the user's, and
 * answers only for a label that looks like a speed, so it is exported for the controllers that load icons
 * themselves (the system, Android Auto).
 */
class SpeedIconProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val label = uri.lastPathSegment
        if (mode != "r" || label == null || !isSpeedIconLabel(label)) throw FileNotFoundException("No such icon")
        val bitmap = renderSpeedIcon(label)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: java.io.IOException) {
                // The reader went away; nothing to do.
            } finally {
                bitmap.recycle()
                pipe[1].close()
            }
        }.start()
        return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
