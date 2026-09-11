package com.johnson.fitness.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 「上傳到 Google Drive」用的中繼設定：Apps Script Web App 的網址與 token，
 * 以及每一筆檔名對應的已上傳 Drive 網址。
 *
 * 電視上沒有 Google 登入，Drive API 沒辦法匿名直接寫入，所以走使用者自己部署的
 * Apps Script 當中繼（契約見 README「錄製資料頁」一節）。網址／token 屬於使用者的私有部署，
 * **不寫進 repo**，只存在這組 SharedPreferences；電視遙控器打字很痛苦，主要靠 debug 用的
 * adb intent（見 [com.johnson.fitness.MainActivity]）灌值，設定頁的編輯對話框只是備用手段。
 *
 * 網址一律經 [setUploadUrl] 存檔，只接受合法的 `https` 網址：使用者用遙控器打字很容易漏打
 * `https://`，漏了的話 OkHttp 的 `Request.Builder().url()` 會丟未捕捉的例外讓 App 崩潰
 * （見 [RecordingUploader]），所以在寫入這一關就先擋掉，adb 灌值與設定頁編輯對話框都走同一關。
 */
class UploadPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configured = MutableStateFlow(computeConfigured())
    // 網址與 token 本身也要能被觀察：設定頁停在前景時用 adb 灌值，只看 configured 布林
    // 分不出「已設定 → 換成另一組」，畫面會一直顯示舊值，編輯對話框也會帶舊值進去
    private val _uploadUrl = MutableStateFlow(getUploadUrl())
    private val _uploadToken = MutableStateFlow(getUploadToken())

    /**
     * 對外的「已設定」狀態；`isConfigured()` 只是一次性讀值，這個 Flow 讓收集端（目前是
     * [com.johnson.fitness.ui.recordings.RecordingsViewModel]）在 adb 灌值／設定頁改完後
     * 立刻拿到最新狀態，不用重新 `load()` 才看得到。單一 process 內大家共用同一個
     * [UploadPreferences] 實例（見 [com.johnson.fitness.FitnessApp]），所以直接在寫入端更新
     * 這個 StateFlow 就夠，不需要另外包 `SharedPreferences.OnSharedPreferenceChangeListener`。
     */
    val configured: StateFlow<Boolean> = _configured.asStateFlow()
    val uploadUrl: StateFlow<String> = _uploadUrl.asStateFlow()
    val uploadToken: StateFlow<String> = _uploadToken.asStateFlow()

    fun getUploadUrl(): String = prefs.getString(KEY_URL, "").orEmpty()

    fun getUploadToken(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    /** 兩者都非空才視為「已設定」；畫面上的上傳相關按鈕全靠這個旗標決定要不要顯示。 */
    fun isConfigured(): Boolean = computeConfigured()

    /**
     * 存檔前驗證網址格式，只接受 `https` 開頭的合法網址；留空視為「清除」。
     * 無效網址回傳 [Result.failure]，訊息可直接顯示給使用者，呼叫端（設定頁對話框、
     * [com.johnson.fitness.MainActivity] 的 adb 灌值）都要走這個方法，不要繞過去直接寫 SharedPreferences。
     */
    fun setUploadUrl(url: String): Result<Unit> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_URL).apply()
            publish()
            return Result.success(Unit)
        }
        return validateHttpsUrl(trimmed).map {
            prefs.edit().putString(KEY_URL, trimmed).apply()
            publish()
        }
    }

    fun setUploadToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
        publish()
    }

    private fun publish() {
        _uploadUrl.value = getUploadUrl()
        _uploadToken.value = getUploadToken()
        _configured.value = computeConfigured()
    }

    /** 已上傳成功的 Drive 網址；null 代表這個檔名還沒上傳過（或上傳失敗過但未成功）。 */
    fun getUploadedUrl(fileName: String): String? = prefs.getString(uploadedKey(fileName), null)

    /** 重開 App 仍要看得到 ✓，所以跟著網址／token 存在同一組 SharedPreferences（不是記憶體狀態）。 */
    fun setUploaded(fileName: String, driveUrl: String) {
        prefs.edit().putString(uploadedKey(fileName), driveUrl).apply()
    }

    /** 目前沒有 UI 入口，先留著給「重新上傳想清掉舊紀錄」這種情境用；上傳成功會直接覆寫，不必先清。 */
    fun clearUploaded(fileName: String) {
        prefs.edit().remove(uploadedKey(fileName)).apply()
    }

    private fun computeConfigured(): Boolean = getUploadUrl().isNotBlank() && getUploadToken().isNotBlank()

    private fun uploadedKey(fileName: String) = "$KEY_UPLOADED_PREFIX$fileName"

    companion object {
        private const val PREFS_NAME = "upload_prefs"
        private const val KEY_URL = "upload_url"
        private const val KEY_TOKEN = "upload_token"
        private const val KEY_UPLOADED_PREFIX = "uploaded_"

        /**
         * 純字串／[okhttp3.HttpUrl] 驗證，不碰 Context，方便 JVM 單元測試。只接受 `https`
         * （不接受漏打 scheme、或打成 `http` 的網址——中繼本來就該是有 TLS 的 Apps Script `/exec`）。
         */
        internal fun validateHttpsUrl(url: String): Result<String> {
            val parsed = url.toHttpUrlOrNull()
            return if (parsed == null || parsed.scheme != "https") {
                Result.failure(IllegalArgumentException("上傳網址格式不正確，請確認有打 https:// 開頭的完整網址"))
            } else {
                Result.success(url)
            }
        }
    }
}
