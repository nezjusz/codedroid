package io.github.nezjusz.codedroid.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.nezjusz.codedroid.MainActivity
import io.github.nezjusz.codedroid.R
import io.github.nezjusz.codedroid.core.net.PermissionRequest

/**
 * Notification surface.
 *
 * Permission requests are the highest-signal event for a remote controller: the agent is blocked
 * and every second of delay is wasted wall-clock, so they get a high-importance channel with
 * inline Approve / Always / Reject actions.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    private fun createChannels() {
        val permission = NotificationChannel(
            CHANNEL_PERMISSION,
            "Permission requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "An opencode agent is waiting for you to approve an action"
            enableVibration(true)
            setShowBadge(true)
        }

        val activity = NotificationChannel(
            CHANNEL_ACTIVITY,
            "Session updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Sessions that finished, failed or need input"
        }

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Connection",
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = "Host connection status" }

        manager.createNotificationChannels(listOf(permission, activity, status))
    }

    fun permissionRequest(serverId: String, serverName: String, request: PermissionRequest) {
        val body = buildString {
            request.resources.firstOrNull()?.let { append(it) }
            if (request.resources.size > 1) append(" (+${request.resources.size - 1} more)")
            if (isEmpty()) request.message?.let { append(it) }
        }.take(240)

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId("open", request.sessionID),
            MainActivity.intentForSession(context, serverId, request.sessionID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PERMISSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${request.title} · $serverName")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(sessionLabel(request.sessionID))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Allow once", replyIntent("once", request, serverId))
            .addAction(0, "Always", replyIntent("always", request, serverId))
            .addAction(0, "Reject", replyIntent("reject", request, serverId))
            .build()

        notify("perm_${request.sessionID}_${request.id}", notification)
    }

    fun sessionFinished(serverId: String, serverName: String, sessionID: String, title: String, outcome: String) {
        val text = when (outcome) {
            "succeeded" -> "Finished · $title"
            "failed" -> "Failed · $title"
            "interrupted" -> "Stopped · $title"
            else -> title
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(serverName)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notificationId("done", sessionID),
                    MainActivity.intentForSession(context, serverId, sessionID),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        notify("done_$sessionID", notification)
    }

    fun connectionStatus(serverId: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        manager.notify(SYNC_NOTIFICATION_ID, notification)
    }

    fun clearPending(hostCount: Int) {
        if (hostCount == 0) manager.cancel(SYNC_NOTIFICATION_ID)
    }

    private fun replyIntent(reply: String, request: PermissionRequest, serverId: String): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_REPLY_PERMISSION
            putExtra(ActionReceiver.EXTRA_SERVER_ID, serverId)
            putExtra(ActionReceiver.EXTRA_SESSION_ID, request.sessionID)
            putExtra(ActionReceiver.EXTRA_REQUEST_ID, request.id)
            putExtra(ActionReceiver.EXTRA_REPLY, reply)
            data = Uri.parse("codedroid://reply/${request.id}")
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId("reply", reply + request.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(tag: String, notification: Notification) {
        runCatching { manager.notify(notificationId(tag), notification) }
    }

    private fun sessionLabel(sessionID: String) = sessionID.takeLast(6)

    companion object {
        const val CHANNEL_PERMISSION = "permissions"
        const val CHANNEL_ACTIVITY = "activity"
        const val CHANNEL_STATUS = "status"
        const val SYNC_NOTIFICATION_ID = 1000
    }
}

private fun notificationId(vararg parts: String): Int = parts.joinToString("_").hashCode()
