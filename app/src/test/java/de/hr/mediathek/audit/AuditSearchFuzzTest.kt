package de.hr.mediathek.audit

import de.hr.mediathek.data.search.SearchQuery
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random

/**
 * Fuzz-Test der Suchsyntax gegen eine echte FTS4-Tabelle (sqlite-jdbc, gleicher Tabellenaufbau wie SearchIndexer).
 * Hinweis: Die SQLite-Version/Compile-Optionen auf Android können abweichen; der Test zeigt Fehler im erzeugten MATCH-Ausdruck.
 */
class AuditSearchFuzzTest {

    companion object {
        private lateinit var conn: Connection

        @BeforeClass @JvmStatic fun setUp() {
            conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            conn.createStatement().use { st ->
                st.execute(
                    "CREATE VIRTUAL TABLE search_index USING fts4(kind, uuid, title, body, notindexed=kind, notindexed=uuid, tokenize=unicode61)",
                )
                val rows = listOf(
                    "Nachtwindel Test" to "forma-care Größe M",
                    "T-Shirt Bericht" to "Café Straße Übung",
                    "video_00001.mp4" to "ordner/unterordner",
                    "Geschichte" to "ein \"zitat\" mit OR und NEAR",
                )
                rows.forEachIndexed { i, (t, b) ->
                    conn.prepareStatement("INSERT INTO search_index(kind, uuid, title, body) VALUES ('STORY', ?, ?, ?)").use { ps ->
                        ps.setString(1, "u$i"); ps.setString(2, t); ps.setString(3, b); ps.executeUpdate()
                    }
                }
            }
        }

        @AfterClass @JvmStatic fun tearDown() = conn.close()
    }

    /** Führt die gleiche Abfrage aus wie SearchIndexer.search. Gibt die Treffer-UUIDs zurück. */
    private fun run(input: String): List<String>? {
        val parsed = SearchQuery.parse(input) ?: return null
        val sb = StringBuilder("SELECT uuid, snippet(search_index, '[', ']', '…', -1, 12) FROM search_index WHERE search_index MATCH ?")
        parsed.excluded.forEach { _ -> sb.append(" AND uuid NOT IN (SELECT uuid FROM search_index WHERE search_index MATCH ?)") }
        sb.append(" LIMIT 200")
        conn.prepareStatement(sb.toString()).use { ps ->
            ps.setString(1, parsed.match)
            parsed.excluded.forEachIndexed { i, ex -> ps.setString(i + 2, ex) }
            ps.executeQuery().use { rs ->
                val out = ArrayList<String>()
                while (rs.next()) out += rs.getString(1)
                return out
            }
        }
    }

    @Test fun syntax_examples() {
        assertEquals(listOf("u0"), run("nacht"))
        assertEquals(listOf("u0"), run("forma-care"))
        assertEquals(listOf("u1"), run("t-shirt"))
        assertEquals(listOf("u1"), run("cafe"))
        assertEquals(listOf("u1"), run("übung"))
    }

    @Test fun umlaut_folding() {
        assertEquals(listOf("u1"), run("ubung"))
        assertEquals(listOf("u0"), run("grosse"))
    }

    @Test fun eszett_folding_strasse() {
        assertEquals(listOf("u1"), run("strasse"))
    }

    @Test fun exclusion_and_phrase() {
        assertEquals(emptyList<String>(), run("nacht -forma"))
        assertEquals(listOf("u3"), run("\"zitat mit\""))
        assertEquals(listOf("u3"), run("or near"))
    }

    @Test fun fuzz_random_inputs_never_throw() {
        val alphabet = "aä ß*\"'\\%_;-()ORNEAR AND NOT ^:~{}[]|!?.,/€😀​‮\u0000\t\n"
        val rnd = Random(4711)
        val failures = ArrayList<String>()
        repeat(5000) {
            val len = rnd.nextInt(0, 60)
            val s = buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            try { run(s) } catch (e: Exception) { failures += "${s.replace("\n", "\\n")} -> ${e.message}" }
        }
        val fixed = listOf(
            "", " ", "-", "--", "\"", "\"\"", "\"a", "a\"", "-\"", "* OR NEAR", "a OR b", "NEAR(a b)", "a*b*", "'; DROP TABLE x; --",
            "%s %d {0}", "😀😀", "\u0000", " ".repeat(10_000), "-".repeat(10_000), "a".repeat(10_000),
        )
        for (s in fixed) try { run(s) } catch (e: Exception) { failures += "${s.take(30)}… -> ${e.message}" }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    /** 10.000 Zeichen aus vielen kurzen Wörtern (z. B. eingefügter Text). */
    @Test fun very_many_terms_do_not_throw() {
        val s = (1..5000).joinToString(" ") { "a" }
        run(s)
        val s2 = (1..2500).joinToString(" ") { "w$it" }
        run(s2)
    }
}
