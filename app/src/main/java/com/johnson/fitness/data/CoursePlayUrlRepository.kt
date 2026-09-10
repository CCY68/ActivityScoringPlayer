package com.johnson.fitness.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 課程播放網址（HLS master playlist）。
 *
 * 播放網址會換（CDN 後台重新編碼就換一組），所以**不寫進 `courses.json`**，
 * 每次要播才向 Welltivity 的免驗證端點查：
 *
 * ```
 * GET https://asia.welltivity.com.tw/api/app/open/course/info?courseId=<courseId>
 * → {"code":200,"data":{"playUrl":"https://….m3u8","courseTitle":"…"}}
 * ```
 *
 * 同一個 process 內查過就快取（課程內容不會在一次使用中換掉），避免回到播放頁又打一次。
 */
class CoursePlayUrlRepository(
    private val client: OkHttpClient = defaultClient(),
    private val endpointTemplate: String = COURSE_INFO_ENDPOINT
) {

    /** [message] 是可以直接顯示給使用者的繁體中文說明。 */
    sealed interface Result {
        data class Success(val playUrl: String) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun resolve(courseId: String, forceReload: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            if (!forceReload) {
                cache[courseId]?.let { return@withContext Result.Success(it) }
            }
            val url = endpointTemplate.replace("{courseId}", courseId)
            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.Failure("課程資訊查詢失敗（HTTP ${response.code}）")
                    }
                    when (val parsed = parsePlayUrl(body)) {
                        is Result.Success -> {
                            cache[courseId] = parsed.playUrl
                            parsed
                        }
                        is Result.Failure -> parsed
                    }
                }
            } catch (e: Exception) {
                Result.Failure("無法連線取得播放網址：${e.message ?: e::class.java.simpleName}")
            }
        }

    companion object {
        const val COURSE_INFO_ENDPOINT =
            "https://asia.welltivity.com.tw/api/app/open/course/info?courseId={courseId}"

        private val cache = ConcurrentHashMap<String, String>()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun clearCacheForTest() = cache.clear()

        /** 純解析，方便 JVM 單元測試直接餵字串。 */
        internal fun parsePlayUrl(body: String): Result {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return Result.Failure("課程資訊回應不是合法的 JSON")
            val code = (root["code"] as? JsonPrimitive)?.content
            val data = root["data"] as? JsonObject
                ?: return Result.Failure(
                    "課程資訊沒有回傳資料" + (code?.let { "（code=$it）" } ?: "")
                )
            val playUrl = (data["playUrl"] as? JsonPrimitive)?.content?.takeIf {
                it.isNotBlank() && it != "null"
            } ?: return Result.Failure("這支課程沒有可播放的串流網址")
            return Result.Success(playUrl)
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
