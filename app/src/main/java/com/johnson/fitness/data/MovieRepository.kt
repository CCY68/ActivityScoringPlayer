package com.johnson.fitness.data

import android.content.Context
import com.johnson.fitness.model.Movie

/**
 * 課程目錄。
 *
 * 內容來自 `assets/courses.json`（本專案要支援的 60 支課程影片），在執行期解析後快取；
 * PR-P4 之前那份寫死在程式碼裡的示範清單已經整份拿掉。
 *
 * - 播放網址**不在目錄裡**：由 [CoursePlayUrlRepository] 向免驗證端點查（`course/info`）。
 * - `.maf` 對應由 [CourseCatalog.resolveMafAsset] 依 `mafAsset` 或課程編號比對 `assets/motions/`，
 *   不再有寫死的 movieId 對照表。
 *
 * 載入失敗會保留成 [CatalogResult.Failure]，首頁據此顯示錯誤畫面（**不會靜默變成空清單**）。
 */
object MovieRepository {

    sealed interface CatalogResult {
        data class Success(val movies: List<Movie>) : CatalogResult
        data class Failure(val message: String) : CatalogResult
    }

    private const val CATALOG_ASSET = "courses.json"
    private const val MOTIONS_DIR = "motions"

    @Volatile
    private var cached: List<Movie>? = null

    /** 只有測試會用到；正式流程不需要重設。 */
    internal fun resetForTest() {
        cached = null
    }

    /**
     * 載入目錄（成功後快取）。會讀 assets，呼叫端請放在背景執行緒。
     * [forceReload] 供錯誤畫面的「重試」使用。
     */
    fun load(context: Context, forceReload: Boolean = false): CatalogResult {
        if (!forceReload) {
            cached?.let { return CatalogResult.Success(it) }
        }
        val assets = context.applicationContext.assets
        val text = runCatching {
            assets.open(CATALOG_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            return CatalogResult.Failure("讀不到課程目錄 assets/$CATALOG_ASSET：${it.message ?: it::class.java.simpleName}")
        }
        val mafAssets = runCatching { assets.list(MOTIONS_DIR)?.toList().orEmpty() }.getOrDefault(emptyList())
        val entries = try {
            CourseCatalog.parse(text)
        } catch (e: CourseCatalogException) {
            return CatalogResult.Failure(e.message ?: "課程目錄格式錯誤")
        }
        val movies = entries.map { entry -> entry.toMovie(mafAssets) }
        cached = movies
        return CatalogResult.Success(movies)
    }

    /** 給沒有機會先跑 [load] 的畫面（詳情／播放頁重建）用：需要時自己補載一次。 */
    fun movies(context: Context): List<Movie> {
        cached?.let { return it }
        return (load(context) as? CatalogResult.Success)?.movies.orEmpty()
    }

    fun getMovieById(context: Context, id: Long): Movie? = movies(context).find { it.id == id }

    /** 首頁分列用；依 [Movie.category] 分組，順序照目錄裡第一次出現的先後。 */
    fun moviesByCategory(context: Context): Map<String, List<Movie>> =
        movies(context).groupBy { it.category }

    // courseId 塞不塞得進 Long 已經在 CourseCatalog.parse 檢查過（不合格會整份目錄失敗，
    // 不會偷偷少一支課），這裡可以安全轉換。
    private fun CourseEntry.toMovie(mafAssets: List<String>): Movie {
        return Movie(
            id = courseId.toLong(),
            courseId = courseId,
            title = title,
            category = category,
            description = "",
            durationSec = durationSec,
            backgroundImageUrl = thumbnailUrl,
            cardImageUrl = thumbnailUrl,
            mafAsset = CourseCatalog.resolveMafAsset(courseId, mafAsset, mafAssets)
        )
    }
}
