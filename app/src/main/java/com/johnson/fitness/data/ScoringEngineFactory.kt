package com.johnson.fitness.data

import android.content.Context
import com.fitness.activityscoringcore.api.EngineConfig
import com.fitness.activityscoringcore.engine.ScoringEngine
import com.fitness.activityscoringcore.reference.readMafAssetBytes
import com.motionmaf.format.AesGcmEnvelopeMafDecryptor
import com.motionmaf.format.MafLoadResult
import com.motionmaf.format.ReviewPolicy

// ActivityScoringCore 已改版：不再有 builder()/ScoringConfig/SettlementAlgorithm，
// 直接建構 ScoringEngine；heartRateProfile 才能啟用心率安全管線（見 UserProfile）。
class ScoringEngineFactory(
    private val context: Context,
    private val userProfilePreferences: UserProfilePreferences = UserProfilePreferences(context)
) {
    /**
     * 每次進播放頁都會重建引擎，這裡順便重讀一次設定頁的生理參數，
     * 使用者剛改完就會套用到這一堂課（見 [UserProfilePreferences]）。
     */
    fun create(): ScoringEngine = ScoringEngine(
        config = EngineConfig(),
        heartRateProfile = userProfilePreferences.load()
    )

    /**
     * 從 assets 讀取指定課程的 MAF 檔位元組；檔案不存在或讀取失敗回傳 null，
     * 呼叫端據此判斷是否進入「僅播放影片、不評分」的降級模式（見 PlaybackViewModel）。
     *
     * 檔名由課程目錄決定（`courses.json` 的 `mafAsset`，留空時以課程編號比對 `assets/motions/`），
     * 見 [CourseCatalog.resolveMafAsset]；這裡**不再有寫死的 movieId → 檔名對照表**。
     */
    fun readMafBytes(movieId: Long): ByteArray? {
        val fileName = MovieRepository.getMovieById(context, movieId)?.mafAsset ?: return null
        return runCatching { context.readMafAssetBytes("motions/$fileName") }.getOrNull()
    }

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
    }
}
