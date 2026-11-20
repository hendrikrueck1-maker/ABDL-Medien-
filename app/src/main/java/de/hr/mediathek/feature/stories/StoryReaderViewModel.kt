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
)

data class ReaderUiState(
    val story: StoryEntity? = null,
    val paragraphs: List<String> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class StoryReaderViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: StoryRepository,
    private val settingDao: SettingDao,
) : ViewModel() {
    private val uuid: String = checkNotNull(handle["uuid"])

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state

    val tags: StateFlow<List<String>> = repository.tagNames(uuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _settings = MutableStateFlow(ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings

    init {
        viewModelScope.launch {
            val story = repository.get(uuid)
            val paragraphs = withContext(Dispatchers.Default) { TextCleaner.paragraphs(repository.text(uuid)) }
            _state.value = ReaderUiState(story, paragraphs, true)
            _settings.value = ReaderSettings(
                fontSize = settingDao.get(K_SIZE)?.toFloatOrNull() ?: 18f,
                lineSpacing = settingDao.get(K_LINE)?.toFloatOrNull() ?: 1.5f,
                theme = settingDao.get(K_THEME)?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.LIGHT,
                font = settingDao.get(K_FONT)?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
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
        }
    }

    fun saveProgress(paragraph: Int, offset: Int) {
        viewModelScope.launch { repository.saveReading(uuid, paragraph, offset) }
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
    }
}
