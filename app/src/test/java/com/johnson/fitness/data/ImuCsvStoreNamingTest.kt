package com.johnson.fitness.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ImuCsvFileNaming` 的新／舊兩種檔名格式解析，純 JVM 測試（不碰 Android API）。
 *
 * 新格式 `<courseId>_<yyyy-MM-dd_HH-mm-ss-SSS>.csv` 是錄製資料頁（PR）新增的，
 * 目的是讓檔名不含 Windows 非法字元的冒號，並帶課程編號方便回放對回課程；
 * 舊格式（帶冒號的 ISO 時間）錄過的檔案仍要能被列出，只是解析不出 courseId。
 */
class ImuCsvStoreNamingTest {

    @Test
    fun `新格式解析出課程編號與時間`() {
        val parsed = ImuCsvFileNaming.parse("17421781954041251_2026-09-11_22-33-55-680.csv")

        assertEquals("17421781954041251", parsed.courseId)
        assertEquals(expectedMs("2026-09-11_22-33-55-680"), parsed.recordedAtMs)
    }

    @Test
    fun `新格式沒有課程編號時退回 unknown`() {
        val parsed = ImuCsvFileNaming.parse("unknown_2026-09-11_22-33-55-680.csv")

        assertEquals("unknown", parsed.courseId)
        assertEquals(expectedMs("2026-09-11_22-33-55-680"), parsed.recordedAtMs)
    }

    @Test
    fun `舊格式的 courseId 解析為 null`() {
        val parsed = ImuCsvFileNaming.parse("2026-09-11T22:33:55.680.csv")

        assertNull(parsed.courseId)
        assertEquals(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).parse("2026-09-11T22:33:55.680")!!.time,
            parsed.recordedAtMs
        )
    }

    @Test
    fun `不符任何已知格式時兩個欄位都是 null`() {
        val parsed = ImuCsvFileNaming.parse("random-file-name.csv")

        assertNull(parsed.courseId)
        assertNull(parsed.recordedAtMs)
    }

    @Test
    fun `buildFileName 產出的檔名能被自己解析回同一個課程編號`() {
        val fileName = ImuCsvFileNaming.buildFileName("17421781954041251")

        assertEquals(true, fileName.startsWith("17421781954041251_"))
        assertEquals(true, fileName.endsWith(".csv"))
        assertEquals("17421781954041251", ImuCsvFileNaming.parse(fileName).courseId)
    }

    @Test
    fun `buildFileName 的 courseId 空白時退回 unknown`() {
        val fileName = ImuCsvFileNaming.buildFileName("")

        assertEquals(true, fileName.startsWith("unknown_"))
    }

    private fun expectedMs(timestampPart: String): Long =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).parse(timestampPart)!!.time

    @Test
    fun sameSecondDifferentMillis_yieldDistinctFileNames() {
        // 同一秒內重新開始錄製不能撞名——Android 9 以下是直接開檔覆寫，撞名等於無聲遺失前一份
        val base = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse("2026-09-11 22:33:55.100")!!
        val a = ImuCsvFileNaming.buildFileName("17421781954041251", base)
        val b = ImuCsvFileNaming.buildFileName("17421781954041251", Date(base.time + 500))
        assertNotEquals(a, b)
        assertEquals("17421781954041251_2026-09-11_22-33-55-100.csv", a)
    }
}
