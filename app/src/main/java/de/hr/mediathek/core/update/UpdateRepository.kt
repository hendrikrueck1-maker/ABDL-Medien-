package de.hr.mediathek.core.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class LatestRelease(val build: Int, val apkUrl: String, val apkSize: Long)

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Liefert das neueste Release oder null, wenn keins mit APK gefunden wurde. */
    suspend fun fetchLatest(): LatestRelease? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val connection = open(url)
        try {
            check(connection.responseCode == 200) { "GitHub antwortet mit ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val build = json.getString("tag_name").removePrefix("build-").toIntOrNull()
                ?: return@withContext null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk") && !name.contains("debug")) {
                    return@withContext LatestRelease(
                        build = build,
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.getLong("size"),
                    )
                }
            }
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Lädt das APK in den Cache und prüft die Größe. */
    suspend fun download(release: LatestRelease, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "update-${release.build}.apk")
            val connection = open(URL(release.apkUrl))
            try {
                check(connection.responseCode == 200) { "Download fehlgeschlagen (${connection.responseCode})" }
                val total = release.apkSize.coerceAtLeast(1L)
                var done = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                check(target.length() == release.apkSize) { "Datei unvollständig, bitte erneut versuchen" }
                target
            } catch (e: Exception) {
                target.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Mediathek-App")
        }
}
