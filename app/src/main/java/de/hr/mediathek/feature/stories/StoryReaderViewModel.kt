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

enum class ReaderTheme(val label: String) { LIGHT("Hell"), DARK("Dunkel"), SEPIA("Sepia") }
enum class ReaderFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

data class ReaderSettings(
    val fontSize: Float = 18f,
    val lineSpacing: Float = 1.5f,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val font: ReaderFont = ReaderFont.SERIF,
    /** Bild neben dem Text (nur auf breiten Bildschirmen). */
    val splitOn: Boolean = true,
    /** Anteil der Breite für die Bilder (0,2 bis 0,7). */
    val splitFraction: Float = 0.4f,
    val slideSeconds: Int = 10,
    /** Auto-Scroll in dp pro Sekunde. */
    val scrollSpeed: Float = 40f,
)

data class ReaderUiState(
    val story: StoryEntity? = null,
    val paragraphs: List<String> = emptyList(),
    val loaded: Boolean = false,
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
        viewModelScope.launch {
            val story = repository.get(uuid)
            val paragraphs = withContext(Dispatchers.Default) { TextCleaner.paragraphs(repository.text(uuid)) }
            _state.value = ReaderUiState(story, paragraphs, true)
            story?.seriesUuid?.let { id ->
                seriesName.value = repository.seriesName(id) ?: ""
                seriesUuid.value = id
            }
            _settings.value = ReaderSettings(
                fontSize = settingDao.get(K_SIZE)?.toFloatOrNull() ?: 18f,
                lineSpacing = settingDao.get(K_LINE)?.toFloatOrNull() ?: 1.5f,
                theme = settingDao.get(K_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.LIGHT,
                font = settingDao.get(K_FONT)?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
                splitOn = settingDao.get(K_SPLIT_ON) != "0",
                splitFraction = (settingDao.get(K_SPLIT)?.toFloatOrNull() ?: 0.4f).coerceIn(0.2f, 0.7f),
                slideSeconds = (settingDao.get(K_SLIDE)?.toIntOrNull() ?: 10).coerceIn(3, 120),
                scrollSpeed = (settingDao.get(K_SCROLL)?.toFloatOrNull() ?: 40f).coerceIn(10f, 300f),
            )
        }
    }

    fun setSettings(s: ReaderSettings) {
        _settings.value = s
        viewModelScope.launch {
            settingDao.set(SettingEntity(K_SIZE, s.fontSize.toString()))
            settingDao.set(SettingEntity(K_LINE, s.lineSpacing.toString()))
            settingDao.set(SettingEntity(K_THEME, s.theme.name))
            settingDao.set(SettingEntity(K_FONT, s.font.name))
            settingDao.set(SettingEntity(K_SPLIT_ON, if (s.splitOn) "1" else "0"))
            settingDao.set(SettingEntity(K_SPLIT, s.splitFraction.toString()))
            settingDao.set(SettingEntity(K_SLIDE, s.slideSeconds.toString()))
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

    // --- Diashow neben dem Text ---
    private val history = ArrayList<android.graphics.Bitmap>()
    private var historyPos = -1
    private val _image = MutableStateFlow<android.graphics.Bitmap?>(null)
    val image: StateFlow<android.graphics.Bitmap?> = _image
    private var loadingImage = false

    private suspend fun loadRandomImage(): android.graphics.Bitmap? {
        val id = mediaDao.randomImageUuid() ?: return null
        val media = mediaDao.getByUuid(id) ?: return null
        val docId = media.documentIdCache ?: return null
        val tree = sourceDao.getByUuid(media.sourceUuid)?.treeUri?.let { android.net.Uri.parse(it) } ?: return null
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val info = imageLoader.info(uri) ?: return null
        return imageLoader.decodeSampled(uri, info, 1600)
    }

    /** Nächstes Zufallsbild (oder wieder vor, wenn man zurückgeblättert hat). */
    fun nextImage() {
        if (historyPos < history.lastIndex) {
            historyPos++
            _image.value = history[historyPos]
            return
        }
        if (loadingImage) return
        loadingImage = true
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { runCatching { loadRandomImage() }.getOrNull() } ?: return@launch
                history += bmp
                if (history.size > 4) history.removeAt(0)
                historyPos = history.lastIndex
                _image.value = bmp
            } finally {
                loadingImage = false
            }
        }
    }

    fun previousImage() {
        if (historyPos > 0) {
            historyPos--
            _image.value = history[historyPos]
        }
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
    }
}
