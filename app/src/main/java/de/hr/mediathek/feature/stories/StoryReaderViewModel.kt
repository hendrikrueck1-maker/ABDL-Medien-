package de.hr.mediathek.feature.stories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import de.hr.mediathek.data.db.StoryEntity
import de.hr.mediathek.data.story.StoryRepository
import de.hr.mediathek.data.story.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ReaderTheme(val label: String) { LIGHT("Hell"), DARK("Nacht"), SEPIA("Sepia") }
enum class ReaderFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineSpacing: Float = 1.75f,
    val theme: ReaderTheme = ReaderTheme.DARK,
    val font: ReaderFont = ReaderFont.SERIF,
    /** Bild neben dem Text (nur auf breiten Bildschirmen). */
    val splitOn: Boolean = true,
    /** Anteil der Breite für die Bilder (0,2 bis 0,7). */
    val splitFraction: Float = 0.4f,
    val slideSeconds: Int = 10,
    /** Bilder neben dem Text in Zufallsreihenfolge (sonst nach Pfad). */
    val slideRandom: Boolean = true,
    /** Auto-Scroll in dp pro Sekunde. */
    val scrollSpeed: Float = 40f,
)

data class ReaderUiState(
    val story: StoryEntity? = null,
    val paragraphs: List<String> = emptyList(),
    val loaded: Boolean = false,
)

/** Das Bild neben dem Text samt Ordner und Stand der Reihe. */
data class ReaderImage(
    val bitmap: android.graphics.Bitmap? = null,
    val uuid: String? = null,
    /** Oberster Ordner; leer = alle Ordner. */
    val folder: String = "",
    val position: Int = 0,
    val total: Int = 0,
    val loading: Boolean = true,
)

/** Mehrteilige Geschichte: Name, alle Teile in Reihenfolge und Position des aktuellen Teils. */
data class SeriesUi(val name: String, val parts: List<de.hr.mediathek.data.db.SeriesPart>, val index: Int)

