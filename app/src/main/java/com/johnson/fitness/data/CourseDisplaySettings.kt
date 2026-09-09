package com.johnson.fitness.data

/**
 * 依課程調整成果卡呈現的設定（評分修復更新計畫 §9.2、§9.5）。
 *
 * 這裡的 key 是**標註端（WebTool／Core）的課程 id**，也就是 Core repo 裡
 * `course-<名稱>-<courseId>.draft.json` 的那串數字；Player 內部用的是 [MovieRepository] 的 `movieId`
 * （0…4），兩者靠 [ANNOTATION_COURSE_ID_BY_MOVIE_ID] 對照。契約以課程 id 寫，所以設定表也以課程 id 為
 * 準——換影片來源、movieId 重新編號時只要改對照表，不用動設定。
 *
 * **這是純顯示設定，不影響 Core**：Core 對每一堂課都照常算四項參與指標，是否要拿給使用者看由這裡決定
 * （§9.2「不動 MAF 與 Core」）。
 */
object CourseDisplaySettings {

    /** Player `movieId` → 標註端課程 id。沒列到的影片沒有對應課程（例如純播放測試片）。 */
    private val ANNOTATION_COURSE_ID_BY_MOVIE_ID: Map<Long, String> = mapOf(
        0L to "17421781954041251", // 銀髮族健康操
        1L to "17421914658801191", // 初階瑜珈體位法
        2L to "17428046223601321"  // 太極藝術體驗課
    )

    /**
     * 是否顯示「偵測到活動／最長連續活動／節奏規律」三項（預設 true）。
     *
     * 太極（`17428046223601321`）為 false：慢動作在 `stillnessProbability ≥ 0.70` 的靜止門檻下多被判靜止
     * （計畫 §5d；真實錄製 `activeMs ÷ 評分段總長` 只有 0.565，健康操是 0.87–0.98），
     * 這一輪**不降門檻**，改成成果卡不顯示這三項，只留參與時間、量測完整度與生理摘要。
     * 待「靜坐／站立不動／慢速太極」小組錄製確認腕部訊號可區分後再開放。
     */
    private val SHOW_ACTIVITY_STATS_BY_COURSE_ID: Map<String, Boolean> = mapOf(
        "17428046223601321" to false
    )

    fun annotationCourseId(movieId: Long?): String? =
        movieId?.let { ANNOTATION_COURSE_ID_BY_MOVIE_ID[it] }

    fun showActivityStats(movieId: Long?): Boolean {
        val courseId = annotationCourseId(movieId) ?: return true
        return SHOW_ACTIVITY_STATS_BY_COURSE_ID[courseId] ?: true
    }
}
