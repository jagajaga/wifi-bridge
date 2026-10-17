package dev.wifibridge.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.wifibridge.R
import dev.wifibridge.protocol.Outcome
import dev.wifibridge.ui.MainActivity

object Notifications {
    const val CHANNEL_SERVICE = "bridge_service"
    const val CHANNEL_APPROVAL = "share_requests"
    const val SERVICE_ID = 1

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVAL, context.getString(R.string.channel_approval), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    fun service(context: Context, status: String): Notification =
        Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(context.getString(R.string.service_title))
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    /** Returns false if the user has blocked notifications (the prompt cannot be shown). */
    fun showApproval(
        context: Context, id: Int, outcome: Outcome.NeedsApproval, share: PendingIntent, deny: PendingIntent,
    ): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled() || !BlePermissions.canNotify(context)) return false
        ensureChannels(context)
        val n = Notification.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(context.getString(R.string.approval_title))
            .setContentText(ApprovalHub.summary(context, outcome))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setTimeoutAfter(ApprovalHub.TIMEOUT_MILLIS)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.share), share).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.deny), deny).build())
            .build()
        nm.notify(id, n)
        return true
    }
}
