package de.hr.mediathek.data.product

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.storage.StorageRepository
import de.hr.mediathek.data.trash.SafOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Produktfotos liegen unverändert im Arbeitsordner unter produktfotos/<Produkt-UUID>/. */
@Singleton
class ProductPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageRepository,
) {
    /** Speichert Bytes und gibt den relativen Pfad zurück; null, wenn kein Arbeitsordner erreichbar ist. */
    suspend fun save(productUuid: String, name: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val tree = storage.getWorkFolderUri() ?: return@withContext null
            val resolver = context.contentResolver
            val root = DocumentsContract.getTreeDocumentId(tree)
            val photos = SafOps.findOrCreateDir(resolver, tree, root, DIR, true) ?: return@withContext null
            val dir = SafOps.findOrCreateDir(resolver, tree, photos, productUuid, true) ?: return@withContext null
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dir)
            val created = DocumentsContract.createDocument(resolver, parent, "image/jpeg", name) ?: return@withContext null
            resolver.openOutputStream(created, "w")?.use { it.write(bytes) } ?: return@withContext null
            "$DIR/$productUuid/$name"
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveFrom(productUuid: String, name: String, source: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        save(productUuid, name, bytes)
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        runCatching {
            val tree = storage.getWorkFolderUri() ?: return@runCatching
            val docId = findDocId(tree, path) ?: return@runCatching
            SafOps.delete(context.contentResolver, tree, docId)
        }
    }

    /** Lädt ein verkleinertes Bild; null, wenn die Datei fehlt (Karte nicht eingelegt) oder defekt ist. */
    suspend fun load(path: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val tree = storage.getWorkFolderUri() ?: return@withContext null
            val docId = findDocId(tree, path) ?: return@withContext null
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun findDocId(tree: Uri, path: String): String? {
        val resolver = context.contentResolver
        val dirPath = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        val dir = SafOps.resolveDir(resolver, tree, dirPath) ?: return null
        return SafOps.children(resolver, tree, dir).firstOrNull { !it.isDir && it.name == name }?.docId
    }

    companion object {
        const val DIR = "produktfotos"

        fun jpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }
    }
}
