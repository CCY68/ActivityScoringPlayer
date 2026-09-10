package com.johnson.fitness.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnson.fitness.data.MovieRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DetailViewModel(private val movieId: Long) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private val _effect = Channel<DetailEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        _state.value = DetailState(
            movie = MovieRepository.getMovieById(movieId),
            // 「其他課程」就是目錄裡除了本堂以外的課，維持固定順序（不亂數、不重複填充）。
            relatedMovies = MovieRepository.movies.filter { it.id != movieId },
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
