package de.hr.mediathek.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Datenmodell laut Projekt-Dokument "Datenmodell" (verbindlich).
 * - Jede Entität mit geräteübergreifender Identität hat eine UUID zusätzlich zur lokalen ID.
 * - createdAt/updatedAt: technisch. userUpdatedAt: nur echte Nutzeränderungen (maßgeblich für Merge).
 * - deletedAt: Tombstone. Alle Abfragen müssen deletedAt IS NULL berücksichtigen.
 * - Zeiten: Millisekunden seit 1970 (UTC).
 */

@Entity(tableName = "source", indices = [Index("uuid", unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val role: SourceRole,
    val label: String,
    val volumeUuid: String?,
    val treeUri: String,
    val lastMountedAt: Long? = null,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "media_file",
    indices = [
        Index("uuid", unique = true),
        Index("sourceUuid", "relativePath"),
        Index("fingerprint"),
        Index("status"),
        Index("type", "displayName"),
        Index("type", "createdAt"),
        Index("type", "dateDetermined"),
        Index("type", "rating"),
        Index("type", "size"),
        Index("type", "durationMs"),
        Index("contentHash"),
    ],
)
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val sourceUuid: String,
    val type: MediaType,
    val relativePath: String,
    /** Nur für die Performance, nie eine Identität. */
    val documentIdCache: String? = null,
    val displayName: String,
    val size: Long,
    val fingerprint: String? = null,
    val mimeType: String? = null,
    val dateDetermined: Long? = null,
    val dateSource: DateSource? = null,
    /** Änderungszeit der Datei (ms), für das schnelle Erkennen unveränderter Dateien beim Scan. */
    val lastModified: Long? = null,
    val status: MediaStatus = MediaStatus.ONLINE,
    val missingScanCount: Int = 0,
    val lastSeenScanUuid: String? = null,
    val rating: Int = 0,
    val favorite: Boolean = false,
    /** Vom Nutzer zum Löschen vorgemerkt (nur eine Markierung, gelöscht wird nichts). */
    @ColumnInfo(defaultValue = "0") val markedDelete: Boolean = false,
    /** Eigene Markierung 💧. */
    @ColumnInfo(defaultValue = "0") val markedPee: Boolean = false,
    /** Wie oft das Video geöffnet wurde (für die Vorschläge in der Übersicht). */
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    // Video
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
    val container: String? = null,
    val resumePositionMs: Long = 0,
    val seenState: SeenState = SeenState.UNSEEN,
    // Bild
    val perceptualHash: Long? = null,
    /** SHA-256 des ganzen Dateiinhalts (nur Bilder), für exakte Duplikate. */
    val contentHash: String? = null,
    /** Fingerabdruck, für den die Hashes berechnet wurden; weicht er ab, wird neu berechnet. */
    val hashFingerprint: String? = null,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

/** Frühere Fingerabdrücke einer Datei (z. B. vor der Komprimierung). */
@Entity(tableName = "media_alias", indices = [Index("uuid", unique = true), Index("mediaUuid"), Index("fingerprint")])
data class MediaAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val mediaUuid: String,
    val fingerprint: String,
    val validFrom: Long = nowMillis(),
    val createdAt: Long = nowMillis(),
)

@Entity(tableName = "category", indices = [Index("uuid", unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val scope: CategoryScope,
    val name: String,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(tableName = "tag", indices = [Index("uuid", unique = true), Index("nameNormalized")])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val name: String,
    val nameNormalized: String,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "category_assignment",
    indices = [Index("uuid", unique = true), Index("entityType", "entityUuid"), Index("categoryUuid")],
)
data class CategoryAssignmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val entityType: EntityType,
    val entityUuid: String,
    val categoryUuid: String,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "tag_assignment",
    indices = [Index("uuid", unique = true), Index("entityType", "entityUuid"), Index("tagUuid")],
)
data class TagAssignmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val entityType: EntityType,
    val entityUuid: String,
    val tagUuid: String,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(tableName = "series", indices = [Index("uuid", unique = true)])
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val name: String,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "story",
    indices = [
        Index("uuid", unique = true),
        Index("category"),
        Index("seriesUuid"),
        Index("textHash"),
        Index("title"),
        Index("createdAt"),
        Index("rating"),
        Index("slug", unique = true),
        Index("importHash"),
    ],
)
data class StoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val title: String,
    val sourceText: String? = null,
    val sourceUrl: String? = null,
    val category: StoryCategory,
    val rating: Int = 0,
    val favorite: Boolean = false,
    val continueFlag: Boolean = false,
    val seriesUuid: String? = null,
    val seriesOrder: Int? = null,
    val textHash: String? = null,
    val readingParagraph: Int = 0,
    val readingOffset: Int = 0,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    /** Stabile Kennung aus dem Markdown-Import (Dateiname ohne .md); null bei selbst angelegten Geschichten. */
    val slug: String? = null,
    val author: String? = null,
    /** "draft", "published" o. ä. aus dem Import; null bei selbst angelegten Geschichten. */
    val status: String? = null,
    val publishedAt: Long? = null,
    /** SHA-256 über den normalisierten Text, nur für den Import (Duplikaterkennung). */
    val importHash: String? = null,
)

/** Eigene Tabelle, damit die Liste nie den Text lädt (CursorWindow-Limit). */
@Entity(tableName = "story_text", primaryKeys = ["storyUuid"])
data class StoryTextEntity(
    val storyUuid: String,
    val text: String,
)

@Entity(tableName = "story_draft_version", indices = [Index("uuid", unique = true), Index("storyUuid")])
data class StoryDraftVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val storyUuid: String,
    val text: String,
    val savedAt: Long = nowMillis(),
)

