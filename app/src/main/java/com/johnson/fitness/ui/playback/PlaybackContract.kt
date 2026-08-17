package com.johnson.fitness.ui.playback

import com.johnson.fitness.model.Movie

enum class MafLoadStatus {
    LOADING,
    READY,
    FAILED,
    PLAY_WITHOUT_SCORING
}

data class PlaybackState(
    val movie: Movie? = null,
    val mafLoadStatus: MafLoadStatus = MafLoadStatus.LOADING,
    val mafLoadError: String? = null,
    val isScoring: Boolean = false,
    val deviceStatus: String = "尚未啟用",
    val imuSampleCount: Long = 0L,
    val scoringStatus: String = "等待影片開始",
    // 即時窗口相似度 0–100，顯示於準度環
    val accuracy: Int = 0,
    // 遊戲化累積分數（accuracy × combo 累加）
    val gameScore: Int = 0,
    val combo: Int = 1,
    val heartRate: Int = 0,
    val grade: String = "",
    val finalScore: Int? = null,
    // 三個評分面向（節奏/軌跡/片段相似度）各自的課程平均分數，key = 顯示標籤；
    // ActivityScoringCore 已不提供聚合總分/多演算法比較（ADR 0011），此處為 App 端自行計算的平均值
    val aspectScores: Map<String, Int> = emptyMap(),
    val alertMessage: String? = null,
    // 窗口反饋文字（"動作標準！"）
    val feedbackLabel: String? = null,
    val feedbackDelta: String? = null,
    val videoPositionMs: Long = 0L,
    val videoDurationMs: Long = 0L,
    val isPlaying: Boolean = true,
)

sealed class PlaybackIntent {
    object BackPressed : PlaybackIntent()
    object PlayWithoutScoring : PlaybackIntent()
    data class VideoStateChanged(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean
    ) : PlaybackIntent()
    object DismissAlert : PlaybackIntent()
    object StopScoring : PlaybackIntent()
    // 手機沒有遙控器，播放列的進度條要能手動拖曳 seek；
    // 這裡同時把評分引擎的「裝置時間 -> 影片時間」換算基準重新校正，
    // 否則 seek 之後 IMU/心率樣本仍會照舊 offset 換算，對到錯誤的影片時間點。
    data class Seek(val positionMs: Long) : PlaybackIntent()
}

sealed class PlaybackEffect {
    object NavigateBack : PlaybackEffect()
}
