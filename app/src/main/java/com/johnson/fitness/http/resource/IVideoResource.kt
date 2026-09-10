package com.johnson.fitness.http.resource

/**
 * 保留給未來的課程清單／使用者資料 API。
 *
 * 這裡原本放的是前一個專案殘留的**股票報價端點**（`GET /stock/bull-bear-force-data`，
 * base url `https://www.wantgoo.com`），跟本 Demo 完全無關，已於 PR-P4 移除，
 * 連同 `BuildConfig.API_URL`、`FitnessApp.videoResource` 一併拿掉。
 *
 * Scoring Demo Player 目前**不呼叫任何後端 API**：課程清單寫死在
 * [com.johnson.fitness.data.MovieRepository]、`.maf` 課程檔打包在 `assets/motions/`、
 * 影片由 ExoPlayer 直接取串流位址播放。
 *
 * 因此本檔案與 `http/` 底下其餘檔案目前**沒有任何呼叫端**，建議整包移除
 * （刪檔需使用者確認，故僅列在 `DONE_Player_B.md` 的建議刪除清單）。
 */
interface IVideoResource
