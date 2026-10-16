package io.github.nezjusz.codedroid.core.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.nezjusz.codedroid.CodeDroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles inline notification actions, most importantly replying to a permission request. */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY_PERMISSION -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
                val sessionID = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                val requestID = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
                val reply = intent.getStringExtra(EXTRA_REPLY) ?: return

                val app = context.applicationContext as? CodeDroidApp ?: return
                val pending = goAsync()

                // Take the notification down as soon as the decision is made. Waiting for the
                // host to confirm left it on screen, and it is posted ongoing, so the user could
                // not even swipe it away.
                app.container.notifier.cancelPermission(sessionID, requestID)

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = app.container.repository.replyPermission(serverId, sessionID, requestID, reply)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (result) "Reply sent" else "Could not reach $serverId",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, e.message ?: "Reply failed", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_REPLY_PERMISSION = "io.github.nezjusz.codedroid.REPLY_PERMISSION"
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SESSION_ID = "sessionID"
        const val EXTRA_REQUEST_ID = "requestID"
        const val EXTRA_REPLY = "reply"
    }
}
