package com.johnson.fitness.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.fitness.activityscoringcore.signal.RawImuSample
import com.fitness.device.api.IDeviceManager
import com.fitness.device.api.IImuDataListener
import com.fitness.device.model.ImuData
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.Locale

/** B20 IMU CSV 的錄製與讀取。錄製 listener 與健康資料管線完全獨立。 */
class ImuCsvStore(
    private val context: Context,
    private val deviceManager: IDeviceManager,
    private val videoPositionProvider: (elapsedRealtimeMs: Long) -> Long?
) {
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var outputUri: Uri? = null
    private var mediaStoreOutput = false

    // 錄製時戳與送進 Core 的即時時戳走同一套換算（P5）：以裝置端時鐘（ImuData.deviceTimestampUs）
    // 換算影片時間，開播／續播／seek 由呼叫端 reanchor()。舊的「與預期差 > 500 ms 就重錨」規則
    // 會把真實丟樣抹平成連續樣本，已拿掉；沒有裝置時鐘的裝置仍退回計數式時間軸。
    private val timeline = ImuVideoTimeline(IMU_SAMPLE_INTERVAL_MS)

    private val listener = object : IImuDataListener {
        override fun onImuData(data: ImuData) {
            synchronized(lock) {
                val elapsedRealtimeMs = SystemClock.elapsedRealtime()
                val videoPositionMs = videoPositionProvider(elapsedRealtimeMs) ?: return
                val ageMs = timeline.sampleAgeMs(
                    data.timestampMs, System.currentTimeMillis(), elapsedRealtimeMs
                )
                // null＝暫停期間的積壓樣本，不寫進 CSV（留成缺口）
                val timestampMs = timeline.videoTimeMsFor(data.deviceTimestampUs, videoPositionMs, ageMs)
                    ?: return
                writer?.run {
                    append(timestampMs.toString())
                    append(',').append(data.ax.toString())
                    append(',').append(data.ay.toString())
                    append(',').append(data.az.toString())
                    append(',').append(data.gx.toString())
                    append(',').append(data.gy.toString())
                    append(',').append(data.gz.toString())
                    newLine()
                }
            }
        }
    }

    /** 影片開播／暫停／續播後重錨錄製時間軸（與 [ImuVideoTimeline.reanchor] 同語意）。 */
    fun reanchor() {
        synchronized(lock) { timeline.reanchor() }
    }

    /** seek 後重錨（與 [ImuVideoTimeline.reanchorAfterSeek] 同語意）。 */
    fun reanchorAfterSeek() {
        synchronized(lock) { timeline.reanchorAfterSeek() }
    }

    /** [courseId] 用來讓檔名帶課程編號（見 [ImuCsvFileNaming]）；拿不到請傳 `"unknown"`。 */
    fun startRecording(courseId: String): Result<String> = runCatching {
        synchronized(lock) {
            check(writer == null) { "IMU 已在錄製中" }
            val fileName = ImuCsvFileNaming.buildFileName(courseId)
            try {
                val (uri, newWriter) = createWriter(fileName)
                outputUri = uri
                timeline.reset()
                writer = newWriter.apply {
                    appendLine(CSV_HEADER)
                    flush()
                }
                deviceManager.addImuDataListener(listener)
                fileName
            } catch (error: Throwable) {
                writer?.close()
                writer = null
                outputUri?.let { uri -> context.contentResolver.delete(uri, null, null) }
                outputUri = null
                mediaStoreOutput = false
                timeline.reset()
                throw error
            }
        }
    }

    fun stopRecording(): Result<Uri?> = runCatching {
        synchronized(lock) {
            if (writer == null) return@runCatching outputUri
            val completedUri = outputUri
            try {
                deviceManager.removeImuDataListener(listener)
            } finally {
                writer?.flush()
                writer?.close()
                writer = null
                if (mediaStoreOutput && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    completedUri?.let { uri ->
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                            null,
                            null
                        )
                    }
                }
                outputUri = null
                mediaStoreOutput = false
            }
            completedUri
        }
    }

    /** 錄製資料頁的一列。[courseId] 為 null 代表舊檔名或無法解析，畫面應顯示「未知課程」。 */
    data class Recording(
        val uri: Uri,
        val fileName: String,
        val courseId: String?,
        /** epoch ms；檔名能解析出時間就用檔名，否則退回檔案 mtime。 */
        val recordedAt: Long,
        val bytes: Long
    )

    data class RecordingSummary(val sampleCount: Int, val firstMs: Long, val lastMs: Long)

    /**
     * 列出本機已收錄的 IMU CSV，依 [Recording.recordedAt] 新到舊排序。
     * &lt; Q 掃 App 專屬 Documents 目錄；Q+ 查 MediaStore 的 `Download/ActivityScoringPlayer/`。
     * 會讀檔案系統／ContentResolver，呼叫端請放在背景執行緒。
     */
    fun listRecordings(): List<Recording> {
        val recordings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listRecordingsFromMediaStore()
        } else {
            listRecordingsFromDocumentsDir()
        }
        return recordings.sortedByDescending { it.recordedAt }
    }

    private fun listRecordingsFromDocumentsDir(): List<Recording> {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return emptyList()
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("csv", ignoreCase = true) }
            ?: return emptyList()
        return files.map { file ->
            val parsed = ImuCsvFileNaming.parse(file.name)
            Recording(
                uri = Uri.fromFile(file),
                fileName = file.name,
                courseId = parsed.courseId,
                recordedAt = parsed.recordedAtMs ?: file.lastModified(),
                bytes = file.length()
            )
        }
    }

    private fun listRecordingsFromMediaStore(): List<Recording> {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        // RELATIVE_PATH 實際寫進 MediaStore 時 Android 會補一個結尾的 "/"，用 LIKE 才不會因為
        // 有沒有那個斜線就查不到（createWriter() 寫入時沒有帶）。
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/ActivityScoringPlayer%", "0")
        val recordings = mutableListOf<Recording>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (!name.endsWith(".csv", ignoreCase = true)) continue
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                val parsed = ImuCsvFileNaming.parse(name)
                // DATE_MODIFIED 這個欄位存的是秒，不是毫秒。
                val mtimeMs = cursor.getLong(modifiedCol) * 1000L
                recordings += Recording(
                    uri = uri,
                    fileName = name,
                    courseId = parsed.courseId,
                    recordedAt = parsed.recordedAtMs ?: mtimeMs,
                    bytes = cursor.getLong(sizeCol)
                )
            }
        }
        return recordings
    }

    /** &lt; Q 直接刪檔；Q+ 交給 `ContentResolver`（連同 MediaStore 索引一起清掉）。 */
    fun deleteRecording(uri: Uri): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            uri.path?.let { File(it).delete() } ?: false
        }
    }

    /**
     * 算筆數與時間跨度供列表顯示。檔案約 2 MB，逐行掃過去、不整檔讀成 [RawImuSample] 清單
     * （這裡不需要排序或欄位型別轉換，只取 timestamp 欄位）。
     */
    fun summarize(uri: Uri): RecordingSummary {
        val reader = context.contentResolver.openInputStream(uri)?.bufferedReader()
            ?: error("無法開啟 CSV 檔案")
        return reader.use { r ->
            val header = r.readLine()?.split(',')?.map { it.trim().lowercase(Locale.US) }
                ?: error("CSV 是空檔案")
            // 跟 parse() 同一套規則：舊版 video_position_ms 語意上等於新版的 timestamp_ms。
            val legacyVideoPosition = header.indexOf("video_position_ms").takeIf { it >= 0 }
            val timestampCol = legacyVideoPosition
                ?: header.indexOf("timestamp_ms").takeIf { it >= 0 }
                ?: error("CSV 缺少 timestamp_ms 欄位")
            var count = 0
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            r.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val value = line.split(',').getOrNull(timestampCol)?.trim()?.toLongOrNull()
                    ?: return@forEach
                count++
                if (value < first) first = value
                if (value > last) last = value
            }
            if (count == 0) RecordingSummary(0, 0L, 0L) else RecordingSummary(count, first, last)
        }
    }

    fun read(uri: Uri): List<RawImuSample> {
        val reader = context.contentResolver.openInputStream(uri)?.bufferedReader()
            ?: error("無法開啟 CSV 檔案")
        return reader.use(::parse)
    }

    private fun parse(reader: java.io.BufferedReader): List<RawImuSample> {
        val header = reader.readLine()?.split(',')?.map { it.trim().lowercase(Locale.US) }
            ?: error("CSV 是空檔案")
        fun column(name: String): Int = header.indexOf(name).takeIf { it >= 0 }
            ?: error("CSV 缺少 $name 欄位")

        val timestamp = column("timestamp_ms")
        // 舊版 CSV 同時保存裝置時間 timestamp_ms 與影片時間 video_position_ms；讀取舊檔時
        // 優先採用影片時間，轉成新版單一 timestamp_ms 的語意。
        val legacyVideoPosition = header.indexOf("video_position_ms").takeIf { it >= 0 }
        val ax = column("ax")
        val ay = column("ay")
        val az = column("az")
        val gx = column("gx")
        val gy = column("gy")
        val gz = column("gz")
        val requiredMax = listOfNotNull(timestamp, legacyVideoPosition, ax, ay, az, gx, gy, gz).max()

        return reader.lineSequence().mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            val values = line.split(',').map(String::trim)
            require(values.size > requiredMax) { "CSV 第 ${index + 2} 列欄位不足" }
            RawImuSample(
                timestampMs = legacyVideoPosition?.let { values[it].toLong() }
                    ?: values[timestamp].toLong(),
                ax = values[ax].toFloat(), ay = values[ay].toFloat(), az = values[az].toFloat(),
                gx = values[gx].toFloat(), gy = values[gy].toFloat(), gz = values[gz].toFloat(),
                packetId = index.toLong()
            )
        }.toList()
            .sortedBy(RawImuSample::timestampMs)
            .also { require(it.isNotEmpty()) { "CSV 沒有 IMU 資料" } }
    }

    private fun createWriter(fileName: String): Pair<Uri, BufferedWriter> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ActivityScoringPlayer")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("無法建立 CSV 檔案")
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: error("無法寫入 CSV 檔案")
            mediaStoreOutput = true
            return uri to BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
        }

        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: error("無法取得文件目錄")
        directory.mkdirs()
        val file = File(directory, fileName)
        return Uri.fromFile(file) to file.bufferedWriter(Charsets.UTF_8)
    }

    private companion object {
        const val CSV_HEADER = "timestamp_ms,ax,ay,az,gx,gy,gz"
        const val IMU_SAMPLE_INTERVAL_MS = ImuVideoTimeline.DEFAULT_SAMPLE_INTERVAL_MS
    }
}
