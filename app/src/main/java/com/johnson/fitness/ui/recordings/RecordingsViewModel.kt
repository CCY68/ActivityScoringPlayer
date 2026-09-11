package com.johnson.fitness.ui.recordings

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johnson.fitness.FitnessApp
import com.johnson.fitness.data.ImuCsvStore
import com.johnson.fitness.data.MovieRepository
import com.johnson.fitness.data.RecordingUploader
import com.johnson.fitness.data.UploadPreferences
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
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

    private val uploadPreferences: UploadPreferences = (application as FitnessApp).uploadPreferences
    private val uploader = RecordingUploader(application, uploadPreferences)

    // 單一上傳佇列：單筆點擊與「全部上傳」都經 enqueueUpload() 取得這把鎖才會真的打網路，
    // 同一時間只有一筆在跑，避免兩條路徑把同一個檔案排兩次（見 enqueueUpload 的說明）。
    private val uploadMutex = Mutex()

    init {
        load()
        // adb 灌值／設定頁改完網址、token 後，停在這頁的畫面也要跟著更新（不用重新整頁）。
        viewModelScope.launch {
            uploadPreferences.configured.collect { configured ->
                _state.update { it.copy(uploadConfigured = configured) }
            }
        }
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
            is RecordingsIntent.UploadClicked -> onUploadClicked(intent.recording)
            is RecordingsIntent.UploadAllClicked -> onUploadAllClicked()
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
                    // uploadConfigured 不在這裡設：init 已經在收集 uploadPreferences.configured，
                    // 一律由那個 Flow 更新，避免兩個地方各自寫一次互相打架。
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

    private fun onUploadClicked(recording: ImuCsvStore.Recording) {
        viewModelScope.launch { enqueueUpload(recording) }
    }

    /**
     * 只排入目前列表裡還沒成功上傳過、也還沒在排隊／上傳中的那些，逐一序列進行
     * （中繼是單機 Apps Script，同時打好幾支沒有意義）。
     */
    private fun onUploadAllClicked() {
        val pending = _state.value.items.filter {
            it.uploadStatus == UploadStatus.NOT_UPLOADED || it.uploadStatus == UploadStatus.FAILED
        }
        if (pending.isEmpty()) return
        // 立刻標成 QUEUED：一來讓「全部上傳」按鈕馬上被鎖住（RecordingsScreen 靠 items 的狀態判斷要不要
        // disable，不等第一筆真的開始跑），二來讓這幾列馬上顯示「等待上傳」而不是空等到輪到才有反應。
        pending.forEach { item ->
            updateItem(item.recording) { it.copy(uploadStatus = UploadStatus.QUEUED, uploadError = null) }
        }
        viewModelScope.launch {
            pending.forEachIndexed { index, item ->
                _state.update { it.copy(uploadAllProgress = UploadAllProgress(index + 1, pending.size)) }
                enqueueUpload(item.recording, alreadyQueued = true)
            }
            _state.update { it.copy(uploadAllProgress = null) }
        }
    }

    /**
     * 單一上傳佇列的入口：單筆點擊與「全部上傳」都經這裡，真正的網路呼叫用 [uploadMutex] 序列化，
     * 同一時間只有一筆在跑——避免單筆上傳中又被「全部上傳」把同一檔排一次，或反過來。
     *
     * 入列前先查一次該檔目前狀態，UPLOADING／QUEUED 就跳過（已經在處理中，不要重複排）；
     * [alreadyQueued] 為 true 代表呼叫端（[onUploadAllClicked]）已經先標成 QUEUED，這裡不用再標一次，
     * 但取得鎖、真的輪到這一筆時還是會重查一次最新狀態——例如排隊期間這筆被刪除了，就不會再去上傳它。
     */
    private suspend fun enqueueUpload(recording: ImuCsvStore.Recording, alreadyQueued: Boolean = false) {
        if (!alreadyQueued) {
            val current = currentItem(recording) ?: return
            if (current.uploadStatus == UploadStatus.UPLOADING || current.uploadStatus == UploadStatus.QUEUED) return
            updateItem(recording) { it.copy(uploadStatus = UploadStatus.QUEUED, uploadError = null) }
        }
        uploadMutex.withLock {
            val latest = currentItem(recording) ?: return@withLock
            if (latest.uploadStatus != UploadStatus.QUEUED) return@withLock
            uploadOne(recording)
        }
    }

    private fun currentItem(recording: ImuCsvStore.Recording): RecordingItem? =
        _state.value.items.find { it.recording.uri == recording.uri }

    /** 「上傳／重新上傳」單一按鈕與「全部上傳」共用同一段邏輯，狀態變化直接反映在該列上。 */
    private suspend fun uploadOne(recording: ImuCsvStore.Recording) {
        updateItem(recording) { it.copy(uploadStatus = UploadStatus.UPLOADING, uploadError = null) }
        // 上傳中按返回會清掉 ViewModel、取消 viewModelScope；阻塞中的 OkHttp 呼叫不會跟著停，
        // 檔案照樣寫進 Drive，但取消後回到這裡就不會記 setUploaded，下次進頁看到的是「未上傳」、
        // 再傳一次就重複。所以「送出＋記錄成功」這一段標成不可取消，做完才讓協程結束
        val result = withContext(NonCancellable) {
            uploader.upload(recording).onSuccess { uploaded ->
                uploadPreferences.setUploaded(recording.fileName, uploaded.url)
            }
        }
        result.fold(
            onSuccess = { uploaded ->
                updateItem(recording) {
                    it.copy(uploadStatus = UploadStatus.UPLOADED, uploadedUrl = uploaded.url, uploadError = null)
                }
                _effect.send(RecordingsEffect.ShowToast("已上傳 ${recording.fileName}"))
            },
            onFailure = { error ->
                val message = error.message ?: "上傳失敗"
                updateItem(recording) { it.copy(uploadStatus = UploadStatus.FAILED, uploadError = message) }
                _effect.send(RecordingsEffect.ShowToast("上傳失敗：${recording.fileName}"))
            }
        )
    }

    private fun updateItem(recording: ImuCsvStore.Recording, transform: (RecordingItem) -> RecordingItem) {
        _state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.recording.uri == recording.uri) transform(item) else item
                }
            )
        }
    }

    private fun ImuCsvStore.Recording.toItem(movies: List<Movie>): RecordingItem {
        val title = courseId?.let { id -> movies.find { it.courseId == id }?.title }
        val uploadedUrl = uploadPreferences.getUploadedUrl(fileName)
        return RecordingItem(
            recording = this,
            courseTitle = title,
            uploadStatus = if (uploadedUrl != null) UploadStatus.UPLOADED else UploadStatus.NOT_UPLOADED,
            uploadedUrl = uploadedUrl
        )
    }

    private fun storageLocationLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "下載／ActivityScoringPlayer"
        } else {
            "App 專屬目錄（需用 adb 取回）"
        }
}
