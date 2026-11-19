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
import de.hr.mediathek.feature.diag.DiagRoutes
import de.hr.mediathek.feature.diag.PdfDiagScreen
import de.hr.mediathek.feature.diag.ScanDiagScreen
import de.hr.mediathek.feature.diag.TranscodeDiagScreen
import de.hr.mediathek.feature.errorlog.ErrorLogScreen
import de.hr.mediathek.feature.home.HomeScreen
import de.hr.mediathek.feature.placeholder.PlaceholderScreen
import de.hr.mediathek.feature.settings.SettingsScreen
import de.hr.mediathek.feature.storage.StorageScreen
import de.hr.mediathek.feature.videos.DuplicatesScreen
import de.hr.mediathek.feature.videos.PlayerScreen
import de.hr.mediathek.feature.videos.TrashScreen
import de.hr.mediathek.feature.videos.VideosScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

private const val WIDE_LAYOUT_MIN_WIDTH_DP = 600

/** Routen außerhalb der Hauptbereiche. */
object AppRoutes {
    const val STORAGE = "storage"
    const val ERROR_LOG = "errorlog"
}

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
        composable(Destination.Settings.route) {
            SettingsScreen(onOpenRoute = { navController.navigate(it) })
        }
        composable(AppRoutes.STORAGE) { StorageScreen(onBack = { navController.popBackStack() }) }
        composable(AppRoutes.ERROR_LOG) { ErrorLogScreen(onBack = { navController.popBackStack() }) }
        composable(DiagRoutes.SCAN) { ScanDiagScreen(onBack = { navController.popBackStack() }) }
        composable(DiagRoutes.TRANSCODE) { TranscodeDiagScreen(onBack = { navController.popBackStack() }) }
        composable(DiagRoutes.PDF) { PdfDiagScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.Videos.route) {
            VideosScreen(
                onOpen = { navController.navigate("player/$it") },
                onOpenTrash = { navController.navigate("trash") },
                onOpenDuplicates = { navController.navigate("duplicates") },
            )
        }
        composable(Destination.Images.route) {
            de.hr.mediathek.feature.images.ImagesScreen(onOpen = { navController.navigate("image/$it") })
        }
        composable(
            route = "image/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
        ) { de.hr.mediathek.feature.images.ImageViewerScreen(onBack = { navController.popBackStack() }) }
        composable("trash") { TrashScreen(onBack = { navController.popBackStack() }) }
        composable("duplicates") { DuplicatesScreen(onBack = { navController.popBackStack() }) }
        composable(
            route = "player/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
        ) {
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onNext = { next ->
                    // Das aktuelle Video wird durch das nächste ersetzt, damit "Zurück" zur Liste führt.
                    navController.popBackStack()
                    navController.navigate("player/$next")
                },
            )
        }
        Destination.entries
            .filter {
                it != Destination.Home && it != Destination.Settings && it != Destination.Videos &&
                    it != Destination.Images
            }
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
