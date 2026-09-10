package com.johnson.fitness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 課程目錄的一筆課程，對應 `assets/courses.json` 的一個物件。
 *
 * 這是**非工程師也能維護**的清單：加一支課程＝在 `courses.json` 的 `courses` 陣列多加一筆，
 * 不需要改任何 Kotlin 程式碼（欄位說明見專案 README「課程目錄（courses.json）」）。
 */
data class CourseEntry(
    /** 平台的課程編號（純數字字串），同時是 Player 內部的 movieId 與 `.maf` 檔名尾碼。 */
    val courseId: String,
    val title: String,
    /** 首頁影片牆用它分列；空字串會被歸到「其他課程」。 */
    val category: String,
    /** 課程長度（秒）；0 或缺漏代表未知，卡片不顯示時長。 */
    val durationSec: Int,
    /** 卡片縮圖網址；留空時卡片改用純色底＋文字（目前 60 支都還沒有縮圖）。 */
    val thumbnailUrl: String,
    /**
     * `assets/motions/` 底下的 `.maf` 檔名；留空代表「這支課程沒有指定檔案」，
     * 由 [CourseCatalog.resolveMafAsset] 依 `courseId` 自動比對 `assets/motions/` 現有檔案。
     */
    val mafAsset: String
)

/** 目錄解析失敗；[message] 是要直接顯示給使用者的繁體中文說明。 */
class CourseCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `assets/courses.json` 的解析與 `.maf` 對應。
 *
 * 刻意不用 kotlinx.serialization 的 `@Serializable`（本專案沒有掛序列化編譯器外掛），
 * 直接走 `JsonElement` API，因此這支可以在 JVM 單元測試裡直接測（見 `CourseCatalogTest`）。
 */
object CourseCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val COURSE_ID_PATTERN = Regex("[0-9]{6,19}")

    /**
     * 解析目錄 JSON。任何結構性錯誤都丟 [CourseCatalogException]（訊息可直接顯示），
     * 不做「靜默略過」——目錄壞掉時首頁要跳錯誤畫面，而不是變成空白。
     */
    fun parse(text: String): List<CourseEntry> {
        val root = runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw CourseCatalogException("courses.json 不是合法的 JSON：${it.message}", it) }

        val coursesElement = when (root) {
            is JsonArray -> root
            is JsonObject -> root["courses"]
                ?: throw CourseCatalogException("courses.json 缺少 courses 陣列")
            else -> throw CourseCatalogException("courses.json 的最外層必須是物件或陣列")
        }
        val array = coursesElement as? JsonArray
            ?: throw CourseCatalogException("courses.json 的 courses 欄位必須是陣列")
        if (array.isEmpty()) {
            throw CourseCatalogException("courses.json 的 courses 陣列是空的，沒有任何課程可以顯示")
        }

        val seen = mutableSetOf<String>()
        return array.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw CourseCatalogException("courses.json 第 ${index + 1} 筆課程不是物件")
            val courseId = obj.string("courseId").trim()
            if (courseId.isEmpty()) {
                throw CourseCatalogException("courses.json 第 ${index + 1} 筆課程缺少 courseId")
            }
            if (!COURSE_ID_PATTERN.matches(courseId) || courseId.toLongOrNull() == null) {
                // 課程編號同時是 App 內部的 movieId（Long），太長或非數字都不能靜默略過，
                // 否則首頁會少一支課而且沒有任何提示（Codex QA 第一輪缺陷 2）。
                throw CourseCatalogException("courses.json 第 ${index + 1} 筆課程的 courseId「$courseId」不是有效的課程編號（需為 6–19 位純數字）")
            }
            if (!seen.add(courseId)) {
                throw CourseCatalogException("courses.json 有重複的 courseId：$courseId")
            }
            val title = obj.string("title").trim()
            if (title.isEmpty()) {
                throw CourseCatalogException("courses.json 課程 $courseId 缺少 title")
            }
            CourseEntry(
                courseId = courseId,
                title = title,
                category = obj.string("category").trim().ifEmpty { DEFAULT_CATEGORY },
                durationSec = obj.string("durationSec").trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
                thumbnailUrl = obj.string("thumbnailUrl").trim(),
                mafAsset = obj.string("mafAsset").trim()
            )
        }
    }

    /**
     * 決定這支課程要載入哪一個 `.maf`：目錄有寫 `mafAsset` 就用它，
     * 沒寫就在 [availableMafAssets] 裡找檔名以 `-<courseId>.maf` 結尾的檔案
     * （標註端交付的原始檔名格式是 `<課程名>-<課程 id>.maf`）。找不到回傳 null＝這支課程僅播放不評分。
     *
     * 這裡不再有「寫死三個 movieId」的對應表；新增一支已標註課程＝把 `.maf` 放進
     * `assets/motions/`，檔名帶上課程編號即可。
     */
    fun resolveMafAsset(courseId: String, declaredAsset: String, availableMafAssets: List<String>): String? {
        // 目錄明講了要用哪個檔就照做，**即使檔案不存在也回傳它**：這樣播放頁會走「評分資料載入失敗」
        // 並顯示原因，而不是把「說好要評分卻缺檔」偽裝成一支正常的純播放課程
        //（Codex QA 第一輪缺陷 1）。
        if (declaredAsset.isNotEmpty()) return declaredAsset
        val suffix = "-$courseId.maf"
        return availableMafAssets.firstOrNull { it.endsWith(suffix) || it == "$courseId.maf" }
    }

    const val DEFAULT_CATEGORY = "其他課程"

    /** 缺欄位一律回空字串；數字欄位也走字串再轉，避免 JSON 寫成 "1200" 就整包解析失敗。 */
    private fun JsonObject.string(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return primitive.content.takeUnless { it == "null" } ?: ""
    }
}
