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

    /** Höchstgröße der Manifest-Datei (Schutz gegen kaputte oder riesige Dateien). */
    const val MANIFEST_MAX_BYTES = 8L * 1024 * 1024

    /** Liest höchstens [MANIFEST_MAX_BYTES]; größere Dateien gelten als ungültig. */
    fun readLimited(input: java.io.InputStream): String = input.use {
        val bytes = it.readNBytesCompat(MANIFEST_MAX_BYTES + 1)
        require(bytes.size <= MANIFEST_MAX_BYTES) { "Manifest zu groß" }
        bytes.toString(Charsets.UTF_8)
    }

    private fun java.io.InputStream.readNBytesCompat(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size.toLong(), max - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray()
    }

    fun decode(text: String): PackageManifest {
        val o = org.json.JSONObject(text)
        require(o.getInt("format") == FORMAT) { "Format unbekannt" }
        val arr = o.getJSONArray("files")
        val files = (0 until arr.length()).map {
            val f = arr.getJSONObject(it)
            ManifestFile(
                f.getString("mediaUuid"), f.getString("type"), f.getString("path"), f.getLong("size"),
                if (f.isNull("originalFingerprint")) null else f.getString("originalFingerprint"),
                f.getString("packageFingerprint"), f.optBoolean("compressed", false),
            )
        }
        return PackageManifest(o.getInt("schemaVersion"), o.optString("appVersion"), o.optLong("createdAt"), o.optString("name", "Paket"), files)
    }

    /**
     * Welche Dateien beim „Paket entfernen + Dateien löschen“ gelöscht werden dürfen: nur die im Manifest genannten
     * Paketdateien (samt liegengebliebener .part-Reste), das Manifest und die Metadaten. Nie fremde Dateien, nie Ordner mit Inhalt.
     */
    fun deletionPlan(manifest: PackageManifest): Set<String> {
        val plan = LinkedHashSet<String>()
        for (f in manifest.files) {
            if (!isSafePath(f.path)) continue
            val dir = f.path.substringBefore('/', "")
            if (dir != DIR_VIDEOS && dir != DIR_IMAGES) continue
            if (f.path.count { it == '/' } != 1) continue
            plan += f.path
            plan += f.path + ".part"
        }
        plan += MANIFEST_NAME
        plan += METADATA_NAME
        return plan
    }
}
