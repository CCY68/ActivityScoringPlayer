package com.johnson.fitness.ui.recordings

import com.johnson.fitness.data.ImuCsvStore
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.playback.PlaybackLaunchConfig

/**
 * 一列錄製資料。[summary] 為 null 代表還在背景算筆數／時長（列表先顯示檔名與大小），
 * [courseTitle] 為 null 代表 [ImuCsvStore.Recording.courseId] 對不到 `courses.json` 裡的任何課程
 * （舊檔名沒有 courseId，或課程已經從目錄移除）。
 */
data class RecordingItem(
    val recording: ImuCsvStore.Recording,
    val courseTitle: String?,
    val summary: ImuCsvStore.RecordingSummary? = null
)

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
    val scorableCourses: List<Movie> = emptyList()
)

sealed class RecordingsIntent {
    data class ReplayClicked(val recording: ImuCsvStore.Recording) : RecordingsIntent()
    data class CourseChosenForReplay(val movieId: Long) : RecordingsIntent()
    object DismissCourseDialog : RecordingsIntent()
    data class DeleteRequested(val recording: ImuCsvStore.Recording) : RecordingsIntent()
    object DeleteConfirmed : RecordingsIntent()
    object DeleteCancelled : RecordingsIntent()
}

sealed class RecordingsEffect {
    data class Replay(val movieId: Long, val config: PlaybackLaunchConfig.ReplayCsv) : RecordingsEffect()
    data class ShowToast(val message: String) : RecordingsEffect()
}
