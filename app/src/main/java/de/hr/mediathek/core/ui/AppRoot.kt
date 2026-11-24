package de.hr.mediathek.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import de.hr.mediathek.core.ui.theme.LocalAppColors
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    // Link aus "Teilen" (Browser): gleich den Web-Import öffnen.
    val sharedLink by ShareInbox.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(sharedLink) {
        val link = sharedLink ?: return@LaunchedEffect
        ShareInbox.pending.value = null
        // Eine schon offene Web-Import-Seite mit altem Link zuerst schließen, damit der neue Link frisch geladen wird.
        navController.popBackStack("story-import-web?url={url}", inclusive = true)
        navController.navigate("story-import-web?url=${android.net.Uri.encode(link)}")
    }

    AppBackground(hearts = showsHearts(route)) {
    if (isWide) {
        Row(Modifier.fillMaxSize()) {
            if (!fullScreenRoute) AppNavigationRail(navController)
            Scaffold(Modifier.weight(1f), containerColor = Color.Transparent) { padding ->
                ScreenPanel(fullScreenRoute, route in CARD_ROUTES, storyAlpha(route), Modifier.padding(padding)) { AppNavHost(navController, Modifier.fillMaxSize()) }
            }
        }
    } else {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { if (!fullScreenRoute) AppBottomBar(navController) },
        ) { padding ->
            ScreenPanel(fullScreenRoute, route in CARD_ROUTES, storyAlpha(route), Modifier.padding(padding)) { AppNavHost(navController, Modifier.fillMaxSize()) }
        }
    }
    }
}

/** Schwebende Herzen nur auf Übersicht und in den Listen, nie im Vollbild, Leser oder Editor. */
/** Bildschirme, die nur aus deckenden Karten bestehen: dort bleibt das große Fenster durchsichtig (Hintergrundbild sichtbar). */
private val CARD_ROUTES = setOf(Destination.Home.route, Destination.Videos.route, Destination.Tracker.route)

private fun showsHearts(route: String): Boolean =
    route in setOf(Destination.Home.route, Destination.Videos.route, Destination.Images.route, Destination.Stories.route, Destination.Products.route)

private fun isFullScreenRoute(route: String?): Boolean =
    route != null && (route.startsWith("player/") || route.startsWith("image/{") || route.startsWith("slideshow") || route.startsWith("story/{"))

/**
 * Deckende Fläche hinter jedem normalen Bildschirm, mit Rand, damit das Hintergrundbild an den Seiten sichtbar bleibt.
 * Schrift liegt nie direkt auf dem Bild. Vollbild-Seiten (Player, Bilder) bleiben unverändert;
 * der Inhalt behält dabei seinen Platz im Baum, damit beim Wechsel nichts neu aufgebaut wird.
 */
/** Geschichten-Übersicht: deckend (1,0); übrige Geschichten-Teile 0,8; sonst null. */
private fun storyAlpha(route: String): Float? = when {
    route == Destination.Stories.route -> 1f
    route.startsWith("stories") || route.startsWith("story") -> 0.8f
    else -> null
}

@Composable
private fun ScreenPanel(fullScreen: Boolean, transparent: Boolean, storyAlpha: Float?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Geschichten-Teile: dunkle Flächen nur zu 20 % durchsichtig (Alpha 0,8) statt 60 %.
    val base = LocalAppColors.current
    val colors = if (storyAlpha != null) base.copy(
        bar = base.bar.copy(alpha = storyAlpha ?: 1f), card = base.card.copy(alpha = storyAlpha ?: 1f), cardInset = base.cardInset.copy(alpha = storyAlpha ?: 1f),
    ) else base
    androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides colors) {
        ScreenPanelBody(fullScreen, transparent, modifier, content)
    }
}

