package de.hr.mediathek.feature.images

import de.hr.mediathek.feature.videos.VideoFilter
import javax.inject.Inject
import javax.inject.Singleton

/** Merkt sich, aus welcher Bilderliste geöffnet wurde (Filter, Seed), damit Vollbild und Diashow blättern können. */
@Singleton
class ImageContext @Inject constructor() {
    @Volatile var filter: VideoFilter = VideoFilter()
    @Volatile var seed: Long = 0L
}
