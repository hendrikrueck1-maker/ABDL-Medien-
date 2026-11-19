package de.hr.mediathek.core.media

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fingerabdruck = Größe + Hash über Anfang, Mitte und Ende (je 64 KB). Kleine Dateien werden ganz gelesen.
 * Er dient als Zuordnungshinweis (Umbenennen, Verschieben), nicht als Identität.
 */
@Singleton
class Fingerprinter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun compute(docUri: Uri, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(size.toString().toByteArray())
        context.contentResolver.openFileDescriptor(docUri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                if (size <= 3 * CHUNK) {
                    updateFrom(channel, 0L, size.toInt(), digest)
                } else {
                    val middle = size / 2 - CHUNK / 2
                    updateFrom(channel, 0L, CHUNK.toInt(), digest)
                    updateFrom(channel, middle, CHUNK.toInt(), digest)
                    updateFrom(channel, size - CHUNK, CHUNK.toInt(), digest)
                }
            }
        } ?: error("Datei nicht lesbar")
        val hash = digest.digest().take(16).joinToString("") { "%02x".format(it) }
        return "$size-$hash"
    }

    private fun updateFrom(channel: java.nio.channels.FileChannel, offset: Long, length: Int, digest: MessageDigest) {
        if (length <= 0) return
        val buffer = ByteBuffer.allocate(length)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        digest.update(buffer.array(), 0, buffer.position())
    }

    private companion object {
        const val CHUNK = 64L * 1024L
    }
}
