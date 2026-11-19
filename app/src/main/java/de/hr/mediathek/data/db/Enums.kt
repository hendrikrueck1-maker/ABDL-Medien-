package de.hr.mediathek.data.db

import java.util.UUID

fun newUuid(): String = UUID.randomUUID().toString()

fun nowMillis(): Long = System.currentTimeMillis()

enum class SourceRole { MEDIA, BACKUP }
enum class ThumbVariant { NORMAL, SMALL }
enum class MediaType { VIDEO, IMAGE }
enum class DateSource { EXIF, CONTAINER, LASTMODIFIED }
enum class MediaStatus { ONLINE, OFFLINE, DEFECT, TRASHED }
enum class SeenState { UNSEEN, IN_PROGRESS, SEEN }
enum class CategoryScope { VIDEO, IMAGE, PRODUCT, ALL }
enum class EntityType { MEDIA, STORY, PRODUCT }
enum class StoryCategory { MEN, WOMEN, MIXED }
enum class PlaylistType { MANUAL, SMART }
enum class CriterionValueType { NUMBER, TEXT, BOTH }
enum class PhotoKind { PACKAGE, DETAIL, WORN_DRY, WORN_WET }
enum class JobType { SCAN, THUMBNAILS, COMPRESS, PACKAGE_EXPORT, PACKAGE_IMPORT, PDF_IMPORT, BACKUP, PHASH, MIRROR_COPY }
enum class JobStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
