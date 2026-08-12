package com.trueshine.healthcalendar.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trueshine.healthcalendar.ui.screens.CalendarScreen
import com.trueshine.healthcalendar.ui.screens.HealthScreen
import com.trueshine.healthcalendar.ui.screens.OnboardingScreen
import com.trueshine.healthcalendar.ui.screens.SettingsScreen
import com.trueshine.healthcalendar.ui.screens.TodayScreen

private enum class Tab(val title: String, val icon: ImageVector) {
    TODAY("Сегодня", Icons.Filled.Today),
    CALENDAR("Календарь", Icons.Filled.CalendarMonth),
    HEALTH("Здоровье", Icons.Filled.Favorite),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCalendarApp(viewModel: HealthViewModel = viewModel()) {
    val ui by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(Tab.TODAY) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (!ui.state.isConfigured) {
            Scaffold { padding ->
                OnboardingScreen(
                    onStart = viewModel::startTracking,
                    contentPadding = padding,
                )
            }
            return@Surface
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(selectedTab.title) },
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) },
                        )
                    }
                }
            },
        ) { padding ->
            when (selectedTab) {
                Tab.TODAY -> TodayScreen(
                    ui = ui,
                    onToggleTodayRelapse = viewModel::toggleRelapse,
                    contentPadding = padding,
                )

                Tab.CALENDAR -> CalendarScreen(
                    ui = ui,
                    onToggleRelapse = viewModel::toggleRelapse,
                    contentPadding = padding,
                )

                Tab.HEALTH -> HealthScreen(
                    ui = ui,
                    contentPadding = padding,
                )

                Tab.SETTINGS -> SettingsScreen(
                    ui = ui,
                    onQuitDateChange = viewModel::setQuitAt,
                    onCigarettesPerDayChange = viewModel::setCigarettesPerDay,
                    onPricePerPackChange = viewModel::setPricePerPack,
                    onCigarettesPerPackChange = viewModel::setCigarettesPerPack,
                    onCurrencyChange = viewModel::setCurrency,
                    onReset = viewModel::reset,
                    contentPadding = padding,
                )
            }
        }
    }
}
