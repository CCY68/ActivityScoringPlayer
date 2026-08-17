package com.johnson.fitness.data

import android.content.Context
import com.fitness.activityscoringcore.api.EngineConfig
import com.fitness.activityscoringcore.engine.ScoringEngine
import com.fitness.activityscoringcore.heart.UserProfile
import com.fitness.activityscoringcore.reference.readMafAssetBytes
import com.motionmaf.format.AesGcmEnvelopeMafDecryptor
import com.motionmaf.format.MafLoadResult
import com.motionmaf.format.ReviewPolicy

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

    /** 載入標註端交付的 AES-256-GCM JSON 信封；key 依信封內 key_id 從 assets 配對。 */
    fun loadMaf(engine: ScoringEngine, movieId: Long): MafLoadResult? {
        val bytes = readMafBytes(movieId) ?: return null
        val decryptor = AesGcmEnvelopeMafDecryptor { keyId -> readContentKey(keyId) }
        return engine.loadMaf(
            rawBytes = bytes,
            reviewPolicy = ReviewPolicy.ALLOW_ANY,
            decryptor = decryptor
        )
    }

    private fun readContentKey(keyId: String): ByteArray? = runCatching {
        require(KEY_ID_PATTERN.matches(keyId)) { "不合法的 MAF key_id：$keyId" }
        val hex = context.assets.open("keys/content-key.$keyId.hex")
            .bufferedReader(Charsets.US_ASCII)
            .use { it.readText().trim() }
        require(hex.length == 64 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "MAF 內容金鑰必須是 64 個十六進位字元"
        }
        ByteArray(32) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    private companion object {
        val KEY_ID_PATTERN = Regex("[A-Za-z0-9._-]+")
        // TODO: 目前沒有使用者生理資料設定頁面，先用固定預設值讓心率安全管線（%HRR/SafetyState）可運作。
        // 之後有使用者資料來源時，應改由呼叫端注入真實的 UserProfile。
        val DEFAULT_USER_PROFILE = UserProfile(ageYears = 30, restingHeartRateBpm = 65)
    }
}
