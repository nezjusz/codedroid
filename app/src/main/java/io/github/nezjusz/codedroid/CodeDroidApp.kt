package io.github.nezjusz.codedroid

import android.app.Application
import io.github.nezjusz.codedroid.core.data.OpenCodeRepository
import io.github.nezjusz.codedroid.core.data.ServerStore
import io.github.nezjusz.codedroid.core.notify.Notifier
import io.github.nezjusz.codedroid.core.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodeDroidApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Starts false: the process can exist for its foreground service long before anything is
        // on screen, and treating that as foreground would suppress notifications that matter.
        container.foreground.isForeground = false
        container.bootstrap()
    }
}

/** Manual dependency container; the graph is small enough not to warrant a DI framework. */
class AppContainer(private val app: Application) {

    val notifier = Notifier(app)
    val serverStore = ServerStore(app)
    val repository = OpenCodeRepository(serverStore, notifier) { foreground.isForeground }

    /**
     * Whether any activity of this process is on screen.
     *
     * Read from the repository when an approval lands, which is not a Compose context. A plain
     * volatile flag is enough: the only decision is "post a notification, or don't", and that has
     * to be read at the moment the event arrives rather than observed.
     */
    val foreground = ForegroundState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun bootstrap() {
        // A foreground service implies a permanent notification, so only run one while there is
        // actually a host that wants background connectivity.
        scope.launch {
            repository.servers.collect { servers ->
                val wanted = servers.any { it.keepAlive }
                if (wanted) SyncService.start(app) else SyncService.stop(app)
                if (wanted) repository.startEnabledServers()
            }
        }
    }
}

class ForegroundState {
    @Volatile
    var isForeground: Boolean = false
}
