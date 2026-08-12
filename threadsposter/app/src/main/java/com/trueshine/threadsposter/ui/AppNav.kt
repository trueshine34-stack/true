package com.trueshine.threadsposter.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
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
import com.trueshine.threadsposter.ui.screens.AccountEditScreen
import com.trueshine.threadsposter.ui.screens.AccountsScreen
import com.trueshine.threadsposter.ui.screens.DashboardScreen
import com.trueshine.threadsposter.ui.screens.LeadsScreen
import com.trueshine.threadsposter.ui.screens.LogsScreen
import com.trueshine.threadsposter.ui.screens.PostScreen
import com.trueshine.threadsposter.ui.screens.QueueScreen
import com.trueshine.threadsposter.ui.screens.SearchScreen
import com.trueshine.threadsposter.ui.screens.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val QUEUE = "queue"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val LEADS = "leads"
    const val LOGS = "logs"
    const val ACCOUNT_EDIT = "account/{accountId}"
    const val POST = "post/{postId}"

    fun accountEdit(id: Long) = "account/$id"
    fun post(id: Long) = "post/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Обзор", Icons.Default.Dashboard),
    Tab(Routes.QUEUE, "Очередь", Icons.Default.Schedule),
    Tab(Routes.SEARCH, "Поиск", Icons.Default.Search),
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
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenQueue = { navController.navigate(Routes.QUEUE) },
                    onOpenLeads = { navController.navigate(Routes.LEADS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                )
            }
            composable(Routes.QUEUE) {
                QueueScreen(onOpenPost = { navController.navigate(Routes.post(it)) })
            }
            composable(Routes.SEARCH) {
                SearchScreen(onOpenLeads = { navController.navigate(Routes.LEADS) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAccount = { navController.navigate(Routes.accountEdit(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.LEADS) {
                LeadsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOGS) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.ACCOUNT_EDIT,
                arguments = listOf(navArgument("accountId") { type = NavType.LongType }),
            ) { entry ->
                AccountEditScreen(
                    accountId = entry.arguments?.getLong("accountId") ?: 0L,
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
