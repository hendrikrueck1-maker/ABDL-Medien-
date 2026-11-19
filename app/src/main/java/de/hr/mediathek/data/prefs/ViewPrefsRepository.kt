package de.hr.mediathek.data.prefs

import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** "Alle anzeigen" oder "Nach Ordnern", wie bei der Ordner-Gruppierung im VLC-Beispiel. */
enum class BrowseMode { ALL, FOLDERS }

enum class CardLayout { LIST, TILES }

/** Ansichtseinstellungen eines Bereichs (Videos, Bilder, Alle Medien); werden je Bereich gemerkt. */
data class ViewSettings(
    val mode: BrowseMode = BrowseMode.FOLDERS,
    val layout: CardLayout = CardLayout.LIST,
    /** Dauer, Auflösung, Größe, Format usw. anzeigen. */
    val showDetails: Boolean = true,
)

@Singleton
class ViewPrefsRepository @Inject constructor(
    private val settings: SettingDao,
) {
    fun observe(module: String, defaults: ViewSettings): Flow<ViewSettings> = combine(
        settings.observe(key(module, "mode")),
        settings.observe(key(module, "layout")),
        settings.observe(key(module, "details")),
    ) { mode, layout, details ->
        ViewSettings(
            mode = mode?.let { runCatching { BrowseMode.valueOf(it) }.getOrNull() } ?: defaults.mode,
            layout = layout?.let { runCatching { CardLayout.valueOf(it) }.getOrNull() } ?: defaults.layout,
            showDetails = when (details) {
                "1" -> true
                "0" -> false
                else -> defaults.showDetails
            },
        )
    }

    suspend fun setMode(module: String, mode: BrowseMode) =
        settings.set(SettingEntity(key(module, "mode"), mode.name))

    suspend fun setLayout(module: String, layout: CardLayout) =
        settings.set(SettingEntity(key(module, "layout"), layout.name))

    suspend fun setShowDetails(module: String, value: Boolean) =
        settings.set(SettingEntity(key(module, "details"), if (value) "1" else "0"))

    private fun key(module: String, name: String) = "view_${module}_$name"
}
