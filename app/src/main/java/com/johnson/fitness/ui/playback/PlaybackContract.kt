package com.johnson.fitness.ui.playback

import android.net.Uri
import com.johnson.fitness.model.Movie

enum class MafLoadStatus {
    LOADING,
    READY,
    FAILED,
    PLAY_WITHOUT_SCORING
}

enum class ImuDataSource {
    NOT_SELECTED,
    LIVE_B20,
    CSV
}

sealed class PlaybackLaunchConfig {
    data class LiveB20(val recordCsv: Boolean) : PlaybackLaunchConfig()
    data class ReplayCsv(val uri: Uri, val displayName: String?) : PlaybackLaunchConfig()
}

data class PlaybackState(
    val movie: Movie? = null,
    val mafLoadStatus: MafLoadStatus = MafLoadStatus.LOADING,
    val mafLoadError: String? = null,
    val isScoring: Boolean = false,
    val deviceStatus: String = "尚未啟用",
    val imuSampleCount: Long = 0L,
    val scoringStatus: String = "等待影片開始",
    // 當下可用評分面向的即時平均，0–100。
    val accuracy: Int = 0,
    // 目前三面向的即時平均，0–100，不累加。
    val gameScore: Int = 0,
    // Core 有回分數但 confidence 低於顯示門檻（靜止／訊號無週期結構）：UI 顯示「等待動作」而非 0 分（決策 A1）
    val awaitingMotion: Boolean = false,
    val combo: Int = 1,
    // 當下三個即時面向；null 代表此面向在目前區段尚無有效分數，UI 顯示「－」。
    val currentAspectScores: Map<String, Int?> = mapOf(
        "節奏" to null,
        "軌跡" to null,
        "順序" to null
    ),
    // Core 原始診斷摘要：availability / reason / coverage，供實機排查面向未出分原因。
    val currentAspectDiagnostics: Map<String, String> = mapOf(
        "節奏" to "尚未啟動",
        "軌跡" to "尚未啟動",
        "順序" to "尚未啟動"
    ),
    val heartRate: Int = 0,
    val grade: String = "",
    val finalScore: Int? = null,
    // 整堂課沒有任何可顯示分數（Core 全程低 confidence）：成果卡顯示「無有效評分」而非 0 分／D 級（決策 A1）
    val finalNoValidScore: Boolean = false,
    // 三個評分面向（節奏/軌跡/片段相似度）各自的課程平均分數，key = 顯示標籤；
    // ActivityScoringCore 已不提供聚合總分/多演算法比較（ADR 0011），此處為 App 端自行計算的平均值
    val aspectScores: Map<String, Int> = emptyMap(),
    // 以下三項只在 StopScoring 時計算一次，供 FinalScoreCard 顯示：
    // Core 依 start/stop 影片時間結算的本次運動時長
    val exerciseDurationMs: Long = 0L,
    // 手環回報心率樣本（僅計播放中收到的）算術平均，無樣本則為 0
    val avgHeartRate: Int = 0,
    // Core 依 1 Hz 心率與 UserProfile 估算的本次課程熱量
    val caloriesBurned: Int? = null,
    // 手環回報的體表/手臂溫度（攝氏度，僅計播放中收到的）算術平均；無樣本則為 null，UI 顯示「－」
    val avgBodyTemperatureC: Float? = null,

    // ── 活動參與指標（Core ParticipationSnapshot，評分修復更新計畫 §9）─────────────────
    // 不是分數、不與三面向合成：只回答「有沒有跟著動、量到多少」。Core 以 1 Hz（event time）更新，
    // 這裡同時供 HUD 即時顯示與成果卡使用（stop 時 Core 會結算並發最後一份快照）。
    // 評分段內偵測到活動的累積時間（毫秒）→「偵測到活動 14 分鐘」
    val participationActiveMs: Long = 0L,
    // 最長連續活動區間（毫秒；≤ 3 s 的短暫停頓不切斷）→「最長連續活動 4 分鐘」
    val participationLongestRunMs: Long = 0L,
    // 量測完整度 measuredMs / expectedMs，0..1 →「量測完整度 92%」
    val participationCoverage: Float = 0f,
    // 使用者自身腕部幅度訊號的 ACF 正規化峰值（時間加權平均）；null＝不可判斷，UI 留白
    val participationRhythmRegularity: Float? = null,
    // Core 是否已經開始累積參與統計（expectedMs > 0）、且統計仍可信；false 時成果卡的活動三項顯示「－」
    val participationHasData: Boolean = false,
    // 課程中曾拖曳進度條：Core 的參與統計以 monotonic session event time 計算，seek 會讓它失真
    // （計畫 §9 的範圍不含暫停／倒帶／重播），成果卡改為說明原因而不是給錯的數字
    val participationSeeked: Boolean = false,
    // 課程設定（CourseDisplaySettings）：false 的課程（太極）成果卡不顯示活動三項與三面向分數，
    // 只留參與時間、量測完整度與生理摘要（§9.2 弱訊號太極、§9.5）
    val showActivityStats: Boolean = true,
    val alertMessage: String? = null,
    // 窗口反饋文字（"動作標準！"）
    val feedbackLabel: String? = null,
    val feedbackDelta: String? = null,
    val videoPositionMs: Long = 0L,
    val videoDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val imuDataSource: ImuDataSource = ImuDataSource.NOT_SELECTED,
    val selectedCsvName: String? = null,
    val csvSampleCount: Int = 0,
    val isRecordingImu: Boolean = false,
    val recordingFileName: String? = null,
    val completedRecordingFileName: String? = null,
)

sealed class PlaybackIntent {
    object BackPressed : PlaybackIntent()
    object PlayWithoutScoring : PlaybackIntent()
    data class VideoStateChanged(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
        val elapsedRealtimeMs: Long,
        val playbackSpeed: Float,
        val hasEnded: Boolean
    ) : PlaybackIntent()
    data class VideoClockTick(val elapsedRealtimeMs: Long) : PlaybackIntent()
    object DismissAlert : PlaybackIntent()
    object StopScoring : PlaybackIntent()
    data class UseLiveB20(val recordCsv: Boolean) : PlaybackIntent()
    data class CsvSelected(val uri: Uri, val displayName: String?) : PlaybackIntent()
    object StartImuRecording : PlaybackIntent()
    object StopImuRecording : PlaybackIntent()
    object DismissRecordingComplete : PlaybackIntent()
    // 手機沒有遙控器，播放列的進度條要能手動拖曳 seek；
    // 這裡同時把評分引擎的「裝置時間 -> 影片時間」換算基準重新校正，
    // 否則 seek 之後 IMU/心率樣本仍會照舊 offset 換算，對到錯誤的影片時間點。
    data class Seek(val positionMs: Long) : PlaybackIntent()
}

sealed class PlaybackEffect {
    object NavigateBack : PlaybackEffect()
    data class ShowToast(val message: String) : PlaybackEffect()
}
