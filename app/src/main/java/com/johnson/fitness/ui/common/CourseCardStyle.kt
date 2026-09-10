package com.johnson.fitness.ui.common

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.johnson.fitness.model.Movie

/**
 * 影片牆卡片的共用樣式與文字格式。
 *
 * 60 支課程目前**都沒有縮圖**（`courses.json` 的 `thumbnailUrl` 全部留空），
 * 所以卡片預設是「純色底＋文字」。純色由課程編號決定（同一支課程每次進來顏色一樣，
 * 使用者可以靠顏色記位置），不是亂數。
 */
object CourseCardStyle {

    private val PALETTE: List<Pair<Color, Color>> = listOf(
        Color(0xFF1E2B4A) to Color(0xFF101726),
        Color(0xFF3A2440) to Color(0xFF1C1226),
        Color(0xFF143A38) to Color(0xFF0B1F1E),
        Color(0xFF43301C) to Color(0xFF21170E),
        Color(0xFF2B1E1E) to Color(0xFF160F0F),
        Color(0xFF1B3350) to Color(0xFF0D1A29),
        Color(0xFF2F3A1C) to Color(0xFF171D0E),
        Color(0xFF32233A) to Color(0xFF19111D)
    )

    /** 沒有縮圖時的卡片底色漸層；同一個 courseId 永遠拿到同一組顏色。 */
    fun placeholderBrush(movie: Movie): Brush {
        val index = ((movie.id % PALETTE.size) + PALETTE.size).toInt() % PALETTE.size
        val (top, bottom) = PALETTE[index]
        return Brush.verticalGradient(listOf(top, bottom))
    }

    /**
     * 課程長度顯示；0（未知）回空字串，呼叫端據此決定要不要畫這行。
     * 目錄裡最長的課程是 57 分鐘，超過一小時仍以「1 小時 5 分」呈現。
     */
    fun formatDuration(durationSec: Int): String {
        if (durationSec <= 0) return ""
        val totalMinutes = (durationSec + 59) / 60
        if (totalMinutes < 60) return "$totalMinutes 分鐘"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) "$hours 小時" else "$hours 小時 $minutes 分"
    }

    /** 卡片右上角的標記文字。 */
    fun scoringBadge(movie: Movie): String = if (movie.hasScoringData) "可評分" else "僅播放"
}
