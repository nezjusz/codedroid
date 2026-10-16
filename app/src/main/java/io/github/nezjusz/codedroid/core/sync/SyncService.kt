package io.github.nezjusz.codedroid.core.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.nezjusz.codedroid.CodeDroidApp
import io.github.nezjusz.codedroid.core.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps host connections alive while the app is backgrounded.
 *
 * Permission requests arrive on the server's event stream, so a process that is suspended the
 * moment the user leaves the app cannot honour the core remote-control promise. This service
 * holds the connections and lets the repository keep draining events.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        val app = application as? CodeDroidApp
        app?.container?.repository?.startEnabledServers()
        startStatusTicker(app)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as? CodeDroidApp)?.container?.repository?.startEnabledServers()
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** Keeps the low-importance connection notification in sync with actual host state. */
    private fun startStatusTicker(app: CodeDroidApp?) {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                val runtimes = app?.container?.repository?.runtimes?.value.orEmpty()
                val servers = app?.container?.repository?.servers?.value.orEmpty()
                val live = runtimes.count { it.value.live }
                val blockers = runtimes.values.sumOf { it.attention }

                val notifier = app?.container?.notifier ?: return@launch
                when {
                    servers.isEmpty() -> notifier.connectionStatus("", "No hosts connected")
                    live == servers.size && blockers == 0 ->
                        notifier.connectionStatus("", "Connected to $live host${plural(live)}")

                    blockers > 0 ->
                        notifier.connectionStatus("", "$blockers request${plural(blockers)} waiting")

                    else -> notifier.connectionStatus("", "$live of ${servers.size} host${plural(servers.size)} live")
                }
                delay(15_000)
            }
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun startForegroundCompat() {
        val notification: Notification = Notification.Builder(this, Notifier.CHANNEL_STATUS)
            .setContentTitle("CodeDroid")
            .setContentText("Connecting to opencode hosts…")
            .setSmallIcon(io.github.nezjusz.codedroid.R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Notifier.SYNC_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ServiceCompat.startForeground(this, Notifier.SYNC_NOTIFICATION_ID, notification, 0)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncService::class.java)) }
        }
    }
}
