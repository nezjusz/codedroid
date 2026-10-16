package io.github.nezjusz.codedroid.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.nezjusz.codedroid.DeepLink
import io.github.nezjusz.codedroid.core.data.OpenCodeRepository
import io.github.nezjusz.codedroid.core.data.ServerRuntime
import io.github.nezjusz.codedroid.ui.chat.ChatScreen
import io.github.nezjusz.codedroid.ui.hosts.HostsScreen
import io.github.nezjusz.codedroid.ui.hosts.PairHostScreen
import io.github.nezjusz.codedroid.ui.sessions.SessionsScreen

object Routes {
    const val HOSTS = "hosts"
    const val PAIR = "pair?serverId={serverId}"
    const val SESSIONS = "sessions/{serverId}"
    const val CHAT = "chat/{serverId}/{sessionId}"

    fun pair(serverId: String? = null) = if (serverId == null) "pair" else "pair?serverId=$serverId"
    fun sessions(serverId: String) = "sessions/$serverId"
    fun chat(serverId: String, sessionId: String) = "chat/$serverId/$sessionId"
}

@Composable
fun CodeDroidRoot(
    repository: OpenCodeRepository,
    runtimes: Map<String, ServerRuntime>,
    deepLink: DeepLink?,
    onDeepLinkHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val servers by repository.servers.collectAsStateWithLifecycle()

    LaunchedEffect(deepLink, servers) {
        val link = deepLink ?: return@LaunchedEffect
        // Wait for persisted hosts to load so the target screen can resolve them.
        if (servers.isEmpty() && link !is DeepLink.Host) return@LaunchedEffect
        val route = when (link) {
            is DeepLink.Session -> Routes.chat(link.serverId, link.sessionId)
            is DeepLink.Host -> if (servers.any { it.id == link.serverId }) {
                Routes.sessions(link.serverId)
            } else {
                Routes.HOSTS
            }
        }
        navController.navigate(route) { launchSingleTop = true }
        onDeepLinkHandled()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOSTS,
            enterTransition = { slideIn() },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { slideOut() },
        ) {
            composable(Routes.HOSTS) {
                HostsScreen(
                    repository = repository,
                    servers = servers,
                    runtimes = runtimes,
                    onOpenHost = { navController.navigate(Routes.sessions(it)) },
                    onAddHost = { navController.navigate(Routes.pair()) },
                    onEditHost = { navController.navigate(Routes.pair(it)) },
                )
            }

            composable(
                route = Routes.PAIR,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                PairHostScreen(
                    repository = repository,
                    existing = entry.arguments?.getString("serverId")
                        ?.let { id -> servers.firstOrNull { it.id == id } },
                    onDone = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.SESSIONS,
                arguments = listOf(navArgument("serverId") { type = NavType.StringType }),
            ) { entry ->
                val serverId = entry.arguments?.getString("serverId").orEmpty()
                SessionsScreen(
                    repository = repository,
                    serverId = serverId,
                    runtime = runtimes[serverId],
                    server = servers.firstOrNull { it.id == serverId },
                    onBack = { navController.popBackStack() },
                    onOpenSession = { navController.navigate(Routes.chat(serverId, it)) },
                    onEditHost = { navController.navigate(Routes.pair(serverId)) },
                )
            }

            composable(
                route = Routes.CHAT,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("sessionId") { type = NavType.StringType },
                ),
            ) { entry ->
                val serverId = entry.arguments?.getString("serverId").orEmpty()
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                ChatScreen(
                    repository = repository,
                    serverId = serverId,
                    sessionId = sessionId,
                    runtime = runtimes[serverId],
                    server = servers.firstOrNull { it.id == serverId },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun AnimatedContentTransitionScope<*>.slideIn() =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260)) + fadeIn(tween(200))

private fun AnimatedContentTransitionScope<*>.slideOut() =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) + fadeOut(tween(160))
