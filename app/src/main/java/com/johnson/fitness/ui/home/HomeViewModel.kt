package com.johnson.fitness.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnson.fitness.data.MovieRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _effect = Channel<HomeEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        loadContent()
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.MovieFocused ->
                _state.value = _state.value.copy(backgroundUrl = intent.movie.backgroundImageUrl)
            is HomeIntent.MovieClicked ->
                viewModelScope.launch { _effect.send(HomeEffect.NavigateToDetail(intent.movie.id)) }
            is HomeIntent.ErrorClicked ->
                viewModelScope.launch { _effect.send(HomeEffect.NavigateToError) }
        }
    }

    private fun loadContent() {
        // 課程清單寫死在 MovieRepository（Demo 無後端），首頁只是照課程分類分列。
        val categories = MovieRepository.moviesByCategory().map { (name, movies) ->
            HomeCategory(name = name, movies = movies)
        }
        _state.value = HomeState(
            categories = categories,
            backgroundUrl = MovieRepository.movies.firstOrNull()?.backgroundImageUrl.orEmpty(),
            isLoading = false
        )
    }
}
