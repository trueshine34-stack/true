package ae.dressrent.studio.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ae.dressrent.studio.ui.screens.BookingEditScreen
import ae.dressrent.studio.ui.screens.BookingDetailScreen
import ae.dressrent.studio.ui.screens.BookingsScreen
import ae.dressrent.studio.ui.screens.ClientEditScreen
import ae.dressrent.studio.ui.screens.ClientsScreen
import ae.dressrent.studio.ui.screens.DashboardScreen
import ae.dressrent.studio.ui.screens.ItemEditScreen
import ae.dressrent.studio.ui.screens.SettingsScreen
import ae.dressrent.studio.ui.screens.WardrobeScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Сегодня", Icons.Default.Today),
    Tab("bookings", "Брони", Icons.Default.Event),
    Tab("wardrobe", "Гардероб", Icons.Default.Checkroom),
    Tab("clients", "Клиенты", Icons.Default.People)
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val vm: StudioViewModel = viewModel()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "today",
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable("today") { DashboardScreen(vm, nav) }
            composable("bookings") { BookingsScreen(vm, nav) }
            composable("wardrobe") { WardrobeScreen(vm, nav) }
            composable("clients") { ClientsScreen(vm, nav) }
            composable("settings") { SettingsScreen(vm, nav) }
            composable("booking/{id}") { backStack ->
                BookingDetailScreen(vm, nav, backStack.arguments?.getString("id")?.toLongOrNull() ?: 0L)
            }
            composable("booking/edit/{id}") { backStack ->
                BookingEditScreen(vm, nav, backStack.arguments?.getString("id")?.toLongOrNull() ?: 0L)
            }
            composable("item/edit/{id}") { backStack ->
                ItemEditScreen(vm, nav, backStack.arguments?.getString("id")?.toLongOrNull() ?: 0L)
            }
            composable("client/edit/{id}") { backStack ->
                ClientEditScreen(vm, nav, backStack.arguments?.getString("id")?.toLongOrNull() ?: 0L)
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
