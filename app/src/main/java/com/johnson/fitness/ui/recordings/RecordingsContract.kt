package com.johnson.fitness.ui.recordings

import com.johnson.fitness.data.ImuCsvStore
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.playback.PlaybackLaunchConfig

/**
 * 單筆錄製資料的上傳狀態；[RecordingsViewModel] 從 [com.johnson.fitness.data.UploadPreferences] 初始化。
 * [QUEUED] 代表已排入單一上傳佇列、正在等前面的檔案上傳完（見 [RecordingsViewModel] 的 `uploadMutex`），
 * 跟真正在打網路的 [UPLOADING] 分開，讓畫面看得出「排隊中」跟「進行中」的差別。
 */
enum class UploadStatus { NOT_UPLOADED, QUEUED, UPLOADING, UPLOADED, FAILED }

/**
 * 一列錄製資料。[summary] 為 null 代表還在背景算筆數／時長（列表先顯示檔名與大小），
 * [courseTitle] 為 null 代表 [ImuCsvStore.Recording.courseId] 對不到 `courses.json` 裡的任何課程
 * （舊檔名沒有 courseId，或課程已經從目錄移除）。
 * [uploadedUrl] 只在 [uploadStatus] 為 [UploadStatus.UPLOADED] 時有值；[uploadError] 只在 FAILED 時有值。
 */
data class RecordingItem(
    val recording: ImuCsvStore.Recording,
    val courseTitle: String?,
    val summary: ImuCsvStore.RecordingSummary? = null,
    val uploadStatus: UploadStatus = UploadStatus.NOT_UPLOADED,
    val uploadedUrl: String? = null,
    val uploadError: String? = null
)

/** 「全部上傳」的整體進度；[RecordingsState.uploadAllProgress] 非 null 時畫面顯示「current/total 上傳中…」。 */
data class UploadAllProgress(val current: Int, val total: Int)

data class RecordingsState(
    val isLoading: Boolean = true,
    val items: List<RecordingItem> = emptyList(),
    /** 儲存位置的人話說明（Q+／&lt; Q 不同）；見 [RecordingsViewModel]。 */
    val storageLocationLabel: String = "",
    /** 等待二次確認刪除的那一筆；非 null 時畫面顯示確認對話框。 */
    val pendingDelete: ImuCsvStore.Recording? = null,
    /** 對不到課程時，回放要先跳「選擇課程」對話框；非 null 代表對話框開著、等使用者選課程。 */
    val pendingReplaySelection: ImuCsvStore.Recording? = null,
    /** 「選擇課程」對話框只列有 `.maf` 的課程。 */
    val scorableCourses: List<Movie> = emptyList(),
    /** 對應 `UploadPreferences.isConfigured()`；false 時整頁隱藏上傳相關按鈕，改在副標提示去設定頁。 */
    val uploadConfigured: Boolean = false,
    val uploadAllProgress: UploadAllProgress? = null
)

sealed class RecordingsIntent {
    data class ReplayClicked(val recording: ImuCsvStore.Recording) : RecordingsIntent()
    data class CourseChosenForReplay(val movieId: Long) : RecordingsIntent()
    object DismissCourseDialog : RecordingsIntent()
    data class DeleteRequested(val recording: ImuCsvStore.Recording) : RecordingsIntent()
    object DeleteConfirmed : RecordingsIntent()
    object DeleteCancelled : RecordingsIntent()
    data class UploadClicked(val recording: ImuCsvStore.Recording) : RecordingsIntent()
    /** 只上傳目前還沒成功上傳過的那些，逐一序列進行（見 [RecordingsViewModel]）。 */
    object UploadAllClicked : RecordingsIntent()
}

sealed class RecordingsEffect {
    data class Replay(val movieId: Long, val config: PlaybackLaunchConfig.ReplayCsv) : RecordingsEffect()
    data class ShowToast(val message: String) : RecordingsEffect()
}
