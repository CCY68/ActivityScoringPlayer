package com.johnson.fitness.ui.playback

import com.johnson.fitness.model.Movie

data class PlaybackState(
    val movie: Movie? = null,
    val isScoring: Boolean = false,
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
)

sealed class PlaybackIntent {
    object BackPressed : PlaybackIntent()
    data class VideoStateChanged(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean
    ) : PlaybackIntent()
    object DismissAlert : PlaybackIntent()
    object StopScoring : PlaybackIntent()
}

sealed class PlaybackEffect {
    object NavigateBack : PlaybackEffect()
}
