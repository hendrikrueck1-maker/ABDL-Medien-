package de.hr.mediathek.data.story.mdimport

/** Drei Beispieldateien: vollständig, minimal (ohne Tags/Autor) und ein Duplikat der ersten (anderer Name, andere Zeilenenden). */
object Samples {
    const val FULL_NAME = "content/stories/2026/08/2026-08-12-die-reise-ans-meer.md"
    val FULL = """
        ---
        title: Die Reise ans Meer
        category: abenteuer
        tags: [meer, freundschaft]
        status: published
        author: Anna
        createdAt: 2026-08-12T10:00:00.000Z
        updatedAt: 2026-08-13T11:30:00.000Z
        publishedAt: 2026-08-14T08:00:00.000Z
        ---
        Es war einmal ein kleines Boot...

        Und es fuhr hinaus.
    """.trimIndent() + "\n"

    const val MINIMAL_NAME = "content/stories/2026/09/2026-09-03-kurz.md"
    val MINIMAL = "---\ntitle: Kurz und knapp\n---\nNur ein Satz.\n"

    const val DUPLICATE_NAME = "content/stories/2026/08/kopie-der-reise.md"
    /** Gleicher Text wie [FULL], aber Windows-Zeilenenden und ein anderer Titel. */
    val DUPLICATE = ("---\r\ntitle: Kopie der Reise\r\n---\r\nEs war einmal ein kleines Boot...\r\n\r\nUnd es fuhr hinaus.\r\n\r\n")
}
