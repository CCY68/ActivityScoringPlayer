package com.johnson.fitness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 實際打包進 App 的 `assets/courses.json` 本身要能解析，而且與 `assets/motions/` 對得起來。
 * 這支測試會在有人手動編輯目錄後、還沒裝進機器前就先擋下格式錯誤。
 */
class CoursesAssetTest {

    private val assetsDir = File("src/main/assets")

    @Test
    fun `目錄可以解析且沒有重複課程`() {
        val entries = CourseCatalog.parse(File(assetsDir, "courses.json").readText(Charsets.UTF_8))
        assertTrue("目錄至少要有一支課程", entries.isNotEmpty())
        assertEquals("courseId 不可重複", entries.size, entries.map { it.courseId }.toSet().size)
        entries.forEach { entry ->
            assertTrue("課程 ${entry.courseId} 的 courseId 必須塞得進 Long", entry.courseId.toLongOrNull() != null)
            assertTrue("課程 ${entry.courseId} 缺標題", entry.title.isNotBlank())
        }
    }

    @Test
    fun `目錄裡寫到的 maf 檔都真的存在`() {
        val motions = File(assetsDir, "motions").list()?.toList().orEmpty()
        val entries = CourseCatalog.parse(File(assetsDir, "courses.json").readText(Charsets.UTF_8))
        val declared = entries.filter { it.mafAsset.isNotEmpty() }
        declared.forEach { entry ->
            assertTrue(
                "courses.json 寫了 ${entry.mafAsset}，但 assets/motions/ 沒有這個檔",
                entry.mafAsset in motions
            )
        }
        // assets/motions/ 底下每一支 .maf 都要有課程對得上，否則就是目錄漏加課程。
        val resolved = entries.mapNotNull {
            CourseCatalog.resolveMafAsset(it.courseId, it.mafAsset, motions)
        }.toSet()
        val orphans = motions.filter { it.endsWith(".maf") } - resolved
        assertTrue("assets/motions/ 有課程目錄對不到的 .maf：$orphans", orphans.isEmpty())
    }
}
