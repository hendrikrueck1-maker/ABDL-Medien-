package de.hr.mediathek.core.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
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
) {
    Home("home", R.string.nav_home, Icons.Filled.Home, true),
    Stories("stories", R.string.nav_stories, Icons.Filled.Create, false),
    Images("images", R.string.nav_images, Icons.Filled.AccountBox, false),
    Videos("videos", R.string.nav_videos, Icons.Filled.PlayArrow, false),
    Products("products", R.string.nav_products, Icons.Filled.ShoppingCart, false),
    Tracker("tracker", R.string.nav_tracker, Icons.Filled.DateRange, true),
    Search("search", R.string.nav_search, Icons.Filled.Search, true),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings, true),
}
