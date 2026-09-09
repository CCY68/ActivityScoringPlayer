package com.johnson.fitness.data

import kotlin.math.abs

/**
 * IMU 樣本 → **影片時間軸**的換算（評分修復更新計畫 §10.3 P5）。
 *
 * DeviceModule `0bf8604` 起，B20 的每筆 [com.fitness.device.model.ImuData] 都帶
 * `deviceTimestampUs`：裝置端時鐘（µs），感測器重開時歸零，**不受 BLE 送達延遲影響**。
 * 因此影片時間直接用裝置時鐘換算：
 *
 * ```
 * videoTimeMs = (deviceTimestampUs - 錨點裝置時戳) / 1000 + 錨點影片位置
 * ```
 *
 * 錨點**在第一筆樣本真正到達時才綁定**：呼叫端在「影片開始播放」「暫停後續播」時呼叫 [reanchor]、
 * 在「seek」時呼叫 [reanchorAfterSeek] 讓錨點失效，下一筆樣本則以呼叫端當下估到的影片位置
 * （[videoTimeMsFor] 的 `videoPositionMs`）減去該樣本的送達延遲（`sampleAgeMs`）重新綁定。
 *
 * 三件事缺一不可：
 * - 不預先記住重錨當下的影片位置——重錨到第一筆樣本之間可能隔了幾十到幾百毫秒（BLE 尚未恢復
 *   送樣、seek 去抖動），拿舊位置綁新樣本會讓整條時間軸固定落後那段差值。
 * - 要扣掉送達延遲——錨定的那一筆若來自 BLE 積壓，它「到達」時影片已經走過它真正對應的位置，
 *   直接綁會讓**整段**時間軸固定超前積壓量。延遲由 [sampleAgeMs] 估。
 * - 暫停／續播的重錨要保留單調下限——續播後第一批到達的可能是**暫停期間**產生的積壓樣本，
 *   扣掉延遲後會落在暫停前，[videoTimeMsFor] 對這種樣本回傳 `null` 請呼叫端丟掉；
 *   真正往回 seek 才會清掉這個下限（[reanchorAfterSeek]）。
 *
 * 這樣做的兩個重點：
 * - **丟樣就是缺口**：BLE 積壓、每整分鐘的健康資料停頓、斷線重連造成的漏樣，時戳會直接
 *   出現對應的空洞，不會被補成連續樣本（舊的計數式時間軸會把丟樣累積成整條時間軸的漂移）。
 * - **單調**：同一段錨定期間內 `deviceTimestampUs` 單調遞增，換算後也單調；裝置時戳相對
 *   **前一筆**倒退（感測器重開歸零）視為新的一段，以當下影片位置重新綁定（保留重開期間的
 *   真實缺口），並以「上一筆輸出 + [sampleIntervalMs]」為下限確保仍然單調。
 *
 * 沒有裝置時鐘的裝置（非幀式協議品牌，`deviceTimestampUs == null`）退回舊的計數式時間軸：
 * 第一筆用當下影片位置，之後每筆 +[sampleIntervalMs]。
 *
 * 執行緒：BLE 回調執行緒與 ViewModel 主執行緒都會呼叫，故全部方法同步化。
 */
