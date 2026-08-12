package com.trueshine.tgposter.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trueshine.tgposter.ui.screens.AccountsScreen
import com.trueshine.tgposter.ui.screens.ChannelEditScreen
import com.trueshine.tgposter.ui.screens.ChannelsScreen
import com.trueshine.tgposter.ui.screens.DashboardScreen
import com.trueshine.tgposter.ui.screens.LogsScreen
import com.trueshine.tgposter.ui.screens.PostScreen
import com.trueshine.tgposter.ui.screens.QueueScreen
import com.trueshine.tgposter.ui.screens.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val CHANNELS = "channels"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val LOGS = "logs"
    const val CHANNEL_EDIT = "channel/{channelId}"
    const val POST = "post/{postId}"

    fun channelEdit(id: Long) = "channel/$id"
    fun post(id: Long) = "post/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Обзор", Icons.Default.Dashboard),
    Tab(Routes.CHANNELS, "Каналы", Icons.Default.Forum),
    Tab(Routes.QUEUE, "Очередь", Icons.Default.Schedule),
    Tab(Routes.SETTINGS, "Настройки", Icons.Default.Settings),
)

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenChannels = { navController.navigate(Routes.CHANNELS) },
                    onOpenQueue = { navController.navigate(Routes.QUEUE) },
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                )
            }
            composable(Routes.CHANNELS) {
                ChannelsScreen(
                    onOpenChannel = { navController.navigate(Routes.channelEdit(it)) },
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                )
            }
            composable(Routes.QUEUE) {
                QueueScreen(onOpenPost = { navController.navigate(Routes.post(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOGS) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.CHANNEL_EDIT,
                arguments = listOf(navArgument("channelId") { type = NavType.LongType }),
            ) { entry ->
                ChannelEditScreen(
                    channelId = entry.arguments?.getLong("channelId") ?: 0L,
                    onBack = { navController.popBackStack() },
                    onOpenPost = { navController.navigate(Routes.post(it)) },
                )
            }
            composable(
                Routes.POST,
                arguments = listOf(navArgument("postId") { type = NavType.LongType }),
            ) { entry ->
                PostScreen(
                    postId = entry.arguments?.getLong("postId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
