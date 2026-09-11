package com.johnson.fitness

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.johnson.fitness.navigation.AppNavigation
import com.johnson.fitness.ui.theme.ActivityScoringPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDebugUploadConfigIfPresent(intent)
        setContent {
            ActivityScoringPlayerTheme {
                AppNavigation()
            }
        }
    }

    // Manifest 把這顆 Activity 設成 launchMode="singleTop"，App 已在前景時 adb am start
    // 會走這裡而不是重開一個新的 instance。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugUploadConfigIfPresent(intent)
    }

    /**
     * debug 專用後門：電視遙控器沒有鍵盤，Apps Script 的上傳網址／token 用遙控器打字幾乎不可能操作，
     * 開發期改用 adb 直接灌設定：
     * ```
     * adb shell am start -n com.johnson.fitness/.MainActivity --es upload_url "…" --es upload_token "…"
     * ```
     * 兩個 extra 可以只帶一個（例如只想換 token）。**release 版必須拿掉**：上傳中繼的 token
     * 一旦能被任何外部 intent 寫入，等於任何裝了 adb 或能發 intent 的人都能竄改上傳目的地，
     * 這在 debug 版可接受（開發機器自己用），正式版不行。
     */
    private fun applyDebugUploadConfigIfPresent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val url = intent.getStringExtra(EXTRA_UPLOAD_URL)
        val token = intent.getStringExtra(EXTRA_UPLOAD_TOKEN)
        if (url == null && token == null) return

        val preferences = (application as FitnessApp).uploadPreferences
        // 網址走 UploadPreferences.setUploadUrl 的 https 驗證（跟設定頁對話框同一關）：
        // adb 打錯網址（漏打 https://）不該讓 App 崩潰，而是拒收並告訴使用者原因。
        val urlFailure = url?.let { preferences.setUploadUrl(it).exceptionOrNull() }
        token?.let(preferences::setUploadToken)

        if (urlFailure != null) {
            Toast.makeText(this, urlFailure.message ?: "上傳網址格式不正確", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "已更新上傳設定", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val EXTRA_UPLOAD_URL = "upload_url"
        const val EXTRA_UPLOAD_TOKEN = "upload_token"
    }
}
