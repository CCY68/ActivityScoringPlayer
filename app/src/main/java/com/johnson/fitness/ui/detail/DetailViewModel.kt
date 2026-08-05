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
            relatedMovies = MovieRepository.movies.shuffled().take(10),
            isLoading = false
        )
    }

    fun onIntent(intent: DetailIntent) {
        when (intent) {
            is DetailIntent.WatchTrailer ->
                viewModelScope.launch { _effect.send(DetailEffect.NavigateToPlayback) }
            is DetailIntent.Rent ->
                viewModelScope.launch { _effect.send(DetailEffect.ShowToast("Rent: ${_state.value.movie?.title}")) }
            is DetailIntent.Buy ->
                viewModelScope.launch { _effect.send(DetailEffect.ShowToast("Buy: ${_state.value.movie?.title}")) }
            is DetailIntent.RelatedMovieClicked ->
                viewModelScope.launch { _effect.send(DetailEffect.NavigateToDetail(intent.movie.id)) }
        }
    }
}