class ImuVideoTimeline(
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS
) {
    private var anchorDeviceUs: Long? = null
    private var anchorVideoMs: Long = 0L
    private var lastDeviceUs: Long? = null
    private var lastVideoTimeMs: Long? = null

    /** 暫停／續播重錨後保留的單調下限：新錨點不得早於它。seek 重錨會清掉。 */
    private var monotonicFloorMs: Long? = null

    /** wall clock 與單調時鐘的差值，用來偵測手機校時。 */
    private var lastClockOffsetMs: Long? = null

    /** 自本次錄製／播放開始累積的 wall clock 調整量，估樣本年齡時要扣掉。 */
    private var clockAdjustmentMs: Long = 0L

    /**
     * 讓錨點失效，但保留單調下限（下一個錨點不得早於上一筆輸出 + 一格）。
     * 影片開始播放、暫停、續播時呼叫。
     */
    @Synchronized
    fun reanchor() {
        monotonicFloorMs = lastVideoTimeMs?.plus(sampleIntervalMs) ?: monotonicFloorMs
        anchorDeviceUs = null
        lastDeviceUs = null
        lastVideoTimeMs = null
    }

    /**
     * seek 專用的重錨：連單調下限一起清掉——往回拉時新的時戳本來就該回到前面，
     * Core 端會另外收到 `engine.seek()`。
     */
    @Synchronized
    fun reanchorAfterSeek() {
        monotonicFloorMs = null
        anchorDeviceUs = null
        lastDeviceUs = null
        lastVideoTimeMs = null
    }

    /** 回到全新狀態（重新開始錄製）。 */
    @Synchronized
    fun reset() {
        anchorDeviceUs = null
        anchorVideoMs = 0L
        lastDeviceUs = null
        lastVideoTimeMs = null
        monotonicFloorMs = null
        lastClockOffsetMs = null
        clockAdjustmentMs = 0L
    }

    /**
     * 估一筆樣本的送達延遲（ms）。
     *
     * `ImuData.timestampMs` 是 DeviceModule 以裝置時鐘換算的**樣本本身**的 epoch 時間，
     * 與收到當下的 wall clock 相減即為 BLE 傳輸＋積壓的延遲。
     *
     * 手機 wall clock 被調整時，DeviceModule 的 epoch 基準要變化超過 30 s 才會重新錨定
     * （見 `DeviceModule-Internal.md` B20StreamClock），小幅校時會讓這個差值**永久**偏掉。
     * 因此這裡以「wall clock − 單調時鐘」的跳動量累積校時量並扣除；跳動超過 DeviceModule 的
     * 重新錨定門檻時，改為歸零（DeviceModule 自己已經重錨）。
     *
     * 回傳值夾在 `0..`[MAX_SAMPLE_AGE_MS]。
     */
    @Synchronized
    fun sampleAgeMs(sampleEpochMs: Long, nowEpochMs: Long, nowElapsedRealtimeMs: Long): Long {
        val offsetMs = nowEpochMs - nowElapsedRealtimeMs
        val previousOffsetMs = lastClockOffsetMs
        lastClockOffsetMs = offsetMs
        if (previousOffsetMs != null) {
            val jumpMs = offsetMs - previousOffsetMs
            if (abs(jumpMs) > CLOCK_JITTER_TOLERANCE_MS) {
                clockAdjustmentMs =
                    if (abs(jumpMs) > DEVICE_EPOCH_RESYNC_THRESHOLD_MS) 0L
                    else clockAdjustmentMs + jumpMs
            }
        }
        return (nowEpochMs - sampleEpochMs - clockAdjustmentMs).coerceIn(0L, MAX_SAMPLE_AGE_MS)
    }

    /**
     * 換算一筆樣本的影片時間（ms）；回傳 `null` 代表這筆是**暫停期間**的積壓樣本，請丟掉。
     *
     * @param deviceTimestampUs 裝置端時鐘（µs）；`null` 代表該裝置沒有裝置時鐘，退回計數式時間軸。
     * @param videoPositionMs   **這筆樣本到達當下**估到的影片位置；需要重新綁定錨點時會用它。
     * @param sampleAgeMs       這筆樣本的送達延遲（ms，見 [sampleAgeMs]）；綁定錨點時會從
     *                          [videoPositionMs] 扣掉，讓積壓樣本不會把整段時間軸推快。
     */
    @Synchronized
    @JvmOverloads
    fun videoTimeMsFor(deviceTimestampUs: Long?, videoPositionMs: Long, sampleAgeMs: Long = 0L): Long? {
        val anchorCandidateMs = videoPositionMs - sampleAgeMs.coerceAtLeast(0L)
        if (deviceTimestampUs == null) {
            val next = lastVideoTimeMs?.plus(sampleIntervalMs)
                ?: monotonicFloorMs?.let { maxOf(anchorCandidateMs, it) }
                ?: anchorCandidateMs
            anchorDeviceUs = null
            lastDeviceUs = null
            lastVideoTimeMs = next
            monotonicFloorMs = null
            return next
        }

        val anchorUs = anchorDeviceUs
        val previousDeviceUs = lastDeviceUs
        // 相對「前一筆」倒退才是感測器重開；跟錨點比會漏掉「重開後又漏掉前幾筆、
        // 新時戳仍大於錨點」的情況（Codex QA 第一輪缺陷 2）。
        val sensorRestarted = previousDeviceUs != null && deviceTimestampUs < previousDeviceUs
        val videoTimeMs = if (anchorUs == null || sensorRestarted) {
            val floorMs = lastVideoTimeMs?.plus(sampleIntervalMs) ?: monotonicFloorMs
            if (anchorUs == null && lastVideoTimeMs == null &&
                floorMs != null && anchorCandidateMs < floorMs
            ) {
                // 續播後最先到達的是暫停期間產生的積壓樣本：那段時間影片沒在播，
                // 硬塞進續播段會讓整段偏移，直接丟掉讓它成為缺口
                // （Codex QA 第三輪缺陷 1）。年齡有上限、影片位置持續前進，最多丟幾筆。
                return null
            }
            // 以當下影片位置重新綁定，重開／中斷期間的真實缺口因此保留在時間軸上；
            // 影片位置的估計誤差不得讓時戳倒退，故以「上一筆 + 一格」為下限。
            val base = floorMs?.let { maxOf(anchorCandidateMs, it) } ?: anchorCandidateMs
            anchorDeviceUs = deviceTimestampUs
            anchorVideoMs = base
            base
        } else {
            anchorVideoMs + (deviceTimestampUs - anchorUs + US_PER_MS / 2) / US_PER_MS
        }
        lastDeviceUs = deviceTimestampUs
        lastVideoTimeMs = videoTimeMs
        monotonicFloorMs = null
        return videoTimeMs
    }

    companion object {
        /** Core 的 canonical rate 25 Hz ⇒ 40 ms。只有沒有裝置時鐘時才會用到。 */
        const val DEFAULT_SAMPLE_INTERVAL_MS = 40L

        /**
         * 送達延遲的採信上限（ms）。BLE 積壓最多也是幾百 ms 的量級；超過這個值多半是
         * DeviceModule 的 epoch 尚未穩定，寧可少扣也不要亂扣。
         */
        const val MAX_SAMPLE_AGE_MS = 2_000L

        /** 兩次讀時鐘之間的正常抖動，不視為校時。 */
        private const val CLOCK_JITTER_TOLERANCE_MS = 200L

        /** DeviceModule（B20StreamClock）自己重新錨定 epoch 的門檻。 */
        private const val DEVICE_EPOCH_RESYNC_THRESHOLD_MS = 30_000L

        private const val US_PER_MS = 1_000L
    }
}
