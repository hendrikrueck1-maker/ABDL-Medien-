package de.hr.mediathek.data.trash

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Kleine SAF-Hilfen für Ordner-Navigation und Verschieben, rein auf DocumentsContract aufgebaut. */
object SafOps {
    class Child(val docId: String, val name: String, val isDir: Boolean)

    fun children(resolver: ContentResolver, tree: Uri, parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val result = ArrayList<Child>()
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                result += Child(
                    c.getString(0), c.getString(1) ?: "",
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return result
    }

    /** Geht von der Wurzel Segment für Segment nach unten. null, wenn ein Ordner fehlt. */
    fun resolveDir(resolver: ContentResolver, tree: Uri, relativeDir: String): String? {
        var current = DocumentsContract.getTreeDocumentId(tree)
        if (relativeDir.isEmpty()) return current
        for (segment in relativeDir.split('/')) {
            current = children(resolver, tree, current).firstOrNull { it.isDir && it.name == segment }?.docId
                ?: return null
        }
        return current
    }

    /** Findet oder legt einen Unterordner an. Neu angelegte Papierkorb-Ordner bekommen sofort ein .nomedia. */
    fun findOrCreateDir(resolver: ContentResolver, tree: Uri, parentDocId: String, name: String, noMedia: Boolean): String? {
        children(resolver, tree, parentDocId).firstOrNull { it.isDir && it.name == name }?.let { return it.docId }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
        val created = DocumentsContract.createDocument(
            resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: return null
        val docId = DocumentsContract.getDocumentId(created)
        if (noMedia) {
            runCatching { DocumentsContract.createDocument(resolver, created, "application/octet-stream", ".nomedia") }
        }
        return docId
    }

    /** Verschiebt per Dokument-Move (kein Kopieren: bei großen Videos wäre das viel zu langsam). Gibt die neue ID zurück. */
    fun move(resolver: ContentResolver, tree: Uri, docId: String, fromParentId: String, toParentId: String): String? {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val from = DocumentsContract.buildDocumentUriUsingTree(tree, fromParentId)
        val to = DocumentsContract.buildDocumentUriUsingTree(tree, toParentId)
        val moved = DocumentsContract.moveDocument(resolver, doc, from, to) ?: return null
        return DocumentsContract.getDocumentId(moved)
    }

    fun delete(resolver: ContentResolver, tree: Uri, docId: String): Boolean =
        runCatching {
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
        }.getOrDefault(false)

    class Stat(val name: String, val size: Long, val modified: Long?)

    /** Name, Größe und Änderungszeit eines Dokuments; null, wenn es nicht existiert oder nicht lesbar ist. */
    fun stat(resolver: ContentResolver, tree: Uri, docId: String): Stat? = try {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, docId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) Stat(c.getString(0) ?: "", c.getLong(1), if (c.isNull(2)) null else c.getLong(2)) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Ohne erreichbare Wurzel (Karte entfernt) wird nie etwas als "gelöscht" oder "fehlend" angenommen. */
    fun rootReachable(resolver: ContentResolver, tree: Uri): Boolean = try {
        val root = DocumentsContract.getTreeDocumentId(tree)
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, root),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }
}
