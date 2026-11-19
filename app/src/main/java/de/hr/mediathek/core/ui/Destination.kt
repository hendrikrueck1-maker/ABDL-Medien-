package de.hr.mediathek.core.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector
import de.hr.mediathek.R

/**
 * Alle Hauptbereiche der App.
 * Tablet: Navigation Rail mit allen Bereichen.
 * Handy: untere Leiste nur mit den Einträgen, bei denen [inCompactBar] gesetzt ist;
 * die übrigen Bereiche erreicht man über die Übersicht.
 */
enum class Destination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val inCompactBar: Boolean,
    /** Gruppe in der Seitenleiste; zwischen Gruppen steht ein Trenner. */
    val group: Int,
) {
    Home("home", R.string.nav_home, Icons.Filled.Home, true, 0),
    Media("media", R.string.nav_media, Icons.Filled.List, true, 1),
    Videos("videos", R.string.nav_videos, Icons.Filled.PlayArrow, true, 1),
    Images("images", R.string.nav_images, Icons.Filled.AccountBox, true, 1),
    Stories("stories", R.string.nav_stories, Icons.Filled.Create, false, 2),
    Products("products", R.string.nav_products, Icons.Filled.ShoppingCart, false, 2),
    Tracker("tracker", R.string.nav_tracker, Icons.Filled.DateRange, false, 2),
    Search("search", R.string.nav_search, Icons.Filled.Search, false, 3),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings, true, 3),
}
