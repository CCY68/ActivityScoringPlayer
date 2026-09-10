package com.johnson.fitness.ui.home

import com.johnson.fitness.model.Movie

data class HomeState(
    val categories: List<HomeCategory> = emptyList(),
    val backgroundUrl: String = "",
    val isLoading: Boolean = true
)

data class HomeCategory(
    val name: String,
    val movies: List<Movie>
)

sealed class HomeIntent {
    data class MovieFocused(val movie: Movie) : HomeIntent()
    data class MovieClicked(val movie: Movie) : HomeIntent()
    object ErrorClicked : HomeIntent()
}

sealed class HomeEffect {
    data class NavigateToDetail(val movieId: Long) : HomeEffect()
    object NavigateToError : HomeEffect()
}
