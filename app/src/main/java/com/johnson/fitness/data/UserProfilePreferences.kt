package com.johnson.fitness.data

import android.content.Context
import com.fitness.activityscoringcore.heart.BiologicalSex
import com.fitness.activityscoringcore.heart.CalorieModel
import com.fitness.activityscoringcore.heart.UserProfile

/**
 * 保存 Demo 的使用者生理參數（年齡、靜息心率、生理性別、體重、身高）。
 *
 * Core 用它推最大心率與心率保留區間、估算熱量（見 Core `heart/UserProfile`）；
 * PR-P4 之前這組值是寫死在 [ScoringEngineFactory] 的 `UserProfile(30, 65, …)`，
 * 換一個受測者就得改程式重編，這裡改成設定頁可調、存在 SharedPreferences。
 *
 * - 沒設定過時回傳 [DEMO_DEFAULT]（30 歲／靜息 65／男性／70 kg／170 cm，VO2R 熱量模型），
 *   設定頁會明白標示那是 Demo 預設值。
 * - `onBetaBlocker` 與處方心率上下限固定留 false／null：那屬於醫療用途，
 *   本 Demo 對外只聲明「心率與運動強度監看」，不提供醫囑欄位。
 * - 讀取時一律夾在合理範圍（見 [AGE_RANGE] 等），避免舊資料或手動改 XML 造成 Core 端算出離譜區間。
 */
class UserProfilePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 使用者是否調整過；設定頁用它決定要不要顯示「目前是 Demo 預設值」提示。 */
    fun isCustomized(): Boolean = prefs.contains(KEY_AGE)

    fun load(): UserProfile {
        if (!isCustomized()) return DEMO_DEFAULT
        return DEMO_DEFAULT.copy(
            ageYears = prefs.getInt(KEY_AGE, DEMO_DEFAULT.ageYears).coerceIn(AGE_RANGE),
            restingHeartRateBpm = prefs.getInt(KEY_RESTING_HR, DEMO_DEFAULT.restingHeartRateBpm)
                .coerceIn(RESTING_HR_RANGE),
            biologicalSex = prefs.getString(KEY_SEX, null)
                ?.let { runCatching { BiologicalSex.valueOf(it) }.getOrNull() }
                ?: DEFAULT_SEX,
            weightKg = prefs.getInt(KEY_WEIGHT_KG, DEFAULT_WEIGHT_KG).coerceIn(WEIGHT_RANGE).toFloat(),
            heightCm = prefs.getInt(KEY_HEIGHT_CM, DEFAULT_HEIGHT_CM).coerceIn(HEIGHT_RANGE).toFloat()
        )
    }

    /** 設定頁每改一項就整份寫回；欄位少、寫入頻率低，不值得為它做增量更新。 */
    fun save(profile: UserProfile) {
        prefs.edit()
            .putInt(KEY_AGE, profile.ageYears.coerceIn(AGE_RANGE))
            .putInt(KEY_RESTING_HR, profile.restingHeartRateBpm.coerceIn(RESTING_HR_RANGE))
            .putString(KEY_SEX, (profile.biologicalSex ?: DEFAULT_SEX).name)
            .putInt(KEY_WEIGHT_KG, (profile.weightKg ?: DEFAULT_WEIGHT_KG.toFloat()).toInt().coerceIn(WEIGHT_RANGE))
            .putInt(KEY_HEIGHT_CM, (profile.heightCm ?: DEFAULT_HEIGHT_CM.toFloat()).toInt().coerceIn(HEIGHT_RANGE))
            .apply()
    }

    /** 回到 Demo 預設值（設定頁的「還原預設」）。 */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        val AGE_RANGE = 10..100
        val RESTING_HR_RANGE = 30..120
        val WEIGHT_RANGE = 30..150
        val HEIGHT_RANGE = 120..210

        /** Core 的 `UserProfile.biologicalSex` 可為 null，這裡一律落到有值的預設。 */
        val DEFAULT_SEX = BiologicalSex.MALE
        private const val DEFAULT_WEIGHT_KG = 70
        private const val DEFAULT_HEIGHT_CM = 170

        /**
         * Demo 預設值：一般成年男性的中位數字，**不是任何真實受測者的資料**。
         * 設定頁沒調過時就用這組，畫面上會標示「Demo 預設值」。
         */
        val DEMO_DEFAULT = UserProfile(
            ageYears = 30,
            restingHeartRateBpm = 65,
            biologicalSex = DEFAULT_SEX,
            weightKg = DEFAULT_WEIGHT_KG.toFloat(),
            heightCm = DEFAULT_HEIGHT_CM.toFloat(),
            calorieModel = CalorieModel.VO2R
        )

        private const val PREFS_NAME = "user_profile"
        private const val KEY_AGE = "age_years"
        private const val KEY_RESTING_HR = "resting_hr_bpm"
        private const val KEY_SEX = "biological_sex"
        private const val KEY_WEIGHT_KG = "weight_kg"
        private const val KEY_HEIGHT_CM = "height_cm"
    }
}