@Composable
private fun ScreenPanelBody(fullScreen: Boolean, transparent: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val panel = if (fullScreen || transparent) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(LocalAppColors.current.bar)
    }
    Box(modifier.then(panel)) {
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
                onOpenStory = { navController.navigate("story/$it") },
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
                onDuplicates = { navController.navigate("story-duplicates") },
                onSeriesSuggest = { navController.navigate("story-series-suggest") },
                onTools = { navController.navigate("story-tools") },
                onEditSeries = { navController.navigate("series-edit/$it") },
            )
        }
        composable("story-duplicates") {
            de.hr.mediathek.feature.stories.DuplicatesScreen(
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate("story/$it") },
            )
        }
        composable("series-edit/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) {
            de.hr.mediathek.feature.stories.SeriesEditScreen(
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate("story/$it") },
            )
        }
        composable("story-tools") {
            de.hr.mediathek.feature.stories.StoryToolsScreen(onBack = { navController.popBackStack() })
        }
        composable("story-series-suggest") {
            de.hr.mediathek.feature.stories.SeriesSuggestScreen(onBack = { navController.popBackStack() })
        }
        composable("story-import") {
            de.hr.mediathek.feature.stories.StoryImportScreen(
                onBack = { navController.popBackStack() },
                onMarkdown = { navController.navigate("story-import-md") },
                onWeb = { navController.navigate("story-import-web") },
                onLinks = { navController.navigate("story-import-links") },
            )
        }
        composable("story-import-links") {
            de.hr.mediathek.feature.stories.LinkListScreen(onBack = { navController.popBackStack() })
        }
        composable(
            "story-import-web?url={url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            de.hr.mediathek.feature.stories.WebImportScreen(
                onBack = { navController.popBackStack() },
                onLogin = { navController.navigate("story-import-web-login?url=${android.net.Uri.encode(it)}") },
            )
        }
        composable(
            "story-import-web-login?url={url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            de.hr.mediathek.feature.stories.WebLoginScreen(
                url = entry.arguments?.getString("url").orEmpty(),
                onDone = { navController.popBackStack() },
            )
        }
        composable("story-import-md") {
            de.hr.mediathek.feature.stories.MarkdownImportScreen(onBack = { navController.popBackStack() })
        }
        composable("story/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) {
            de.hr.mediathek.feature.stories.StoryReaderScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("story-edit/$it") },
                onEditSeries = { navController.navigate("series-edit/$it") },
                onOpenImage = { navController.navigate("image/$it") },
                onOpenStory = { id ->
                    navController.navigate("story/$id") { popUpTo("story/{uuid}") { inclusive = true } }
                },
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

/** Modulfarbe je Bereich (aktiver Eintrag in der Navigation). */
@Composable
private fun Destination.moduleColor(): Color {
    val c = LocalAppColors.current
    return when (this) {
        Destination.Images -> c.hellblau
        Destination.Videos -> c.mint
        Destination.Products -> c.gold
        Destination.Tracker -> c.lavendel
        else -> c.rosa
    }
}

/** Ein Eintrag der Navigation: Icon (bei aktiv auf Pille in Modulfarbe) und Beschriftung. */
@Composable
private fun NavItem(destination: Destination, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    val color = destination.moduleColor()
    val label = stringResource(destination.label)
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .defaultMinSize(minWidth = 64.dp, minHeight = 56.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 32.dp).clip(CircleShape).background(if (selected) color else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(destination.icon, contentDescription = null, tint = if (selected) c.onAccent else c.onCardVariant)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) color else c.onCardVariant,
            maxLines = 1,
        )
    }
}

/** Handy: schwebende Leiste als Pille mit 4 Einträgen. */
@Composable
private fun AppBottomBar(navController: NavHostController) {
    val currentRoute = navController.currentRoute()
    val c = LocalAppColors.current
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(12.dp)
            .fillMaxWidth()
            .height(70.dp)
            .clip(RoundedCornerShape(35.dp))
            .background(c.bar),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Destination.entries.filter { it.inCompactBar }.forEach { destination ->
            NavItem(destination, currentRoute == destination.route, { navController.navigateTopLevel(destination) })
        }
    }
}

/** Tablet: schwebende Rail links mit allen Bereichen, Einstellungen unten. */
@Composable
private fun AppNavigationRail(navController: NavHostController) {
    val currentRoute = navController.currentRoute()
    val c = LocalAppColors.current
    Column(
        Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
            .width(104.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(32.dp))
            .background(c.bar)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Destination.entries.filter { it != Destination.Settings }.forEach { destination ->
                NavItem(destination, currentRoute == destination.route, { navController.navigateTopLevel(destination) })
            }
        }
        NavItem(Destination.Settings, currentRoute == Destination.Settings.route, { navController.navigateTopLevel(Destination.Settings) })
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
