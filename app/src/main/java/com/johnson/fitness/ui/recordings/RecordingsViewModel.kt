package com.johnson.fitness.ui.recordings

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johnson.fitness.FitnessApp
import com.johnson.fitness.data.ImuCsvStore
import com.johnson.fitness.data.MovieRepository
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.playback.PlaybackLaunchConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(RecordingsState())
    val state: StateFlow<RecordingsState> = _state.asStateFlow()

    private val _effect = Channel<RecordingsEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    // 這頁只列舉／刪除／摘要，從不錄製；videoPositionProvider 給不會被呼叫的 lambda 即可，
    // 不必為了這頁另外改 ImuCsvStore 的建構子（跟其他畫面一樣手動 DI，見 FitnessApp）。
    private val imuCsvStore = ImuCsvStore(
        context = application,
        deviceManager = (application as FitnessApp).deviceManager,
        videoPositionProvider = { null }
    )

    init {
        load()
    }

    fun onIntent(intent: RecordingsIntent) {
        when (intent) {
            is RecordingsIntent.ReplayClicked -> onReplayClicked(intent.recording)
            is RecordingsIntent.CourseChosenForReplay -> onCourseChosenForReplay(intent.movieId)
            is RecordingsIntent.DismissCourseDialog ->
                _state.update { it.copy(pendingReplaySelection = null) }
            is RecordingsIntent.DeleteRequested ->
                _state.update { it.copy(pendingDelete = intent.recording) }
            is RecordingsIntent.DeleteCancelled ->
                _state.update { it.copy(pendingDelete = null) }
            is RecordingsIntent.DeleteConfirmed -> onDeleteConfirmed()
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val movies = withContext(Dispatchers.IO) { MovieRepository.movies(getApplication()) }
            val recordings = withContext(Dispatchers.IO) { imuCsvStore.listRecordings() }
            val items = recordings.map { recording -> recording.toItem(movies) }
            _state.update {
                it.copy(
                    isLoading = false,
                    items = items,
                    storageLocationLabel = storageLocationLabel(),
                    scorableCourses = movies.filter(Movie::hasScoringData)
                )
            }
            // 摘要（筆數／時長）要讀整份 CSV，比列表本身慢很多；先讓列表用檔名/大小顯示出來，
            // 每一筆算完再各自回填，不要卡住整個列表的呈現。
            recordings.forEach { recording -> loadSummary(recording) }
        }
    }

    private fun loadSummary(recording: ImuCsvStore.Recording) {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { imuCsvStore.summarize(recording.uri) }.getOrNull()
            } ?: return@launch
            _state.update { state ->
                state.copy(
                    items = state.items.map { item ->
                        if (item.recording.uri == recording.uri) item.copy(summary = summary) else item
                    }
                )
            }
        }
    }

    private fun onReplayClicked(recording: ImuCsvStore.Recording) {
        val movie = recording.courseId?.let { courseId ->
            MovieRepository.movies(getApplication()).find { it.courseId == courseId }
        }
        if (movie != null) {
            viewModelScope.launch {
                _effect.send(
                    RecordingsEffect.Replay(
                        movieId = movie.id,
                        config = PlaybackLaunchConfig.ReplayCsv(recording.uri, recording.fileName)
                    )
                )
            }
        } else {
            // 舊檔名沒有 courseId、或課程已經從目錄拿掉：讓使用者自己選一支有 .maf 的課程再回放。
            _state.update { it.copy(pendingReplaySelection = recording) }
        }
    }

    private fun onCourseChosenForReplay(movieId: Long) {
        val recording = _state.value.pendingReplaySelection ?: return
        _state.update { it.copy(pendingReplaySelection = null) }
        viewModelScope.launch {
            _effect.send(
                RecordingsEffect.Replay(
                    movieId = movieId,
                    config = PlaybackLaunchConfig.ReplayCsv(recording.uri, recording.fileName)
                )
            )
        }
    }

    private fun onDeleteConfirmed() {
        val recording = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { imuCsvStore.deleteRecording(recording.uri) }
            if (deleted) {
                _state.update { state ->
                    state.copy(items = state.items.filterNot { it.recording.uri == recording.uri })
                }
                _effect.send(RecordingsEffect.ShowToast("已刪除 ${recording.fileName}"))
            } else {
                _effect.send(RecordingsEffect.ShowToast("刪除失敗：${recording.fileName}"))
            }
        }
    }

    private fun ImuCsvStore.Recording.toItem(movies: List<Movie>): RecordingItem {
        val title = courseId?.let { id -> movies.find { it.courseId == id }?.title }
        return RecordingItem(recording = this, courseTitle = title)
    }

    private fun storageLocationLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "下載／ActivityScoringPlayer"
        } else {
            "App 專屬目錄（需用 adb 取回）"
        }
}
