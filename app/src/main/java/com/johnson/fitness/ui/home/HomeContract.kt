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
    data class PreferenceClicked(val label: String) : HomeIntent()
}

sealed class HomeEffect {
    data class NavigateToDetail(val movieId: Long) : HomeEffect()
    object NavigateToError : HomeEffect()
    data class ShowToast(val message: String) : HomeEffect()
}
