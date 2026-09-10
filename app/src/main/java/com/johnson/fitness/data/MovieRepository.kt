package com.johnson.fitness.data

import com.johnson.fitness.model.Movie

/**
 * Scoring Demo Player 的示範課程目錄。
 *
 * 這裡是**寫死的示範清單**，不是佔位假資料：Demo 沒有後端，也沒有課程管理 UI，
 * 影片直接指向已上架的串流位址，課程檔 `.maf` 打包在 `assets/motions/`。
 *
 * - `id` 0–2：已完成動作標註、有 `.maf` 的課程，配戴手環時會實際評分
 *   （對照表在 [ScoringEngineFactory] 與 [CourseDisplaySettings]）。
 * - `id` 3–4：沒有 `.maf` 的播放路徑驗證影片，播放頁會降級成「只播放、不評分」（`isScoring = false`）。
 *
 * 新增課程時：這裡加一筆 → `ScoringEngineFactory.MAF_FILE_BY_MOVIE_ID` 補檔名 →
 * `CourseDisplaySettings.ANNOTATION_COURSE_ID_BY_MOVIE_ID` 補標註端課程 id。
 */
object MovieRepository {

    val movies: List<Movie> = listOf(
        Movie(
            id = 0L,
            title = "銀髮族健康操",
            category = "銀髮運動",
            description = "坐姿與站姿交替的全身活動操，以上肢擺動、開合與踏步為主。" +
                "已完成動作標註，配戴手環即可即時評分。",
            videoUrl = "https://75d61619-eeb7-4283-b5ed-36e1930a7dcf.cdn.blendvision.com/" +
                "6ddcc065-ee8f-4449-a4c7-f9d9eb11a978/vod/cd5651d3-9c24-4930-8f0f-0c6c8ecedc15/vod/hls.m3u8",
            hasScoringData = true
        ),
        Movie(
            id = 1L,
            title = "初階瑜珈體位法 1 - 英雄1 & 英雄2",
            category = "瑜珈",
            description = "英雄一式與英雄二式的入門教學，著重站姿穩定與上肢延展。已完成動作標註。",
            videoUrl = "https://75d61619-eeb7-4283-b5ed-36e1930a7dcf.cdn.blendvision.com/" +
                "6ddcc065-ee8f-4449-a4c7-f9d9eb11a978/vod/209581d1-c9cb-477e-aaa9-386b9033219e/vod/hls.m3u8",
            hasScoringData = true
        ),
        Movie(
            id = 2L,
            title = "太極藝術體驗課 (中文字幕)",
            category = "太極",
            description = "太極入門體驗，動作緩慢連貫。已完成動作標註；" +
                "慢速動作的靜止判定仍在調校，成果卡不顯示活動統計三項（見 CourseDisplaySettings）。",
            videoUrl = "https://75d61619-eeb7-4283-b5ed-36e1930a7dcf.cdn.blendvision.com/" +
                "6ddcc065-ee8f-4449-a4c7-f9d9eb11a978/vod/763e63bb-5ad2-43e5-9d7f-695f1ece3ec9/vod/hls.m3u8",
            hasScoringData = true
        ),
        Movie(
            id = 3L,
            title = "MP4 播放測試",
            category = "播放測試",
            description = "單一 MP4 片段，用來驗證非串流的播放路徑。沒有 `.maf`，播放頁為只播放、不評分模式。",
            videoUrl = "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/" +
                "dash-vod-single-segment/video-137.mp4"
        ),
        Movie(
            id = 4L,
            title = "HLS 串流播放測試",
            category = "播放測試",
            description = "多位元率 HLS 串流，用來驗證換軌與緩衝行為。沒有 `.maf`，播放頁為只播放、不評分模式。",
            videoUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        )
    )

    fun getMovieById(id: Long): Movie? = movies.find { it.id == id }

    /** 首頁分列用；依 [Movie.category] 分組，順序照 [movies] 第一次出現的先後。 */
    fun moviesByCategory(): Map<String, List<Movie>> = movies.groupBy { it.category }
}
