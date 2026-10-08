package com.sleeptide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sleeptide.tracking.TrackingState

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("sleep", "Sleep", Icons.Filled.Bedtime),
    Tab("journal", "Journal", Icons.AutoMirrored.Filled.MenuBook),
    Tab("stats", "Statistics", Icons.Filled.BarChart),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(openSession: MutableState<Long?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val live by TrackingState.live.collectAsStateWithLifecycle()
    val finished by TrackingState.finishedSessionId.collectAsStateWithLifecycle()
    val message by TrackingState.message.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(finished) {
        finished?.let {
            TrackingState.finishedSessionId.value = null
            nav.navigate("night/$it")
        }
    }
    LaunchedEffect(openSession.value) {
        openSession.value?.let {
            openSession.value = null
            nav.navigate("night/$it")
        }
    }
    LaunchedEffect(message) {
        message?.let {
            TrackingState.message.value = null
            snackbar.showSnackbar(it)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (tabs.any { it.route == route }) {
                    NavigationBar(containerColor = Palette.Surface1) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.Surface2)
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(nav, startDestination = "sleep", modifier = Modifier.padding(padding)) {
                composable("sleep") { SleepScreen(snackbar) }
                composable("journal") { JournalScreen(onOpen = { nav.navigate("night/$it") }) }
                composable("stats") { StatsScreen() }
                composable("settings") { SettingsScreen(snackbar) }
                composable("night/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    NightDetailScreen(entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                }
            }
        }
        live?.let { TrackingScreen(it) }
    }
}
