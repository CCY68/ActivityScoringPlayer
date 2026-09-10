package com.johnson.fitness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `assets/courses.json` 的解析與 `.maf` 對應規則。 */
class CourseCatalogTest {

    private val sample = """
        {
          "version": 1,
          "courses": [
            {
              "courseId": "17421781954041251",
              "title": "銀髮族健康操",
              "category": "伸展與活動度",
              "durationSec": 1200,
              "thumbnailUrl": "",
              "mafAsset": "銀髮族健康操-17421781954041251.maf"
            },
            {
              "courseId": "17421784957981721",
              "title": "慢活舒心氧身操2-2",
              "category": "有氧運動",
              "durationSec": 1200,
              "thumbnailUrl": "",
              "mafAsset": ""
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析目錄取得所有欄位`() {
        val entries = CourseCatalog.parse(sample)
        assertEquals(2, entries.size)
        assertEquals("17421781954041251", entries[0].courseId)
        assertEquals("銀髮族健康操", entries[0].title)
        assertEquals("伸展與活動度", entries[0].category)
        assertEquals(1200, entries[0].durationSec)
        assertEquals("", entries[0].thumbnailUrl)
        assertEquals("銀髮族健康操-17421781954041251.maf", entries[0].mafAsset)
    }

    @Test
    fun `分類留空歸到其他課程、時長缺漏為0`() {
        val entries = CourseCatalog.parse(
            """[{"courseId":"12345678901234567","title":"測試課"}]"""
        )
        assertEquals(CourseCatalog.DEFAULT_CATEGORY, entries[0].category)
        assertEquals(0, entries[0].durationSec)
        assertEquals("", entries[0].mafAsset)
    }

    @Test
    fun `時長寫成字串也能解析`() {
        val entries = CourseCatalog.parse(
            """[{"courseId":"12345678901234567","title":"測試課","durationSec":"900"}]"""
        )
        assertEquals(900, entries[0].durationSec)
    }

    @Test
    fun `未知欄位不影響解析`() {
        val entries = CourseCatalog.parse(
            """[{"courseId":"12345678901234567","title":"測試課","coach":"某某","level":3}]"""
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun `JSON 壞掉時丟出可顯示的錯誤`() {
        val error = runCatching { CourseCatalog.parse("{ 這不是 JSON") }.exceptionOrNull()
        assertTrue(error is CourseCatalogException)
        assertTrue(error!!.message!!.contains("courses.json"))
    }

    @Test
    fun `缺少 courses 陣列時丟錯而不是回空清單`() {
        val error = runCatching { CourseCatalog.parse("""{"version":1}""") }.exceptionOrNull()
        assertTrue(error is CourseCatalogException)
    }

    @Test
    fun `空的 courses 陣列視為錯誤`() {
        val error = runCatching { CourseCatalog.parse("""{"courses":[]}""") }.exceptionOrNull()
        assertTrue(error is CourseCatalogException)
    }

    @Test
    fun `缺少 courseId 或 title 時丟錯並指出是第幾筆`() {
        val missingId = runCatching {
            CourseCatalog.parse("""[{"title":"沒有編號"}]""")
        }.exceptionOrNull()
        assertTrue(missingId is CourseCatalogException)
        assertTrue(missingId!!.message!!.contains("第 1 筆"))

        val missingTitle = runCatching {
            CourseCatalog.parse("""[{"courseId":"12345678901234567"}]""")
        }.exceptionOrNull()
        assertTrue(missingTitle is CourseCatalogException)
    }

    @Test
    fun `courseId 不是純數字時丟錯`() {
        val error = runCatching {
            CourseCatalog.parse("""[{"courseId":"abc-123","title":"測試課"}]""")
        }.exceptionOrNull()
        assertTrue(error is CourseCatalogException)
    }

    @Test
    fun `courseId 長到塞不進 Long 時丟錯而不是靜默少一支課`() {
        val error = runCatching {
            CourseCatalog.parse("""[{"courseId":"99999999999999999999","title":"測試課"}]""")
        }.exceptionOrNull()
        assertTrue(error is CourseCatalogException)
        assertTrue(error!!.message!!.contains("courseId"))
    }

    @Test
    fun `重複的 courseId 丟錯`() {
        val error = runCatching {
            CourseCatalog.parse(
                """[{"courseId":"12345678901234567","title":"A"},
                    {"courseId":"12345678901234567","title":"B"}]"""
            )
        }.exceptionOrNull()
        assertTrue(error is CourseCatalogException)
        assertTrue(error!!.message!!.contains("重複"))
    }

    // ── .maf 對應 ────────────────────────────────────────────────────────────

    private val motions = listOf(
        "README.md",
        "初階瑜珈-17421914658801191.maf",
        "太極藝術體驗課-17428046223601321.maf",
        "銀髮族健康操-17421781954041251.maf"
    )

    @Test
    fun `目錄有指定 mafAsset 時直接使用`() {
        assertEquals(
            "銀髮族健康操-17421781954041251.maf",
            CourseCatalog.resolveMafAsset("17421781954041251", "銀髮族健康操-17421781954041251.maf", motions)
        )
    }

    @Test
    fun `指定的 mafAsset 不存在時仍回傳該檔名（讓播放頁報評分資料載入失敗）`() {
        // 目錄說好要評分卻缺檔，要顯示「找不到 MAF」而不是偽裝成純播放課程。
        assertEquals(
            "不存在.maf",
            CourseCatalog.resolveMafAsset("17421781954041251", "不存在.maf", motions)
        )
    }

    @Test
    fun `mafAsset 留空時以課程編號比對檔名尾碼`() {
        assertEquals(
            "初階瑜珈-17421914658801191.maf",
            CourseCatalog.resolveMafAsset("17421914658801191", "", motions)
        )
        assertEquals(
            "太極藝術體驗課-17428046223601321.maf",
            CourseCatalog.resolveMafAsset("17428046223601321", "", motions)
        )
    }

    @Test
    fun `檔名只有課程編號也算命中`() {
        assertEquals(
            "17421781954041251.maf",
            CourseCatalog.resolveMafAsset("17421781954041251", "", listOf("17421781954041251.maf"))
        )
    }

    @Test
    fun `沒有對應課程檔時回 null（僅播放不評分）`() {
        assertNull(CourseCatalog.resolveMafAsset("17421784957981721", "", motions))
    }

    @Test
    fun `課程編號不會誤命中其他課程的檔名`() {
        // 尾碼比對必須連 '-' 一起比，否則 …1191 會誤中 …801191 之類的檔名。
        assertNull(CourseCatalog.resolveMafAsset("801191", "", motions))
    }
}
