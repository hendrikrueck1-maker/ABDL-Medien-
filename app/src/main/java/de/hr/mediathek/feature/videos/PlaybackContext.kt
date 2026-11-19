package de.hr.mediathek.feature.videos

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merkt sich, aus welcher Liste ein Video geöffnet wurde (Filter, Sortierung, Seed).
 * Der Player nutzt das für "Nächstes" und das automatische Weiterspielen, auch bei Playlists und Zufall.
 */
@Singleton
class PlaybackContext @Inject constructor() {
    @Volatile var filter: VideoFilter? = null
    @Volatile var seed: Long = 0L
}
