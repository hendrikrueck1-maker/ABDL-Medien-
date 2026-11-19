package de.hr.mediathek.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.hr.mediathek.feature.home.HomeScreen
import de.hr.mediathek.feature.placeholder.PlaceholderScreen

private const val WIDE_LAYOUT_MIN_WIDTH_DP = 600

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val isWide = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP

    if (isWide) {
        Row(Modifier.fillMaxSize()) {
            AppNavigationRail(navController)
            Scaffold(Modifier.weight(1f)) { padding ->
                AppNavHost(navController, Modifier.padding(padding))
            }
        }
    } else {
        Scaffold(
            bottomBar = { AppBottomBar(navController) },
        ) { padding ->
            AppNavHost(navController, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
        modifier = modifier,
    ) {
        composable(Destination.Home.route) {
            HomeScreen(onOpen = { navController.navigateTopLevel(it) })
        }
        Destination.entries
            .filter { it != Destination.Home }
            .forEach { destination ->
                composable(destination.route) {
                    PlaceholderScreen(title = stringResource(destination.label))
                }
            }
    }
}

@Composable
private fun AppBottomBar(navController: NavHostController) {
    val currentRoute = navController.currentRoute()
    NavigationBar {
        Destination.entries.filter { it.inCompactBar }.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { navController.navigateTopLevel(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(navController: NavHostController) {
    val currentRoute = navController.currentRoute()
    NavigationRail {
        Destination.entries.forEach { destination ->
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = { navController.navigateTopLevel(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

@Composable
private fun NavHostController.currentRoute(): String? {
    val entry by currentBackStackEntryAsState()
    return entry?.destination?.hierarchy?.firstOrNull()?.route
}

private fun NavHostController.navigateTopLevel(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
