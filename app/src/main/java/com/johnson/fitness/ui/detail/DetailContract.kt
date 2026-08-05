package com.johnson.fitness.ui.detail

import com.johnson.fitness.model.Movie

data class DetailState(
    val movie: Movie? = null,
    val relatedMovies: List<Movie> = emptyList(),
    val isLoading: Boolean = true
)

sealed class DetailIntent {
    object WatchTrailer : DetailIntent()
    object Rent : DetailIntent()
    object Buy : DetailIntent()
    data class RelatedMovieClicked(val movie: Movie) : DetailIntent()
}

sealed class DetailEffect {
    object NavigateToPlayback : DetailEffect()
    data class NavigateToDetail(val movieId: Long) : DetailEffect()
    data class ShowToast(val message: String) : DetailEffect()
}
