package de.hr.mediathek.data.story

/** Ein Kapitel einer Geschichte: Überschrift und Nummer des Absatzes, an dem es beginnt. */
data class Chapter(val title: String, val paragraphIndex: Int)

/**
 * Erkennt Kapitelüberschriften im Text, z. B. "Kapitel 3", "Chapter 12: Heimweg", "Teil II", "Prolog".
 * Reine Zahlen oder römische Ziffern allein auf einer Zeile zählen nur, wenn es mindestens drei davon gibt,
 * damit einzelne Zahlen im Text nicht als Kapitel durchgehen.
 */
object ChapterDetector {
    private val NUMBERED = Regex(
        "^(kapitel|chapter|teil|part|abschnitt|akt|buch)\\s*([0-9]{1,3}|[ivxlc]{1,7})\\b.{0,60}$",
        RegexOption.IGNORE_CASE,
    )
    private val NAMED = Regex(
        "^(prolog|epilog|prologue|epilogue|vorwort|nachwort|einleitung)\\b.{0,40}$",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_NUMBER = Regex("^[0-9]{1,3}[.):]?$")
    private val BARE_ROMAN = Regex("^[IVXLC]{1,7}[.):]?$")

    /** Entfernt Markdown-Zeichen und Zierstriche um eine Überschrift. */
    private fun clean(line: String): String =
        line.trim().trimStart('#', '*', '=', '-', '_', '~', ' ').trimEnd('*', '=', '-', '_', '~', ' ')

    fun detect(paragraphs: List<String>): List<Chapter> {
        val named = ArrayList<Chapter>()
        val bare = ArrayList<Chapter>()
        paragraphs.forEachIndexed { index, raw ->
            val firstLine = raw.lineSequence().firstOrNull()?.let(::clean).orEmpty()
            if (firstLine.isEmpty() || firstLine.length > 80) return@forEachIndexed
            if (NUMBERED.matches(firstLine) || NAMED.matches(firstLine)) {
                named += Chapter(firstLine, index)
            } else if (!raw.contains('\n') && (BARE_NUMBER.matches(firstLine) || BARE_ROMAN.matches(firstLine))) {
                bare += Chapter("Abschnitt " + firstLine.trimEnd('.', ')', ':'), index)
            }
        }
        return when {
            named.size >= 2 -> named
            bare.size >= 3 -> bare
            else -> emptyList()
        }
    }

    /** Ob der ganze Absatz nur die Überschrift ist (dann wird er größer und fett gezeigt). */
    fun isHeading(paragraph: String, chapters: List<Chapter>, index: Int): Boolean =
        chapters.any { it.paragraphIndex == index } && !paragraph.trim().contains('\n')
}
