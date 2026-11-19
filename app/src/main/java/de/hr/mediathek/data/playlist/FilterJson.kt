package de.hr.mediathek.data.playlist

import de.hr.mediathek.feature.videos.SeenFilter
import de.hr.mediathek.feature.videos.VideoFilter
import de.hr.mediathek.feature.videos.VideoSort
import org.json.JSONObject

/** Gespeicherte Filter für Smart Playlists. Die Versionsnummer erlaubt spätere Formatänderungen. */
object FilterJson {
    const val VERSION = 1

    fun encode(filter: VideoFilter): String {
        val o = JSONObject()
        o.put("sort", filter.sort.name)
        o.put("search", filter.search)
        o.put("favoriteOnly", filter.favoriteOnly)
        o.put("minRating", filter.minRating)
        o.put("seen", filter.seen.name)
        o.put("onlineOnly", filter.onlineOnly)
        filter.format?.let { o.put("format", it) }
        filter.categoryUuid?.let { o.put("categoryUuid", it) }
        filter.tagUuid?.let { o.put("tagUuid", it) }
        return o.toString()
    }

    /** Unbekannte oder fehlende Felder fallen auf Standardwerte zurück, damit alte Listen weiter funktionieren. */
    fun decode(json: String?, version: Int): VideoFilter {
        if (json.isNullOrBlank() || version > VERSION) return VideoFilter()
        return try {
            val o = JSONObject(json)
            VideoFilter(
                sort = runCatching { VideoSort.valueOf(o.optString("sort")) }.getOrDefault(VideoSort.ADDED_DESC)
                    .let { if (it == VideoSort.PLAYLIST_ORDER) VideoSort.ADDED_DESC else it },
                search = o.optString("search", ""),
                favoriteOnly = o.optBoolean("favoriteOnly", false),
                minRating = o.optInt("minRating", 0),
                seen = runCatching { SeenFilter.valueOf(o.optString("seen")) }.getOrDefault(SeenFilter.ALL),
                onlineOnly = o.optBoolean("onlineOnly", false),
                format = o.optString("format").ifBlank { null },
                categoryUuid = o.optString("categoryUuid").ifBlank { null },
                tagUuid = o.optString("tagUuid").ifBlank { null },
            )
        } catch (e: Exception) {
            VideoFilter()
        }
    }
}
