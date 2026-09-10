package com.johnson.fitness.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johnson.fitness.data.MovieRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首頁影片牆最前面那一列的名稱：跨分類把有 `.maf` 的課程集中，方便拿不同課程測評分。 */
private const val SCORABLE_RAIL_NAME = "可評分課程"

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _effect = Channel<HomeEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        loadContent(forceReload = false)
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.MovieFocused ->
                _state.value = _state.value.copy(backgroundUrl = intent.movie.backgroundImageUrl)
            is HomeIntent.MovieClicked ->
                viewModelScope.launch { _effect.send(HomeEffect.NavigateToDetail(intent.movie.id)) }
            is HomeIntent.ErrorClicked ->
                viewModelScope.launch { _effect.send(HomeEffect.NavigateToError) }
            is HomeIntent.Retry -> loadContent(forceReload = true)
        }
    }

    private fun loadContent(forceReload: Boolean) {
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            // 讀 assets 是阻塞 I/O，不要在 Main thread 做。
            val result = withContext(Dispatchers.IO) {
                MovieRepository.load(getApplication(), forceReload = forceReload)
            }
            when (result) {
                is MovieRepository.CatalogResult.Failure ->
                    _state.value = HomeState(isLoading = false, errorMessage = result.message)

                is MovieRepository.CatalogResult.Success -> {
                    val movies = result.movies
                    val scorable = movies.filter { it.hasScoringData }
                    val rails = buildList {
                        if (scorable.isNotEmpty()) {
                            add(
                                HomeCategory(
                                    name = SCORABLE_RAIL_NAME,
                                    movies = scorable,
                                    isScorableShortcut = true
                                )
                            )
                        }
                        // 其餘照目錄裡分類第一次出現的先後排列。
                        movies.groupBy { it.category }.forEach { (name, list) ->
                            add(HomeCategory(name = name, movies = list))
                        }
                    }
                    _state.value = HomeState(
                        categories = rails,
                        backgroundUrl = movies.firstOrNull()?.backgroundImageUrl.orEmpty(),
                        isLoading = false,
                        errorMessage = null,
                        courseCount = movies.size,
                        scorableCount = scorable.size
                    )
                }
            }
        }
    }
}
