package com.johnson.fitness.model

/**
 * 示範課程／影片的展示資料。
 *
 * Scoring Demo Player 沒有後端，這份資料寫死在 [com.johnson.fitness.data.MovieRepository]；
 * [id] 同時是 `.maf` 課程檔與課程顯示設定的對照鍵（見 `ScoringEngineFactory`、`CourseDisplaySettings`）。
 *
 * [backgroundImageUrl]／[cardImageUrl] 目前一律留空——原本填的是 Google TV 範例專案的示範圖片，
 * 與課程內容無關，已於 PR-P4 移除；畫面在留空時改用純色卡片，之後有實拍縮圖再填回即可。
 */
data class Movie(
    val id: Long = 0,
    val title: String = "",
    /** 課程分類，首頁用它分列、詳情頁與播放頁當副標顯示。 */
    val category: String = "",
    val description: String = "",
    val backgroundImageUrl: String = "",
    val cardImageUrl: String = "",
    val videoUrl: String = "",
    /** 是否有對應的 `.maf` 課程檔；false 代表播放頁會降級成「只播放、不評分」。 */
    val hasScoringData: Boolean = false
)
