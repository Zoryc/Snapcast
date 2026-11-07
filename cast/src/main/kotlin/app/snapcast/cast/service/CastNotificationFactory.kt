package app.snapcast.cast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.snapcast.cast.CastIntents
import app.snapcast.cast.R
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/** What the media notification shows; rebuilt only when this changes. */
internal data class CastNotificationContent(
    val title: String,
    val text: String,
    val subText: String?,
    val isPlaying: Boolean,
    val isSeekable: Boolean,
    val artwork: Bitmap?,
    /** False while the session is lost: only Stop can reach the receiver (by stopping the proxy). */
    val controllable: Boolean = true,
)

/** Builds the casting notification: MediaStyle with rewind / play-pause / forward / stop actions. */
internal class CastNotificationFactory(private val service: Service) {

    private val contentIntent: PendingIntent? by lazy { createContentIntent() }

    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(service.getString(R.string.cast_notification_channel_name))
            .setDescription(service.getString(R.string.cast_notification_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(service).createNotificationChannel(channel)
    }

    /** Opens the app on the expanded cast controller. */
    fun sessionActivity(): PendingIntent? = contentIntent

    fun build(content: CastNotificationContent, token: MediaSessionCompat.Token?): Notification {
        val actions = ArrayList<NotificationCompat.Action>()
        if (!content.controllable) {
            actions += action(R.drawable.ic_cast_stop, R.string.cast_action_stop, CastPlaybackService.ACTION_STOP)
            return build(content, token, actions, intArrayOf(0))
        }
        if (content.isSeekable) {
            actions += action(
                R.drawable.ic_cast_replay_10,
                R.string.cast_action_rewind,
                CastPlaybackService.ACTION_REWIND,
            )
        }
        val playPauseIndex = actions.size
        actions += if (content.isPlaying) {
            action(R.drawable.ic_cast_pause, R.string.cast_action_pause, CastPlaybackService.ACTION_TOGGLE_PLAYBACK)
        } else {
            action(R.drawable.ic_cast_play, R.string.cast_action_play, CastPlaybackService.ACTION_TOGGLE_PLAYBACK)
        }
        if (content.isSeekable) {
            actions += action(
                R.drawable.ic_cast_forward_10,
                R.string.cast_action_forward,
                CastPlaybackService.ACTION_FORWARD,
            )
        }
        val stopIndex = actions.size
        actions += action(R.drawable.ic_cast_stop, R.string.cast_action_stop, CastPlaybackService.ACTION_STOP)

        val compactActions = if (content.isSeekable) {
            intArrayOf(0, playPauseIndex, playPauseIndex + 1)
        } else {
            intArrayOf(playPauseIndex, stopIndex)
        }
        return build(content, token, actions, compactActions)
    }

    private fun build(
        content: CastNotificationContent,
        token: MediaSessionCompat.Token?,
        actions: List<NotificationCompat.Action>,
        compactActions: IntArray,
    ): Notification {
        val style = MediaNotificationCompat.MediaStyle()
            .setMediaSession(token)
            .setShowActionsInCompactView(*compactActions)

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setLargeIcon(content.artwork)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(style)
        contentIntent?.let { builder.setContentIntent(it) }
        actions.forEach { builder.addAction(it) }
        return builder.build()
    }

    private fun action(@DrawableRes icon: Int, @StringRes title: Int, action: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(icon, service.getString(title), servicePendingIntent(action)).build()

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(service, CastPlaybackService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            service,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createContentIntent(): PendingIntent? {
        val launch = service.packageManager.getLaunchIntentForPackage(service.packageName) ?: return null
        launch.putExtra(CastIntents.EXTRA_OPEN_CAST_CONTROLLER, true)
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            service,
            REQUEST_CONTENT,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "cast_playback"
        const val NOTIFICATION_ID = 0x5CA5
        private const val REQUEST_CONTENT = 1
    }
}
