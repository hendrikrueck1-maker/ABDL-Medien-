package de.hr.mediathek.data.stats

/** Macht Dateinamen, Pfade und Adressen in Fehlertexten unkenntlich, damit eine Vorschau teilbar ist. */
object Redactor {
    private val uri = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://\S+""")
    private val path = Regex("""(?<![\w])/(?:[^\s/:]+/)*[^\s/:]*""")
    private val fileName = Regex("""[^\s/\\:*?"<>|]+\.(?:mp4|mkv|avi|mov|m4v|webm|3gp|jpg|jpeg|png|gif|webp|heic|bmp|pdf|txt|docx|zip|ndjson|part|tmp)\b""", RegexOption.IGNORE_CASE)
    private val tree = Regex("""\b(?:primary|[0-9A-F]{4}-[0-9A-F]{4}):\S+""")

    fun redact(text: String): String =
        text.replace(uri, "<adresse>").replace(tree, "<pfad>").replace(path, "<pfad>").replace(fileName, "<datei>")
}
