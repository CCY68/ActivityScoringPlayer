package com.johnson.fitness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RecordingUploader` 的請求組裝與回應解析，純 JVM 測試（不打網路、不碰
 * `android.util.Base64`——base64 編碼已經是呼叫端傳進來的字串，這裡只驗證 JSON 形狀）。
 */
class RecordingUploaderTest {

    @Test
    fun `請求 JSON 帶齊 token、檔名、內容`() {
        val requestJson = RecordingUploader.buildUploadRequestJson(
            token = "secret-token",
            fileName = "17421781954041251_2026-09-11_22-33-55-680.csv",
            subfolder = "17421781954041251",
            contentBase64 = "aGVsbG8="
        )

        val root = Json.parseToJsonElement(requestJson) as JsonObject
        assertEquals("secret-token", (root["token"] as JsonPrimitive).content)
        assertEquals(
            "17421781954041251_2026-09-11_22-33-55-680.csv",
            (root["fileName"] as JsonPrimitive).content
        )
        assertEquals("17421781954041251", (root["subfolder"] as JsonPrimitive).content)
        assertEquals("aGVsbG8=", (root["contentBase64"] as JsonPrimitive).content)
    }

    @Test
    fun `courseId 是 null 時不帶 subfolder 欄位`() {
        val requestJson = RecordingUploader.buildUploadRequestJson(
            token = "t", fileName = "unknown_2026-09-11_22-33-55-680.csv",
            subfolder = null, contentBase64 = "AA=="
        )

        val root = Json.parseToJsonElement(requestJson) as JsonObject
        assertFalse(root.containsKey("subfolder"))
    }

    @Test
    fun `成功回應解析出 id、url、name`() {
        val result = RecordingUploader.parseUploadResponse(
            """{"ok":true,"id":"1abc","url":"https://drive.google.com/file/d/1abc","name":"foo.csv"}"""
        )

        assertTrue(result is RecordingUploader.UploadResult.Success)
        result as RecordingUploader.UploadResult.Success
        assertEquals("1abc", result.id)
        assertEquals("https://drive.google.com/file/d/1abc", result.url)
        assertEquals("foo.csv", result.name)
    }

    @Test
    fun `失敗回應帶出中繼給的 error 訊息`() {
        val result = RecordingUploader.parseUploadResponse(
            """{"ok":false,"error":"token 不正確"}"""
        )

        assertTrue(result is RecordingUploader.UploadResult.Failure)
        assertEquals("token 不正確", (result as RecordingUploader.UploadResult.Failure).error)
    }

    @Test
    fun `ok=false 但沒帶 error 時給預設訊息`() {
        val result = RecordingUploader.parseUploadResponse("""{"ok":false}""")

        assertTrue(result is RecordingUploader.UploadResult.Failure)
        assertTrue((result as RecordingUploader.UploadResult.Failure).error.isNotBlank())
    }

    @Test
    fun `回應不是 JSON 時不丟例外`() {
        val result = RecordingUploader.parseUploadResponse("<html>502 Bad Gateway</html>")

        assertTrue(result is RecordingUploader.UploadResult.Failure)
    }

    @Test
    fun `回應缺少 ok 欄位視為失敗`() {
        val result = RecordingUploader.parseUploadResponse("""{"id":"1abc"}""")

        assertTrue(result is RecordingUploader.UploadResult.Failure)
    }

    @Test
    fun `ok=true 但沒有 id 視為未處理，不是成功也不是失敗`() {
        // 實測發現中繼偶爾會把這次 POST 誤答成 doGet 的健康檢查，回應長這樣。
        val result = RecordingUploader.parseUploadResponse("""{"ok":true,"service":"imu-upload"}""")

        assertEquals(RecordingUploader.UploadResult.Incomplete, result)
    }

    @Test
    fun `ok=true 但 id 是空字串同樣視為未處理`() {
        val result = RecordingUploader.parseUploadResponse("""{"ok":true,"id":""}""")

        assertEquals(RecordingUploader.UploadResult.Incomplete, result)
    }

    @Test
    fun `buildUploadRequest 對合法 https 網址回傳成功`() {
        val result = RecordingUploader.buildUploadRequest(
            url = "https://script.google.com/macros/s/xxx/exec",
            requestJson = """{"token":"t"}"""
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `buildUploadRequest 對漏打 https 的網址回傳失敗、不丟例外`() {
        // codex P2：使用者漏打 https:// 時，Request.Builder().url() 本來會丟未捕捉的
        // IllegalArgumentException 讓 App 崩潰；這裡驗證 buildUploadRequest 已經用 runCatching 接住。
        val result = RecordingUploader.buildUploadRequest(
            url = "script.google.com/macros/s/xxx/exec",
            requestJson = """{"token":"t"}"""
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `buildUploadRequest 對空字串網址回傳失敗、不丟例外`() {
        val result = RecordingUploader.buildUploadRequest(url = "", requestJson = """{"token":"t"}""")

        assertTrue(result.isFailure)
    }
}
