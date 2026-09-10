package com.johnson.fitness.ui.detail

import com.johnson.fitness.model.Movie

data class DetailState(
    val movie: Movie? = null,
    val relatedMovies: List<Movie> = emptyList(),
    val isLoading: Boolean = true
)

sealed class DetailIntent {
    /** 按「開始課程」；畫面接著跳出播放模式（B20／Replay CSV）選擇對話框。 */
    object StartCourse : DetailIntent()
    data class RelatedMovieClicked(val movie: Movie) : DetailIntent()
}

sealed class DetailEffect {
    object NavigateToPlayback : DetailEffect()
    data class NavigateToDetail(val movieId: Long) : DetailEffect()
}
