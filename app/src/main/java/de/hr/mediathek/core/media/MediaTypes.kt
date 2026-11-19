package de.hr.mediathek.core.media

import de.hr.mediathek.data.db.MediaType

/** Erkennt, welche Dateien die Mediathek einliest, und welche Ordner/Dateien ignoriert werden. */
object MediaTypes {
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "avi", "mov", "webm", "3gp", "ts", "mts", "m2ts", "wmv", "flv", "mpg", "mpeg", "ogv",
    )
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp",
    )

    fun detect(mime: String?, name: String): MediaType? {
        val m = mime?.lowercase().orEmpty()
        if (m.startsWith("video/")) return MediaType.VIDEO
        if (m.startsWith("image/")) return MediaType.IMAGE
        if (m.isEmpty() || m == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in videoExtensions) return MediaType.VIDEO
            if (ext in imageExtensions) return MediaType.IMAGE
        }
        return null
    }

    /** Versteckte Dateien, .tmp und .part (unfertige Kopien oder Komprimierung) werden nicht indexiert. */
    fun isIgnoredFile(name: String): Boolean {
        val lower = name.lowercase()
        return name.startsWith(".") || lower.endsWith(".tmp") || lower.endsWith(".part")
    }

    /** .trash, _alt und andere versteckte Ordner werden nicht indexiert. */
    fun isIgnoredDir(name: String): Boolean = name.startsWith(".") || name == "_alt"
}
