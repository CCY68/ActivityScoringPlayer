package com.johnson.fitness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UploadPreferences.validateHttpsUrl` 的純字串驗證，JVM 單元測試（不碰 Context／SharedPreferences，
 * 所以不需要實際建立 `UploadPreferences` 實例）。
 *
 * codex P2：設定頁對話框與 MainActivity 的 adb 灌值都走這關，使用者漏打 `https://` 或打成
 * `http://` 不該讓錯誤網址被存進去、最後在真正上傳時才讓 App 崩潰。
 */
class UploadPreferencesTest {

    @Test
    fun `合法 https 網址驗證通過`() {
        val result = UploadPreferences.validateHttpsUrl("https://script.google.com/macros/s/xxx/exec")

        assertTrue(result.isSuccess)
        assertEquals("https://script.google.com/macros/s/xxx/exec", result.getOrNull())
    }

    @Test
    fun `漏打 https 的網址驗證失敗、不丟例外`() {
        val result = UploadPreferences.validateHttpsUrl("script.google.com/macros/s/xxx/exec")

        assertTrue(result.isFailure)
    }

    @Test
    fun `http（非 https）網址驗證失敗`() {
        val result = UploadPreferences.validateHttpsUrl("http://script.google.com/macros/s/xxx/exec")

        assertTrue(result.isFailure)
    }

    @Test
    fun `空字串驗證失敗`() {
        val result = UploadPreferences.validateHttpsUrl("")

        assertTrue(result.isFailure)
    }

    @Test
    fun `完全不是網址的字串驗證失敗`() {
        val result = UploadPreferences.validateHttpsUrl("不是網址")

        assertTrue(result.isFailure)
    }
}
