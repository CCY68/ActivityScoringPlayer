package com.johnson.fitness.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johnson.fitness.data.MovieRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 「其他課程」最多顯示幾支；目錄有 60 支，全放進去只是把同一列拉到很長。 */
private const val RELATED_LIMIT = 12

class DetailViewModel(
    application: Application,
    private val movieId: Long
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private val _effect = Channel<DetailEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        val movies = MovieRepository.movies(getApplication())
        val movie = movies.find { it.id == movieId }
        // 「其他課程」先排同分類（同一類最容易接著上），再補其他課程；固定順序、不亂數、不重複。
        val others = movies.filter { it.id != movieId }
        val related = (others.filter { it.category == movie?.category } +
            others.filter { it.category != movie?.category })
            .take(RELATED_LIMIT)
        _state.value = DetailState(
            movie = movie,
            relatedMovies = related,
            isLoading = false
        )
    }

    fun onIntent(intent: DetailIntent) {
        when (intent) {
            is DetailIntent.StartCourse ->
                viewModelScope.launch { _effect.send(DetailEffect.NavigateToPlayback) }
            is DetailIntent.RelatedMovieClicked ->
                viewModelScope.launch { _effect.send(DetailEffect.NavigateToDetail(intent.movie.id)) }
        }
    }
}
