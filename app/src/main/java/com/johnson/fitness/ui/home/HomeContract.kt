package com.johnson.fitness.ui.home

import com.johnson.fitness.model.Movie

data class HomeState(
    val categories: List<HomeCategory> = emptyList(),
    val backgroundUrl: String = "",
    val isLoading: Boolean = true,
    /** 課程目錄載入失敗的說明；非 null 時首頁顯示錯誤畫面（不會靜默留白）。 */
    val errorMessage: String? = null,
    /** 目錄裡的課程總數與其中有 `.maf`（可評分）的支數，顯示在標題列。 */
    val courseCount: Int = 0,
    val scorableCount: Int = 0
)

data class HomeCategory(
    val name: String,
    val movies: List<Movie>,
    /** 這一列是「可評分課程」快捷列（跨分類集合），卡片標題不重覆顯示分類。 */
    val isScorableShortcut: Boolean = false
)

sealed class HomeIntent {
    data class MovieFocused(val movie: Movie) : HomeIntent()
    data class MovieClicked(val movie: Movie) : HomeIntent()
    object ErrorClicked : HomeIntent()
    /** 目錄載入失敗時的「重試」。 */
    object Retry : HomeIntent()
}

sealed class HomeEffect {
    data class NavigateToDetail(val movieId: Long) : HomeEffect()
    object NavigateToError : HomeEffect()
}
