package com.podclatch.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.podclatch.Config
import com.podclatch.MainActivity
import com.podclatch.R
import com.podclatch.domain.NewEpisodeNotice
import com.podclatch.domain.NewEpisodeNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "New episodes" notification on its own channel (default importance, separate from the playback
 * channel) under one fixed id, so a later Sync replaces it instead of stacking. Tapping it opens
 * [MainActivity], whose start destination is the Queue tab, and dismisses it. Without the notification
 * permission (or with notifications switched off) it posts nothing and does not crash.
 */
@Singleton
class AndroidNewEpisodeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : NewEpisodeNotifier {
    override fun post(notice: NewEpisodeNotice) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(Config.NEW_EPISODES_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.new_episodes_channel_name))
                    .build(),
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, Config.NEW_EPISODES_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_feeds)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            @Suppress("MissingPermission") // areNotificationsEnabled() above covers the runtime permission.
            manager.notify(Config.NEW_EPISODES_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the call: nothing to post.
        }
    }
}
