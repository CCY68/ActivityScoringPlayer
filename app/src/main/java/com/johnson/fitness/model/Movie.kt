package com.johnson.fitness.model

/**
 * 影片牆上的一堂課程。
 *
 * 資料來源是 `assets/courses.json`（見 [com.johnson.fitness.data.CourseCatalog]），
 * 由 [com.johnson.fitness.data.MovieRepository] 在執行期載入，**不再寫死在程式碼裡**。
 *
 * [id] 就是課程編號 [courseId] 的數值形式（例如 `17421781954041251`）——導覽參數要 Long，
 * 課程編號是 17 位純數字，塞得進 Long。這樣一來 `.maf` 對應、標註端課程 id 對應都直接用課程編號，
 * 不必再維護一份「movieId 0…4」的翻譯表。
 *
 * [backgroundImageUrl]／[cardImageUrl] 兩個欄位都取自目錄的 `thumbnailUrl`；60 支課程目前都沒有縮圖，
 * 留空時畫面改用純色卡片（見 HomeScreen 的 CourseCard）。
 *
 * [videoUrl] **不在這裡**：播放網址由免驗證端點在執行期查（見
 * [com.johnson.fitness.data.CoursePlayUrlRepository]），目錄不存也不寫死。
 */
data class Movie(
    val id: Long = 0,
    /** 平台課程編號（字串形式，保留前導格式）。 */
    val courseId: String = "",
    val title: String = "",
    /** 課程分類，首頁用它分列、詳情頁與播放頁當副標顯示。 */
    val category: String = "",
    val description: String = "",
    /** 課程長度（秒）；0 代表未知。 */
    val durationSec: Int = 0,
    val backgroundImageUrl: String = "",
    val cardImageUrl: String = "",
    /** 對應的 `assets/motions/` 檔名；null 代表沒有課程檔＝僅播放不評分。 */
    val mafAsset: String? = null
) {
    /** 是否有對應的 `.maf` 課程檔；false 代表播放頁會降級成「只播放、不評分」。 */
    val hasScoringData: Boolean get() = mafAsset != null
}
