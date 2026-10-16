package io.github.nezjusz.codedroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nezjusz.codedroid.ui.CodeDroidRoot
import io.github.nezjusz.codedroid.ui.theme.CodeDroidTheme

class MainActivity : ComponentActivity() {

    private var deepLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink = intent.toDeepLink()

        val container = (application as CodeDroidApp).container

        setContent {
            CodeDroidTheme {
                // Approvals are the whole point of a remote controller, so ask once, up front.
                // Without this the high-importance channel stays silent on Android 13+.
                val requestNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val runtimes by container.repository.runtimes.collectAsStateWithLifecycle()
                CodeDroidRoot(
                    repository = container.repository,
                    runtimes = runtimes,
                    deepLink = deepLink,
                    onDeepLinkHandled = { deepLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = intent.toDeepLink()
    }

    companion object {
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SESSION_ID = "sessionId"

        fun intentForSession(context: Context, serverId: String, sessionID: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SERVER_ID, serverId)
                putExtra(EXTRA_SESSION_ID, sessionID)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        private fun Intent.toDeepLink(): DeepLink? {
            val serverId = getStringExtra(EXTRA_SERVER_ID) ?: return null
            val sessionId = getStringExtra(EXTRA_SESSION_ID) ?: return DeepLink.Host(serverId)
            return DeepLink.Session(serverId, sessionId)
        }
    }
}

sealed interface DeepLink {
    data class Host(val serverId: String) : DeepLink
    data class Session(val serverId: String, val sessionId: String) : DeepLink
}