@Entity(tableName = "playlist", indices = [Index("uuid", unique = true)])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val name: String,
    val type: PlaylistType,
    val filterJson: String? = null,
    val filterVersion: Int = 1,
    val randomSeed: Long? = null,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(tableName = "playlist_item", indices = [Index("uuid", unique = true), Index("playlistUuid", "position"), Index("mediaUuid")])
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val playlistUuid: String,
    val mediaUuid: String,
    val position: Int,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(tableName = "brand", indices = [Index("uuid", unique = true), Index("name")])
data class BrandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val name: String,
    /** Alternative Schreibweisen, durch Zeilenumbruch getrennt. */
    val aliases: String = "",
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "product",
    indices = [Index("uuid", unique = true), Index("brandUuid"), Index("title"), Index("stars"), Index("createdAt")],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val title: String,
    val brandUuid: String? = null,
    val sizeLabel: String? = null,
    val rawPriceText: String? = null,
    val rawUnitPriceText: String? = null,
    val unitPriceMin: Double? = null,
    val unitPriceMax: Double? = null,
    val unitPriceOverride: Double? = null,
    val packSize: Int? = null,
    val weightEmptyG: Double? = null,
    val weightFullG: Double? = null,
    val sizesAvailableText: String? = null,
    val wouldBuyAgain: Boolean? = null,
    val overallNote: String? = null,
    val stars: Int? = null,
    val starsManual: Boolean = false,
    val favorite: Boolean = false,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

/** Die Kriterien der Vorlage als Daten, nicht als Spalten. */
@Entity(tableName = "criterion_catalog", indices = [Index("uuid", unique = true), Index("key", unique = true)])
data class CriterionCatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val key: String,
    val label: String,
    val groupName: String,
    val valueType: CriterionValueType,
    val sortOrder: Int,
    val catalogVersion: Int,
)

@Entity(tableName = "product_criterion_value", indices = [Index("uuid", unique = true), Index("productUuid"), Index("criterionUuid")])
data class ProductCriterionValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val productUuid: String,
    val criterionUuid: String? = null,
    /** Für Zusatzkriterien aus "Nicht zugeordnet". */
    val customLabel: String? = null,
    val numberValue: Int? = null,
    val textValue: String? = null,
    val note: String? = null,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(tableName = "product_photo", indices = [Index("uuid", unique = true), Index("productUuid", "sortOrder")])
data class ProductPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val productUuid: String,
    val kind: PhotoKind,
    val rowIndex: Int = 0,
    /** Pfad im Arbeitsordner. */
    val filePath: String,
    val fingerprint: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

@Entity(tableName = "tracker_entry", indices = [Index("uuid", unique = true), Index("productUuid"), Index("instant")])
data class TrackerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    /** Wird beim Löschen des Produkts null; der Snapshot bleibt. */
    val productUuid: String? = null,
    val productNameSnapshot: String,
    val unitPriceSnapshot: Double? = null,
    val instant: Long,
    val zoneId: String,
    val note: String? = null,
    val createdAt: Long = nowMillis(),
    val updatedAt: Long = createdAt,
    val userUpdatedAt: Long = createdAt,
    val deletedAt: Long? = null,
)

/** Einzige Wahrheit für Zustand und Fortschritt von Hintergrundarbeiten. */
@Entity(tableName = "job", indices = [Index("uuid", unique = true), Index("status", "priority")])
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val type: JobType,
    val status: JobStatus = JobStatus.QUEUED,
    val phase: String? = null,
    val priority: Int = 0,
    val paramsJson: String = "{}",
    val progress: Float? = null,
    val lastError: String? = null,
    val createdAt: Long = nowMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

@Entity(tableName = "compression_record", indices = [Index("jobUuid"), Index("mediaUuid")])
data class CompressionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobUuid: String,
    val mediaUuid: String,
    val phase: String,
    val tempPath: String? = null,
    val altPath: String? = null,
    val deleteAfter: Long? = null,
    // Ab Datenbankversion 4: alles, was das Phasen-Journal zum Fortsetzen braucht.
    val sourceUuid: String? = null,
    val relativePath: String? = null,
    val origName: String? = null,
    val targetName: String? = null,
    val level: String? = null,
    val origDocId: String? = null,
    val newDocId: String? = null,
    val localTemp: String? = null,
    val oldFingerprint: String? = null,
    val newFingerprint: String? = null,
    val origSize: Long? = null,
    val newSize: Long? = null,
    val note: String? = null,
)

@Entity(tableName = "trash_item", indices = [Index("uuid", unique = true), Index("expiresAt")])
data class TrashItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val entityType: EntityType,
    val entityUuid: String,
    val originalPath: String? = null,
    val trashPath: String? = null,
    val deletedAt: Long = nowMillis(),
    val expiresAt: Long,
)

/** Gleiche Fehler werden zusammengefasst; höchstens 1000 Einträge, älteste zuerst entfernt. */
@Entity(tableName = "error_log", indices = [Index("type", "message"), Index("lastAt")])
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val message: String,
    val count: Int = 1,
    val firstAt: Long,
    val lastAt: Long,
)

@Entity(tableName = "setting", primaryKeys = ["key"])
data class SettingEntity(
    val key: String,
    val value: String,
)

/** Ein Eintrag je Vorschaubild im Cache (Datei liegt in filesDir/thumbs). Rein technisch, nie Nutzerdaten. */
@Entity(tableName = "thumbnail")
data class ThumbnailEntity(
    @PrimaryKey val mediaUuid: String,
    /** Fingerabdruck der Quelldatei zum Zeitpunkt der Erzeugung; weicht er ab, ist das Bild veraltet. */
    val fingerprint: String?,
    val variant: ThumbVariant,
    val sizeBytes: Long,
    val createdAt: Long,
    val lastAccess: Long,
)
