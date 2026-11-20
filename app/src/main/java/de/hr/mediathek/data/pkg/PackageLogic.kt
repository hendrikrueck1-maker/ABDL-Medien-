package de.hr.mediathek.data.pkg

/** Eine Datei im Manifest eines Mobil-Pakets. */
data class ManifestFile(
    val mediaUuid: String,
    val type: String,
    /** Pfad relativ zum Paketordner, z. B. videos/ab12cd34_name.mp4 */
    val path: String,
    val size: Long,
    val originalFingerprint: String?,
    val packageFingerprint: String,
    val compressed: Boolean,
)

class PackageManifest(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val name: String,
    val files: List<ManifestFile>,
)

object PackageLogic {
    const val FORMAT = 1
    const val MANIFEST_NAME = "manifest.json"
    const val METADATA_NAME = "paket.zip"
    const val DIR_VIDEOS = "videos"
    const val DIR_IMAGES = "bilder"
    const val GB = 1024L * 1024L * 1024L

    class Sized(val uuid: String, val size: Long)

    /** Nimmt die Dateien in Reihenfolge, solange sie ins Limit passen; zu große Einzeldateien werden übersprungen. */
    fun truncate(items: List<Sized>, limitBytes: Long): Pair<List<Sized>, List<Sized>> {
        val kept = ArrayList<Sized>()
        val dropped = ArrayList<Sized>()
        var total = 0L
        for (it in items) {
            if (total + it.size <= limitBytes) { kept += it; total += it.size } else dropped += it
        }
        return kept to dropped
    }

    /** Dateiname im Paket: kurze UUID vorne (eindeutig), dann der bereinigte Originalname. */
    fun fileName(mediaUuid: String, displayName: String): String {
        val clean = displayName.map { if (it in "\\/:*?\"<>|\u0000" || it.isISOControl()) '_' else it }.joinToString("").trim().trim('.')
        val base = clean.ifEmpty { "datei" }.take(120)
        return mediaUuid.replace("-", "").take(8) + "_" + base
    }

    /** Ordnername aus dem Datum, z. B. ABDL-Paket-20260315. */
    fun folderName(now: java.time.LocalDate): String = "ABDL-Paket-" + now.toString().replace("-", "")

    /** Wo eine .part-Datei weitermacht: 0 = neu beginnen (fehlt, zu groß oder schon vollständig). */
    fun resumeOffset(partSize: Long?, sourceSize: Long): Long =
        if (partSize == null || partSize <= 0L || partSize >= sourceSize) 0L else partSize

    fun encode(m: PackageManifest): String {
        val sb = StringBuilder()
        sb.append("{\"format\":").append(FORMAT)
        sb.append(",\"schemaVersion\":").append(m.schemaVersion)
        sb.append(",\"appVersion\":").append(quote(m.appVersion))
        sb.append(",\"createdAt\":").append(m.createdAt)
        sb.append(",\"name\":").append(quote(m.name))
        sb.append(",\"files\":[")
        m.files.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"mediaUuid\":").append(quote(f.mediaUuid))
            sb.append(",\"type\":").append(quote(f.type))
            sb.append(",\"path\":").append(quote(f.path))
            sb.append(",\"size\":").append(f.size)
            sb.append(",\"originalFingerprint\":").append(f.originalFingerprint?.let { quote(it) } ?: "null")
            sb.append(",\"packageFingerprint\":").append(quote(f.packageFingerprint))
            sb.append(",\"compressed\":").append(f.compressed).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /** Pfad im Paket darf den Ordner nie verlassen. */
    fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.contains("\\") && path.split('/').none { it == ".." || it.isEmpty() }
}
