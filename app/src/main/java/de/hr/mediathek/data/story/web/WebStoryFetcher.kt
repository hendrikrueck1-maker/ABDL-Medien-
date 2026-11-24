package de.hr.mediathek.data.story.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** [loginNeeded]: Die Seite zeigt ohne Anmeldung keinen Text; die App bietet dann die Anmeldung an. */
class WebFetchException(message: String, val loginNeeded: Boolean = false) : Exception(message)

/** Lädt eine Seite (bei Foren alle Seiten des Themas) und liefert die Kapitel. Nur für den privaten Gebrauch gedacht. */
@Singleton
class WebStoryFetcher @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {

    suspend fun load(rawUrl: String, onPage: (Int) -> Unit = {}): WebThread = withContext(Dispatchers.IO) {
        val start = normalize(rawUrl)
        val pages = ArrayList<WebPage>()
        val seen = HashSet<String>()
        var next: String? = start
        while (next != null && pages.size < MAX_PAGES && seen.add(next)) {
            coroutineContext.ensureActive()
            val page = PageParser.parse(download(next))
            pages += page
            onPage(pages.size)
            next = page.nextUrl
            if (next != null) delay(PAGE_DELAY_MS)
        }
        val first = pages.first()
        if (pages.all { it.posts.isEmpty() }) {
            throw WebFetchException(
                if (first.loginRequired) "Die Seite verlangt eine Anmeldung. Ohne Anmeldung ist der Text nicht lesbar."
                else "Auf der Seite wurde kein Text erkannt.",
                loginNeeded = first.loginRequired,
            )
        }
        val thread = ThreadBuilder.build(start, pages)
        if (next != null && pages.size >= MAX_PAGES) {
            thread.copy(warnings = thread.warnings + "Nach $MAX_PAGES Seiten gestoppt. Das Thema ist länger.")
        } else {
            thread
        }
    }

    private fun userAgent(): String =
        runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }.getOrDefault(USER_AGENT)

    /** Link bereinigen: https voranstellen, http auf https umstellen; wirft [WebFetchException] bei ungültigem Link. */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "https://$url"
        // Android blockiert unverschlüsselte Verbindungen, fast alle Seiten gehen aber auch über https.
        if (url.startsWith("http://", true)) url = "https://" + url.substring(7)
        try {
            URL(url)
        } catch (e: Exception) {
            throw WebFetchException("Das ist kein gültiger Link.")
        }
        return url
    }

    private fun download(url: String): Document {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw WebFetchException("Verbindung nicht möglich: ${e.message ?: "unbekannter Fehler"}")
        }
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // Dieselbe Kennung wie die Anmelde-Ansicht, damit Foren, die die Sitzung daran binden, sie akzeptieren.
            conn.setRequestProperty("User-Agent", userAgent())
            // Anmeldung, die der Nutzer in der eingebauten Ansicht gemacht hat.
            runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.6")
            val code = conn.responseCode
            when {
                code == 401 || code == 403 -> throw WebFetchException("Die Seite verweigert den Zugriff ($code). Vermutlich ist eine Anmeldung nötig.", loginNeeded = true)
                code == 404 -> throw WebFetchException("Seite nicht gefunden (404).")
                code == 429 -> throw WebFetchException("Die Seite bremst zu viele Anfragen (429). Später erneut versuchen.")
                code !in 200..299 -> throw WebFetchException("Die Seite antwortet mit Fehler $code.")
            }
            val charset = conn.contentType?.let { Regex("""(?i)charset=["']?([\w-]+)""").find(it)?.groupValues?.get(1) }
            val bytes = conn.inputStream.use { readLimited(it) }
            return Jsoup.parse(ByteArrayInputStream(bytes), charset, conn.url.toString())
        } catch (e: WebFetchException) {
            throw e
        } catch (e: IOException) {
            throw WebFetchException("Seite konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw WebFetchException("Die Seite ist größer als 8 MB.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_PAGES = 60
        const val PAGE_DELAY_MS = 700L
        const val MAX_BYTES = 8 * 1024 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
