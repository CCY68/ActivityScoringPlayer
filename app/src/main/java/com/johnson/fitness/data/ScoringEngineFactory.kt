package com.johnson.fitness.data

import android.content.Context
import com.fitness.activityscoringcore.api.EngineConfig
import com.fitness.activityscoringcore.engine.ScoringEngine
import com.fitness.activityscoringcore.heart.UserProfile
import com.fitness.activityscoringcore.reference.readMafAssetBytes

// ActivityScoringCore 已改版：不再有 builder()/ScoringConfig/SettlementAlgorithm，
// 直接建構 ScoringEngine；heartRateProfile 才能啟用心率安全管線（見 UserProfile）。
class ScoringEngineFactory(
    private val context: Context
) {
    fun create(): ScoringEngine = ScoringEngine(
        config = EngineConfig(),
        heartRateProfile = DEFAULT_USER_PROFILE
    )

    /**
     * 從 assets 讀取指定課程的 MAF 檔位元組；檔案不存在或讀取失敗回傳 null，
     * 呼叫端據此判斷是否進入「僅播放影片、不評分」的降級模式（見 PlaybackViewModel）。
     */
    fun readMafBytes(movieId: Long): ByteArray? =
        runCatching { context.readMafAssetBytes("motions/$movieId.maf") }.getOrNull()

    private companion object {
        // TODO: 目前沒有使用者生理資料設定頁面，先用固定預設值讓心率安全管線（%HRR/SafetyState）可運作。
        // 之後有使用者資料來源時，應改由呼叫端注入真實的 UserProfile。
        val DEFAULT_USER_PROFILE = UserProfile(ageYears = 30, restingHeartRateBpm = 65)
    }
}
