package de.hr.mediathek.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    const val STORAGE_PATTERN = "storage/{section}"
    fun storage(section: String) = "storage/$section"
    const val ERROR_LOG = "errorlog"
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val isWide = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP
    // Player, Bildansicht und Diashow brauchen den ganzen Bildschirm; sie haben eigene Zurück-Knöpfe.
    val route = navController.currentRoute().orEmpty()
    val fullScreenRoute = isFullScreenRoute(route)

    AppBackground {
    if (isWide) {
        Row(Modifier.fillMaxSize()) {
            if (!fullScreenRoute) AppNavigationRail(navController)
            Scaffold(Modifier.weight(1f), containerColor = Color.Transparent) { padding ->
                ScreenPanel(fullScreenRoute, Modifier.padding(padding)) { AppNavHost(navController, Modifier.fillMaxSize()) }
            }
        }
    } else {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { if (!fullScreenRoute) AppBottomBar(navController) },
        ) { padding ->
            ScreenPanel(fullScreenRoute, Modifier.padding(padding)) { AppNavHost(navController, Modifier.fillMaxSize()) }
        }
    }
    }
}

private fun isFullScreenRoute(route: String?): Boolean =
    route != null && (route.startsWith("player/") || route.startsWith("image/{") || route.startsWith("slideshow"))

/**
 * Heller, fast deckender Untergrund hinter jedem normalen Bildschirm: Schrift liegt nie direkt auf dem Hintergrundbild
 * und bleibt lesbar. Darauf steigen zarte Seifenblasen auf. Vollbild-Seiten (Player, Bilder) bleiben unverändert;
 * der Inhalt behält dabei seinen Platz im Baum, damit beim Wechsel nichts neu aufgebaut wird.
 */
@Composable
private fun ScreenPanel(fullScreen: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val panel = if (fullScreen) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
    }
    Box(modifier.then(panel)) {
        if (!fullScreen) FloatingBubbles()
        content()
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
        modifier = modifier,
        // Kurze Übergänge: Die frühere lange Überblendung ließ Player und Liste gleichzeitig laufen und ruckeln.
        enterTransition = {
            if (isFullScreenRoute(targetState.destination.route)) {
                fadeIn(tween(120))
            } else {
                fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                )
            }
        },
        exitTransition = { fadeOut(tween(90)) },
        popEnterTransition = { fadeIn(tween(140)) },
        popExitTransition = { fadeOut(tween(90)) },
    ) {
        composable(Destination.Home.route) {
            HomeScreen(
                onOpen = { navController.navigateTopLevel(it) },
                onOpenVideo = { navController.navigate("player/$it") },
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(onOpenRoute = { navController.navigate(it) })
        }
        composable(
            route = AppRoutes.STORAGE_PATTERN,
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
        ) { entry ->
            StorageScreen(
                onBack = { navController.popBackStack() },
                section = entry.arguments?.getString("section") ?: de.hr.mediathek.feature.storage.StorageSection.SOURCES,
            )
        }
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
            de.hr.mediathek.feature.images.ImagesScreen(
                onOpen = { navController.navigate("image/$it") },
                onOpenDuplicates = { navController.navigate("image-duplicates") },
                onOpenSlideshow = { navController.navigate("slideshow") },
            )
        }
        composable(
            route = "image/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType }),
        ) {
            de.hr.mediathek.feature.images.ImageViewerScreen(
                onBack = { navController.popBackStack() },
                onSlideshow = { navController.navigate("slideshow?start=$it") },
            )
        }
        composable(
            route = "slideshow?start={start}",
            arguments = listOf(navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { de.hr.mediathek.feature.images.SlideshowScreen(onBack = { navController.popBackStack() }) }
        composable("image-duplicates") {
            de.hr.mediathek.feature.images.ImageDuplicatesScreen(
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate("image/$it") },
            )
        }
        composable(Destination.Stories.route) {
            de.hr.mediathek.feature.stories.StoriesScreen(
                onOpen = { navController.navigate("story/$it") },
                onEdit = { navController.navigate("story-edit/$it") },
                onImport = { navController.navigate("story-import") },
            )
        }
        composable("story-import") {
            de.hr.mediathek.feature.stories.StoryImportScreen(
                onBack = { navController.popBackStack() },
                onMarkdown = { navController.navigate("story-import-md") },
            )
        }
        composable("story-import-md") {
            de.hr.mediathek.feature.stories.MarkdownImportScreen(onBack = { navController.popBackStack() })
        }
        composable("story/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) {
            de.hr.mediathek.feature.stories.StoryReaderScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("story-edit/$it") },
            )
        }
        composable("story-edit/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) {
            de.hr.mediathek.feature.stories.StoryEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(Destination.Products.route) {
            de.hr.mediathek.feature.products.ProductsScreen(
                onOpen = { navController.navigate("product/$it") },
                onImport = { navController.navigate("product-import") },
            )
        }
        composable("product-import") {
            de.hr.mediathek.feature.products.ProductImportScreen(onBack = { navController.popBackStack() })
        }
        composable("product/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) {
            de.hr.mediathek.feature.products.ProductDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Destination.Tracker.route) {
            de.hr.mediathek.feature.tracker.TrackerScreen(
                onOpenProduct = { navController.navigate("product/$it") },
                onStats = { navController.navigate("tracker-stats") },
            )
        }
        composable("tracker-stats") {
            de.hr.mediathek.feature.tracker.TrackerStatsScreen(
                onBack = { navController.popBackStack() },
                onOpenProduct = { navController.navigate("product/$it") },
            )
        }
        composable(Destination.Search.route) {
            de.hr.mediathek.feature.search.SearchScreen(onOpen = { kind, uuid ->
                when (kind) {
                    "VIDEO" -> navController.navigate("player/$uuid")
                    "IMAGE" -> navController.navigate("image/$uuid")
                    "STORY" -> navController.navigate("story/$uuid")
                    else -> navController.navigate("product/$uuid")
                }
            })
        }
        composable("overall-stats") { de.hr.mediathek.feature.overview.OverallStatsScreen(onBack = { navController.popBackStack() }) }
        composable("privacy") { de.hr.mediathek.feature.overview.PrivacyScreen(onBack = { navController.popBackStack() }) }
        composable("background") { de.hr.mediathek.feature.overview.BackgroundScreen(onBack = { navController.popBackStack() }) }
        composable("packages") { de.hr.mediathek.feature.pkg.PackageScreen(onBack = { navController.popBackStack() }) }
        composable("trash") { TrashScreen(onBack = { navController.popBackStack() }) }
        composable("duplicates") {
            DuplicatesScreen(
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate("player/$it") },
            )
        }
        composable(
            route = "player/{uuid}?original={original}",
            arguments = listOf(
                navArgument("uuid") { type = NavType.StringType },
                navArgument("original") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            PlayerScreen(
                onSwitchVersion = { id, original ->
                    navController.popBackStack()
                    navController.navigate("player/$id?original=$original")
                },
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
                    it != Destination.Images && it != Destination.Stories && it != Destination.Products && it != Destination.Tracker && it != Destination.Search
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
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)) {
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
    // Feste Breite: die Trennlinien füllen sonst die ganze Zeile und der Inhalt daneben hat keinen Platz mehr.
    NavigationRail(modifier = Modifier.width(80.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            var previousGroup = Destination.entries.first().group
            Destination.entries.forEach { destination ->
                if (destination.group != previousGroup) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    previousGroup = destination.group
                }
                NavigationRailItem(
                    selected = currentRoute == destination.route,
                    onClick = { navController.navigateTopLevel(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                )
            }
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