@HiltViewModel
class StoryReaderViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: StoryRepository,
    private val settingDao: SettingDao,
    private val mediaDao: de.hr.mediathek.data.db.MediaFileDao,
    private val sourceDao: de.hr.mediathek.data.db.SourceDao,
    private val imageLoader: de.hr.mediathek.data.images.ImageLoader,
) : ViewModel() {
    private val uuid: String = checkNotNull(handle["uuid"])

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state

    val tags: StateFlow<List<String>> = repository.tagNames(uuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val seriesUuid = MutableStateFlow<String?>(null)
    private val seriesName = MutableStateFlow("")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val series: StateFlow<SeriesUi?> = seriesUuid
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.seriesParts(id) }
        .combine(seriesName) { parts, name ->
            val i = parts.indexOfFirst { it.uuid == uuid }
            if (parts.size >= 2 && i >= 0) SeriesUi(name, parts, i) else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _settings = MutableStateFlow(ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings

    init {
        viewModelScope.launch { settingDao.set(SettingEntity("stories_last_open", uuid)) }
        viewModelScope.launch {
            val story = repository.get(uuid)
            val paragraphs = withContext(Dispatchers.Default) { TextCleaner.paragraphs(repository.text(uuid)) }
            _state.value = ReaderUiState(story, paragraphs, true)
            story?.seriesUuid?.let { id ->
                seriesName.value = repository.seriesName(id) ?: ""
                seriesUuid.value = id
            }
            _settings.value = ReaderSettings(
                fontSize = settingDao.get(K_SIZE)?.toFloatOrNull() ?: 19f,
                lineSpacing = settingDao.get(K_LINE)?.toFloatOrNull() ?: 1.75f,
                theme = settingDao.get(K_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.DARK,
                font = settingDao.get(K_FONT)?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
                splitOn = settingDao.get(K_SPLIT_ON) != "0",
                splitFraction = (settingDao.get(K_SPLIT)?.toFloatOrNull() ?: 0.4f).coerceIn(0.25f, 0.65f),
                slideSeconds = (settingDao.get(K_SLIDE)?.toIntOrNull() ?: 10).coerceIn(3, 120),
                slideRandom = settingDao.get(K_RANDOM) != "0",
                scrollSpeed = (settingDao.get(K_SCROLL)?.toFloatOrNull() ?: 40f).coerceIn(10f, 300f),
            )
        }
    }

    fun setSettings(s: ReaderSettings) {
        val randomChanged = _settings.value.slideRandom != s.slideRandom
        _settings.value = s
        if (randomChanged) loadImageList()
        viewModelScope.launch {
            settingDao.set(SettingEntity(K_SIZE, s.fontSize.toString()))
            settingDao.set(SettingEntity(K_LINE, s.lineSpacing.toString()))
            settingDao.set(SettingEntity(K_THEME, s.theme.name))
            settingDao.set(SettingEntity(K_FONT, s.font.name))
            settingDao.set(SettingEntity(K_SPLIT_ON, if (s.splitOn) "1" else "0"))
            settingDao.set(SettingEntity(K_SPLIT, s.splitFraction.toString()))
            settingDao.set(SettingEntity(K_SLIDE, s.slideSeconds.toString()))
            settingDao.set(SettingEntity(K_RANDOM, if (s.slideRandom) "1" else "0"))
            settingDao.set(SettingEntity(K_SCROLL, s.scrollSpeed.toString()))
        }
    }

    // --- Lesezeichen ---
    val bookmarks: StateFlow<List<de.hr.mediathek.data.db.StoryBookmarkEntity>> = repository.observeBookmarks(uuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addBookmark(paragraph: Int) {
        val label = _state.value.paragraphs.getOrNull(paragraph)?.trim()?.replace(Regex("\\s+"), " ")?.take(60).orEmpty()
        viewModelScope.launch { repository.addBookmark(uuid, paragraph, label) }
    }

    fun deleteBookmark(id: String) = viewModelScope.launch { repository.deleteBookmark(id) }

    // --- Bilder neben dem Text ---
    private var uuids: List<String> = emptyList()
    private var order: de.hr.mediathek.data.images.SlideshowOrder? = null
    private var shown = 1
    private var started = false
    private var listJob: kotlinx.coroutines.Job? = null
    private var showJob: kotlinx.coroutines.Job? = null
    private var folder = ""
    private val trees = HashMap<String, android.net.Uri?>()

    private val _image = MutableStateFlow(ReaderImage())
    val image: StateFlow<ReaderImage> = _image

    /** Oberste Bilderordner zur Auswahl. */
    val imageFolders: StateFlow<List<String>> = mediaDao.observeImageFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Beim ersten Anzeigen des Bildbereichs: gemerkten Ordner dieser Geschichte (sonst den zuletzt gewählten) laden. */
    fun startImages() {
        if (started) return
        started = true
        viewModelScope.launch {
            folder = settingDao.get(K_FOLDER + uuid) ?: settingDao.get(K_FOLDER_LAST) ?: ""
            loadImageList()
        }
    }

    /** Wählt den Bildordner; er wird für diese Geschichte und als Standard für die nächsten gemerkt. */
    fun setImageFolder(name: String) {
        folder = name
        viewModelScope.launch {
            settingDao.set(SettingEntity(K_FOLDER + uuid, name))
            settingDao.set(SettingEntity(K_FOLDER_LAST, name))
        }
        loadImageList()
    }

    private fun loadImageList() {
        if (!started) return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val f = folder
            uuids = withContext(Dispatchers.IO) { if (f.isEmpty()) mediaDao.onlineImageUuids() else mediaDao.onlineImageUuidsIn(f) }
            order = if (uuids.isEmpty()) null else de.hr.mediathek.data.images.SlideshowOrder(uuids.size, _settings.value.slideRandom)
            shown = 1
            _image.value = ReaderImage(folder = f, total = uuids.size, loading = false)
            showCurrent()
        }
    }

    private fun showCurrent() {
        val o = order ?: return
        val id = uuids.getOrNull(o.current) ?: return
        showJob?.cancel()
        showJob = viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { loadBitmap(id) }.getOrNull() }
            if (bmp == null) {
                // Nicht lesbar: einfach weiter zum nächsten.
                if (uuids.size > 1) { o.next(); showCurrent() }
                return@launch
            }
            _image.update { it.copy(bitmap = bmp, uuid = id, position = shown.coerceIn(1, it.total.coerceAtLeast(1))) }
        }
    }

    private suspend fun loadBitmap(id: String): android.graphics.Bitmap? {
        val media = mediaDao.getByUuid(id) ?: return null
        val docId = media.documentIdCache ?: return null
        val tree = trees.getOrPut(media.sourceUuid) { sourceDao.getByUuid(media.sourceUuid)?.treeUri?.let { android.net.Uri.parse(it) } }
            ?: return null
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val info = imageLoader.info(uri) ?: return null
        return imageLoader.decodeSampled(uri, info, 1600)
    }

    fun nextImage() {
        val o = order ?: return
        o.next()
        shown = if (shown >= uuids.size) 1 else shown + 1
        showCurrent()
    }

    fun previousImage() {
        val o = order ?: return
        o.previous()
        shown = if (shown <= 1) uuids.size else shown - 1
        showCurrent()
    }

    fun saveProgress(paragraph: Int, offset: Int) {
        viewModelScope.launch { repository.saveReading(uuid, paragraph, offset, _state.value.paragraphs.size) }
    }

    fun setRating(rating: Int) = edit { it.copy(rating = if (it.rating == rating) 0 else rating) }
    fun toggleFavorite() = edit { it.copy(favorite = !it.favorite) }
    fun toggleContinue() = edit { it.copy(continueFlag = !it.continueFlag) }

    private fun edit(block: (StoryEntity) -> StoryEntity) {
        val current = _state.value.story ?: return
        val updated = block(current)
        _state.update { it.copy(story = updated) }
        viewModelScope.launch { repository.update(updated) }
    }

    private companion object {
        const val K_SIZE = "reader_font_size"
        const val K_LINE = "reader_line_spacing"
        const val K_THEME = "reader_theme"
        const val K_FONT = "reader_font"
        const val K_SPLIT_ON = "reader_split_on"
        const val K_SPLIT = "reader_split_fraction"
        const val K_SLIDE = "reader_slide_seconds"
        const val K_SCROLL = "reader_scroll_speed"
        const val K_RANDOM = "reader_slide_random"
        const val K_FOLDER = "reader_image_folder_"
        const val K_FOLDER_LAST = "reader_image_folder_last"
    }
}
