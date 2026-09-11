package com.johnson.fitness.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 把本機 IMU CSV 上傳到使用者自己部署的 Google Apps Script Web App 當中繼。
 *
 * 電視上沒有 Google 登入，Drive API 沒辦法匿名寫入，所以走這條路：
 * `POST <uploadUrl>`，body `{token, fileName, subfolder?, contentBase64}`，
 * 回應一律 HTTP 200，成功／失敗看 body 的 `ok`（詳細契約見 README「錄製資料頁」一節）。
 * `subfolder` 用課程編號分資料夾，拿不到（[ImuCsvStore.Recording.courseId] 為 null）就不帶。
 *
 * Apps Script 的 `/exec` 對 POST 會回 302，轉去的 `script.googleusercontent.com/macros/echo?…`
 * 只接受 GET（用 POST 打會 405）——OkHttp 預設會在 302 時自動把 method 改成 GET 再把回應內容取回來，
 * 這是正常流程的一部分，所以底下**故意**明講 `followRedirects(true)`，不要自己實作轉址、
 * 也不要把 method 固定成 POST，兩者都會壞。
 *
 * 實測另外發現：中繼偶爾會把這次 POST 的回應誤答成 doGet 的健康檢查
 * （`{"ok":true,"service":"imu-upload"}`，`ok=true` 但沒有 `id`）——這不是上傳失敗，
 * 是這次請求根本沒被當成上傳處理到，[parseUploadResponse] 會回 [UploadResult.Incomplete]，
 * [upload] 遇到這個情況會自動重試一次，重試後還是這樣才真的當失敗回報。
 *
 * 網址／token 來自 [UploadPreferences]，不寫死在程式碼裡。一堂課 CSV 約 2 MB、
 * base64 後約 2.8 MB，讀檔／編碼／POST 全部放 IO 執行緒，逾時刻意設寬（120 s）。
 */
class RecordingUploader(
    private val context: Context,
    private val preferences: UploadPreferences,
    private val client: OkHttpClient = defaultClient()
) {

    data class UploadedFile(val id: String, val url: String, val name: String)

    suspend fun upload(recording: ImuCsvStore.Recording): Result<UploadedFile> = withContext(Dispatchers.IO) {
        val url = preferences.getUploadUrl()
        val token = preferences.getUploadToken()
        if (url.isBlank() || token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("尚未設定上傳網址／token（設定 → 錄製上傳）"))
        }

        val contentBase64 = try {
            val bytes = context.contentResolver.openInputStream(recording.uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalStateException("無法開啟 CSV 檔案：${recording.fileName}"))
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("讀取 CSV 失敗：${e.message ?: e::class.java.simpleName}"))
        }

        val requestJson = buildUploadRequestJson(
            token = token,
            fileName = recording.fileName,
            subfolder = recording.courseId,
            contentBase64 = contentBase64
        )

        var result = performUploadRequest(url, requestJson)
        // 實測發現 Apps Script 偶爾會把這次 POST 誤答成 doGet 的健康檢查
        // （{"ok":true,"service":"imu-upload"}，ok=true 但沒有 id）——這不是上傳失敗，
        // 是中繼那次根本沒處理到這次請求，重試一次通常就會拿到正確的 id；
        // 重試後還是這樣才真的當失敗回報，不要無限重試。
        if (result is UploadResult.Incomplete) {
            result = performUploadRequest(url, requestJson)
        }

        when (val finalResult = result) {
            is UploadResult.Success ->
                Result.success(UploadedFile(id = finalResult.id, url = finalResult.url, name = finalResult.name))
            is UploadResult.Failure -> Result.failure(IllegalStateException(finalResult.error))
            UploadResult.Incomplete ->
                Result.failure(IllegalStateException("中繼重試後仍未處理這次上傳，請稍後再試一次"))
        }
    }

    private fun performUploadRequest(url: String, requestJson: String): UploadResult {
        // 網址格式不正確（例如使用者忘了打 https://）時，Request.Builder().url() 會丟
        // 未捕捉的 IllegalArgumentException；buildUploadRequest 把這段納入 runCatching，
        // 這裡收到失敗直接轉成可讀訊息，不讓例外往上炸到呼叫端。
        val request = buildUploadRequest(url, requestJson).getOrElse {
            return UploadResult.Failure("上傳網址格式不正確")
        }
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    UploadResult.Failure("上傳失敗（HTTP ${response.code}）")
                } else {
                    parseUploadResponse(body)
                }
            }
        } catch (e: Exception) {
            UploadResult.Failure("無法連線上傳：${e.message ?: e::class.java.simpleName}")
        }
    }

    internal sealed interface UploadResult {
        data class Success(val id: String, val url: String, val name: String) : UploadResult
        data class Failure(val error: String) : UploadResult
        /** ok=true 但沒有 id：中繼那次沒真的處理到這筆上傳，呼叫端要重試而不是直接當失敗。 */
        object Incomplete : UploadResult
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 純字串組裝，不碰 `android.util.Base64` 或任何 Android API，方便 JVM 單元測試
         * （呼叫端已經把檔案內容編碼成 base64 字串再傳進來）。
         */
        internal fun buildUploadRequestJson(
            token: String,
            fileName: String,
            subfolder: String?,
            contentBase64: String
        ): String = buildJsonObject {
            put("token", token)
            put("fileName", fileName)
            if (!subfolder.isNullOrBlank()) put("subfolder", subfolder)
            put("contentBase64", contentBase64)
        }.toString()

        /**
         * 純 Request 組裝，不碰 [OkHttpClient]，方便 JVM 單元測試涵蓋「網址格式不正確」這條路徑
         * （`Request.Builder().url()` 對非法網址——例如漏打 `https://`——會丟未捕捉的
         * [IllegalArgumentException]，這裡用 [runCatching] 接住，呼叫端就不會再讓它往外炸）。
         */
        internal fun buildUploadRequest(url: String, requestJson: String): Result<Request> = runCatching {
            Request.Builder().url(url).post(requestJson.toRequestBody(JSON_MEDIA_TYPE)).build()
        }

        /** 純解析，方便 JVM 單元測試直接餵字串（比照 [CoursePlayUrlRepository.parsePlayUrl]）。 */
        internal fun parseUploadResponse(body: String): UploadResult {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return UploadResult.Failure("上傳回應不是合法的 JSON")
            val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!ok) {
                val error = (root["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                return UploadResult.Failure(error ?: "上傳失敗（中繼未回傳原因）")
            }
            // ok=true 但沒有 id：中繼把這次 POST 誤答成 doGet 的健康檢查
            // （{"ok":true,"service":"imu-upload"}），上傳其實沒被處理到。
            val id = (root["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: return UploadResult.Incomplete
            return UploadResult.Success(
                id = id,
                url = (root["url"] as? JsonPrimitive)?.content.orEmpty(),
                name = (root["name"] as? JsonPrimitive)?.content.orEmpty()
            )
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
