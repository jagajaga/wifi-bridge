package dev.wifibridge.ble

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.wifibridge.R
import dev.wifibridge.protocol.Outcome
import dev.wifibridge.protocol.SyncStatus
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pending "Share / Deny" prompts for Macs whose "Share automatically" is off.
 * Each prompt answers exactly once: on tap, or with `timeout` after 60 s.
 */
object ApprovalHub {
    const val TIMEOUT_MILLIS = 60_000L
    private const val EXTRA_ID = "approval_id"
    private const val EXTRA_SHARE = "share"

    private class Pending(val outcome: Outcome.NeedsApproval, val reply: (ByteArray) -> Unit, val timeout: Runnable)

    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<Int, Pending>()
    private val ids = AtomicInteger(2000)

    fun request(context: Context, outcome: Outcome.NeedsApproval, reply: (ByteArray) -> Unit) {
        val app = context.applicationContext
        main.post {
            val id = ids.incrementAndGet()
            val timeout = Runnable { resolve(app, id, SyncStatus.TIMEOUT) }
            pending[id] = Pending(outcome, reply, timeout)
            main.postDelayed(timeout, TIMEOUT_MILLIS)
            // If notifications are blocked this shows nothing and the request answers `timeout` after 60 s.
            Notifications.showApproval(app, id, outcome, action(app, id, true), action(app, id, false))
        }
    }

    fun resolve(context: Context, id: Int, status: SyncStatus) {
        main.post {
            val p = pending.remove(id) ?: return@post
            main.removeCallbacks(p.timeout)
            context.getSystemService(NotificationManager::class.java).cancel(id)
            p.reply(p.outcome.complete(status))
        }
    }

    /** Service stopping: answer outstanding prompts as timeouts so no Mac waits forever. */
    fun cancelAll(context: Context) {
        main.post { pending.keys.toList().forEach { resolve(context, it, SyncStatus.TIMEOUT) } }
    }

    private fun action(context: Context, id: Int, share: Boolean): PendingIntent {
        val intent = Intent(context, ApprovalReceiver::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_SHARE, share)
        return PendingIntent.getBroadcast(
            context, id * 2 + if (share) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    class ApprovalReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val share = intent.getBooleanExtra(EXTRA_SHARE, false)
            resolve(context, id, if (share) SyncStatus.OK else SyncStatus.DENIED)
            context.getSystemService(NotificationManager::class.java).cancel(id)
        }
    }

    internal fun summary(context: Context, outcome: Outcome.NeedsApproval): String {
        val what = if (outcome.request.all) context.getString(R.string.approval_all)
        else outcome.request.ssids.joinToString(", ")
        return context.getString(R.string.approval_text, what, outcome.mac.name)
    }
}
