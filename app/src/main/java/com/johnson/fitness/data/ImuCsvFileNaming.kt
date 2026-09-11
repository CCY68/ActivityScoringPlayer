package com.johnson.fitness.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * IMU CSV 檔名的產生與解析。
 *
 * 新格式 `<courseId>_<yyyy-MM-dd_HH-mm-ss-SSS>.csv`：檔名帶課程編號方便錄製資料頁直接對回課程，
 * 且不含冒號——舊格式 `yyyy-MM-dd'T'HH:mm:ss.SSS.csv` 的冒號在 Windows 是非法檔名字元，
 * `adb pull` 到 Windows 端會整支失敗。舊檔名格式仍要能被解析（[Parsed.courseId] 為 null，
 * 由呼叫端退回檔案 mtime 當錄製時間）。
 *
 * 時間戳保留到毫秒：Android 9 以下 `createWriter()` 是 `File.bufferedWriter()` 直接開檔，
 * 同一秒內重新開始錄製會無聲截斷前一份；毫秒精度與舊格式相同，實務上不會撞名。
 */
internal object ImuCsvFileNaming {

    // 課程編號前段不含底線（純數字或呼叫端傳進來的 "unknown"），第二段固定是新版時間戳格式；
    // 用 [^_]+ 而不是限定數字，才能同時吃下 "unknown" 這個 fallback 值。
    private val NEW_PATTERN = Regex("""^([^_]+)_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3})\.csv$""")
    private val LEGACY_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\.csv$""")

    private const val NEW_TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss-SSS"
    private const val LEGACY_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'.csv'"

    data class Parsed(val courseId: String?, val recordedAtMs: Long?)

    /** 錄製開始時組檔名；[courseId] 拿不到時呼叫端請傳 `"unknown"`。 */
    fun buildFileName(courseId: String, timestamp: Date = Date()): String {
        val safeCourseId = courseId.ifBlank { "unknown" }
        val ts = SimpleDateFormat(NEW_TIMESTAMP_PATTERN, Locale.US).format(timestamp)
        return "${safeCourseId}_$ts.csv"
    }

    /** [Parsed.recordedAtMs] 為 null 代表檔名解不出時間（不符任何已知格式），呼叫端請退回檔案 mtime。 */
    fun parse(fileName: String): Parsed {
        NEW_PATTERN.matchEntire(fileName)?.let { match ->
            val (courseId, timestampPart) = match.destructured
            val recordedAt = runCatching {
                SimpleDateFormat(NEW_TIMESTAMP_PATTERN, Locale.US).parse(timestampPart)?.time
            }.getOrNull()
            return Parsed(courseId, recordedAt)
        }
        if (LEGACY_PATTERN.matches(fileName)) {
            val recordedAt = runCatching {
                SimpleDateFormat(LEGACY_TIMESTAMP_PATTERN, Locale.US).parse(fileName)?.time
            }.getOrNull()
            return Parsed(null, recordedAt)
        }
        return Parsed(null, null)
    }
}
