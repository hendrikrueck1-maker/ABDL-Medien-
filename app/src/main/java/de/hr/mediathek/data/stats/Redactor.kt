package de.hr.mediathek.data.stats

/** Macht Dateinamen, Pfade und Adressen in Fehlertexten unkenntlich, damit eine Vorschau teilbar ist. */
object Redactor {
    private val uri = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://\S+""")
    private val path = Regex("""(?<![\w])/(?:[^\s/:]+/)*[^\s/:]*""")
    /** Dateinamen auch mit Leerzeichen („Urlaub am See.mp4“, bis 5 Wörter); lieber zu viel als zu wenig unkenntlich machen. */
    private val fileName = Regex(
        """(?:[^\s/\\:*?"<>|,;()\[\]]+ ){0,4}[^\s/\\:*?"<>|,;()\[\]]+\.(?:mp4|mkv|avi|mov|m4v|webm|3gp|wmv|flv|ts|mts|m2ts|mpg|mpeg|ogv|jpg|jpeg|png|gif|webp|heic|heif|avif|bmp|pdf|txt|md|json|docx|zip|ndjson|part|tmp)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Dokument-IDs der Speicherkarte (z. B. „1A2B-3C4D:Videos/Name mit Leerzeichen.mp4“) bis zum Satzzeichen bzw. Zeilenende. */
    private val tree = Regex("""\b(?:primary|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}):[^\n,;)\]]+""")

    fun redact(text: String): String =
        text.replace(uri, "<adresse>").replace(tree, "<pfad>").replace(path, "<pfad>").replace(fileName, "<datei>")
}
