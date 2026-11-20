package de.hr.mediathek.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediaFileEntity
import de.hr.mediathek.data.db.RecentStory
import de.hr.mediathek.data.db.StoryDao
import de.hr.mediathek.data.db.ThumbVariant
import de.hr.mediathek.data.suggest.Suggestion
import de.hr.mediathek.data.suggest.VideoSuggestions
import de.hr.mediathek.data.thumb.ThumbnailCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: MediaFileDao,
    private val thumbs: ThumbnailCache,
    private val stories: StoryDao,
) : ViewModel() {
    private val _suggestions = MutableStateFlow<List<Suggestion<MediaFileEntity>>>(emptyList())
    val suggestions: StateFlow<List<Suggestion<MediaFileEntity>>> = _suggestions

    private val _recent = MutableStateFlow<List<RecentStory>>(emptyList())
    val recent: StateFlow<List<RecentStory>> = _recent

    init { refresh() }

    /** Würfelt die Vorschläge neu; bei jedem Öffnen der Übersicht entsteht eine frische Auswahl. */
    fun refresh() {
        viewModelScope.launch {
            _recent.value = stories.recentStories(10)
            _suggestions.value = VideoSuggestions.pick(
                often = dao.suggestFrequent(8),
                rare = dao.suggestRare(8),
                never = dao.suggestNeverWatched(8),
                key = { it.uuid },
            )
        }
    }

    fun thumbnailOf(uuid: String): File? =
        thumbs.fileIfPresent(uuid, ThumbVariant.NORMAL) ?: thumbs.fileIfPresent(uuid, ThumbVariant.SMALL)
}
