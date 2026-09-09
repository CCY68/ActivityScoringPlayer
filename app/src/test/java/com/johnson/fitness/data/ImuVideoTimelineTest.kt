package com.johnson.fitness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5：以 `ImuData.deviceTimestampUs` 換算影片時間軸的單元測試。
 *
 * 驗收情境對應評分修復更新計畫 §10.4「seek、斷線重連、每整分鐘 BLE 停頓三種情境下
 * Core 收到的時戳單調且與影片時間對齊」。
 */
class ImuVideoTimelineTest {

    private val intervalUs = 40_000L

    /** 25 Hz 連續樣本：換算後每筆固定 40 ms，且以第一筆到達當下的影片位置為起點。 */
    @Test
    fun `連續樣本換算為 40 ms 等距`() {
        val timeline = ImuVideoTimeline()
        timeline.reanchor()

        val out = (0 until 25).map { i ->
            timeline.videoTimeMsFor(5_000_000L + i * intervalUs, videoPositionMs = 1_000L)
        }

        assertEquals(1_000L, out.first())
        assertEquals((0 until 25).map { 1_000L + it * 40L }, out)
    }

    /**
     * 錨點是「第一筆樣本到達當下」的影片位置，不是重錨當下的位置。
     * seek／續播後 BLE 可能隔一段時間才恢復送樣，用舊位置綁會讓整條時間軸固定落後（Codex QA 缺陷 1）。
     */
    @Test
    fun `錨點綁在第一筆樣本到達當下的影片位置`() {
        val timeline = ImuVideoTimeline()
        timeline.reanchorAfterSeek() // 使用者 seek 到 60,000 ms

        // 160 ms 之後才收到第一筆樣本，此時播放器已經走到 60,160 ms。
        assertEquals(60_160L, timeline.videoTimeMsFor(987_654_321L, videoPositionMs = 60_160L))
        assertEquals(60_200L, timeline.videoTimeMsFor(987_654_321L + intervalUs, videoPositionMs = 60_200L))
    }

    /** 沒有重錨過（例如尚未開播就收到樣本）時，一樣以當下影片位置起算。 */
    @Test
    fun `第一筆樣本以當下影片位置起算`() {
        val timeline = ImuVideoTimeline()

        assertEquals(700L, timeline.videoTimeMsFor(0L, videoPositionMs = 700L))
        assertEquals(740L, timeline.videoTimeMsFor(intervalUs, videoPositionMs = 999_999L))
    }

    /** seek：重錨後下一筆樣本落在新的影片位置，之後仍以裝置時鐘遞增。 */
    @Test
    fun `seek 後重新錨定到新的影片位置`() {
        val timeline = ImuVideoTimeline()
        repeat(10) { i -> timeline.videoTimeMsFor(i * intervalUs, videoPositionMs = i * 40L) }

        // 使用者往前拉到 60 s。
        timeline.reanchorAfterSeek()
        assertEquals(60_000L, timeline.videoTimeMsFor(10 * intervalUs, videoPositionMs = 60_000L))
        assertEquals(60_040L, timeline.videoTimeMsFor(11 * intervalUs, videoPositionMs = 60_040L))

        // 往回拉到 5 s 同樣成立（Core 端會另外收到 seek）。
        timeline.reanchorAfterSeek()
        assertEquals(5_000L, timeline.videoTimeMsFor(12 * intervalUs, videoPositionMs = 5_000L))
        assertEquals(5_040L, timeline.videoTimeMsFor(13 * intervalUs, videoPositionMs = 5_040L))
    }

    /** 暫停期間裝置時鐘照走，續播重錨後不會把暫停時間灌進影片時間軸。 */
    @Test
    fun `暫停續播不把暫停時間算進影片時間`() {
        val timeline = ImuVideoTimeline()
        timeline.videoTimeMsFor(0L, videoPositionMs = 0L)
        assertEquals(40L, timeline.videoTimeMsFor(intervalUs, videoPositionMs = 40L))

        // 暫停 30 s（裝置時鐘照走），在原位置續播。續播後第一筆的影片位置必須超過
        // 「上一筆 + 一格」才會錨定（等於已消費過的時刻不再重複送）。
        timeline.reanchor()
        assertEquals(80L, timeline.videoTimeMsFor(30_000_000L, videoPositionMs = 80L))
        assertEquals(120L, timeline.videoTimeMsFor(30_040_000L, videoPositionMs = 120L))
    }

    /** 每整分鐘的健康資料停頓／BLE 積壓造成漏樣：時間軸留缺口，不補樣本。 */
    @Test
    fun `丟樣以缺口呈現而非補樣本`() {
        val timeline = ImuVideoTimeline()
        timeline.videoTimeMsFor(0L, videoPositionMs = 0L)

        // 中間掉了 12 筆（約 480 ms）。
        assertEquals(520L, timeline.videoTimeMsFor(13 * intervalUs, videoPositionMs = 520L))
        assertEquals(560L, timeline.videoTimeMsFor(14 * intervalUs, videoPositionMs = 560L))
    }

    /** 斷線重連（DeviceModule 沿用同一條裝置時間軸）：時戳連續，缺口等於斷線長度。 */
    @Test
    fun `斷線重連沿用裝置時鐘且時戳單調`() {
        val timeline = ImuVideoTimeline()

        val before = (0 until 5).map {
            timeline.videoTimeMsFor(it * intervalUs, videoPositionMs = it * 40L)!!
        }
        // 斷線 8 s 後重連，裝置時鐘從 8.2 s 續傳。
        val after = (0 until 5).map {
            timeline.videoTimeMsFor(8_200_000L + it * intervalUs, videoPositionMs = 8_200L + it * 40L)!!
        }

        assertEquals(listOf(0L, 40L, 80L, 120L, 160L), before)
        assertEquals(listOf(8_200L, 8_240L, 8_280L, 8_320L, 8_360L), after)
        assertTrue((before + after).zipWithNext().all { (a, b) -> b > a })
    }

    /** 感測器重開會讓裝置時鐘歸零：以當下影片位置重新綁定，保留重開期間的真實缺口。 */
    @Test
    fun `感測器重開歸零時保留真實缺口`() {
        val timeline = ImuVideoTimeline()
        val head = (0 until 3).map {
            timeline.videoTimeMsFor(600_000_000L + it * intervalUs, videoPositionMs = it * 40L)!!
        }

        // 手環重開，裝置時鐘歸零；此時影片已經走到 9,000 ms（中間 8.9 s 沒有資料）。
        val tail = (0 until 3).map {
            timeline.videoTimeMsFor(it * intervalUs, videoPositionMs = 9_000L + it * 40L)!!
        }

        assertEquals(listOf(0L, 40L, 80L), head)
        assertEquals(listOf(9_000L, 9_040L, 9_080L), tail)
        assertTrue((head + tail).zipWithNext().all { (a, b) -> b > a })
    }

    /**
     * 重開後又漏掉最初幾筆，新時戳雖然小於前一筆卻仍大於錨點——只跟錨點比較會漏判，
     * 時戳會直接倒退（Codex QA 缺陷 2）。
     */
    @Test
    fun `重開後時戳仍大於錨點也要判定為重開`() {
        val timeline = ImuVideoTimeline()
        assertEquals(0L, timeline.videoTimeMsFor(40_000L, videoPositionMs = 0L))
        assertEquals(9_960L, timeline.videoTimeMsFor(10_000_000L, videoPositionMs = 9_960L))

        // 重開後漏掉最初兩筆，第一筆到達時裝置時鐘是 80,000 µs（> 錨點 40,000 µs）。
        val afterRestart = timeline.videoTimeMsFor(80_000L, videoPositionMs = 10_500L)

        assertEquals(10_500L, afterRestart)
        assertEquals(10_540L, timeline.videoTimeMsFor(120_000L, videoPositionMs = 10_540L))
    }

    /** 影片位置的估計誤差不得讓時戳倒退：重開時以「上一筆 + 一格」為下限。 */
    @Test
    fun `重開時影片位置落後仍不倒退`() {
        val timeline = ImuVideoTimeline()
        assertEquals(1_000L, timeline.videoTimeMsFor(500_000L, videoPositionMs = 1_000L))
        assertEquals(1_040L, timeline.videoTimeMsFor(540_000L, videoPositionMs = 1_040L))

        // 影片位置回報成 1,010 ms（比上一筆輸出還早）。
        assertEquals(1_080L, timeline.videoTimeMsFor(0L, videoPositionMs = 1_010L))
    }

    /** 非幀式協議裝置沒有裝置時鐘：退回舊的計數式時間軸。 */
    @Test
    fun `沒有裝置時戳時退回計數式時間軸`() {
        val timeline = ImuVideoTimeline()
        timeline.reanchor()

        val out = (0 until 5).map { timeline.videoTimeMsFor(null, videoPositionMs = 2_000L) }
        assertEquals(listOf(2_000L, 2_040L, 2_080L, 2_120L, 2_160L), out)

        timeline.reanchor()
        assertEquals(9_000L, timeline.videoTimeMsFor(null, videoPositionMs = 9_000L))
        assertEquals(9_040L, timeline.videoTimeMsFor(null, videoPositionMs = 9_010L))
    }

    /** 混合情況：中途才拿到裝置時鐘（或反之）都要接得上，不得跳時也不得倒退。 */
    @Test
    fun `有無裝置時戳交替時不跳時`() {
        val timeline = ImuVideoTimeline()

        assertEquals(0L, timeline.videoTimeMsFor(null, videoPositionMs = 0L))
        assertEquals(40L, timeline.videoTimeMsFor(null, videoPositionMs = 40L))
        // 突然開始帶裝置時戳：以當下影片位置重新綁定（不得早於上一筆 + 一格）。
        assertEquals(80L, timeline.videoTimeMsFor(1_000_000L, videoPositionMs = 80L))
        assertEquals(120L, timeline.videoTimeMsFor(1_040_000L, videoPositionMs = 120L))
        // 又沒有了：計數式接續。
        assertEquals(160L, timeline.videoTimeMsFor(null, videoPositionMs = 160L))
    }

    /** 104 Hz 原生時鐘（9.615 ms）換算採四捨五入，不得逐筆累積截斷誤差。 */
    @Test
    fun `非整數毫秒間隔四捨五入不累積誤差`() {
        val timeline = ImuVideoTimeline()
        val periodUs = Math.round(1_000_000.0 / 104)

        val out = (0 until 104).map { timeline.videoTimeMsFor(it * periodUs, videoPositionMs = 0L)!! }

        assertEquals(0L, out.first())
        // 第 104 筆（第 1 秒的最後一筆）應該非常接近 1000 ms，截斷式累積會少約 40 ms。
        assertEquals(990L, out.last())
        assertTrue(out.zipWithNext().all { (a, b) -> b >= a })
    }

    /**
     * 錨定的那一筆若來自 BLE 積壓，要扣掉送達延遲；否則整段時間軸會固定超前積壓量
     * （Codex QA 第二輪缺陷 1）。
     */
    @Test
    fun `以積壓樣本錨定時扣掉送達延遲`() {
        val timeline = ImuVideoTimeline()
        timeline.reanchor()

        // 這筆樣本實際對應影片 60,000 ms，但積壓 1 s 才送到（此時播放器已在 61,000 ms）。
        assertEquals(
            60_000L,
            timeline.videoTimeMsFor(500_000L, videoPositionMs = 61_000L, sampleAgeMs = 1_000L)
        )
        // 同一幀後續樣本沿用錨點，不再讀影片位置。
        assertEquals(
            60_040L,
            timeline.videoTimeMsFor(540_000L, videoPositionMs = 61_040L, sampleAgeMs = 960L)
        )
    }

    /** 送達延遲不得讓錨點早於上一筆輸出（重開時仍以「上一筆 + 一格」為下限）。 */
    @Test
    fun `送達延遲不得造成時戳倒退`() {
        val timeline = ImuVideoTimeline()
        assertEquals(1_000L, timeline.videoTimeMsFor(500_000L, videoPositionMs = 1_000L))
        assertEquals(1_040L, timeline.videoTimeMsFor(540_000L, videoPositionMs = 1_040L))

        // 感測器重開，且錨定的那一筆積壓了 1 s。
        assertEquals(
            1_080L,
            timeline.videoTimeMsFor(0L, videoPositionMs = 1_080L, sampleAgeMs = 1_000L)
        )
    }

    /**
     * 續播後最先到達的若是暫停期間產生的積壓樣本（扣掉年齡後落在暫停前），要丟掉而不是
     * 硬塞進續播段（Codex QA 第三輪缺陷 1）。
     */
    @Test
    fun `續播後暫停期間的積壓樣本要丟掉`() {
        val timeline = ImuVideoTimeline()
        timeline.videoTimeMsFor(0L, videoPositionMs = 59_920L)
        assertEquals(59_960L, timeline.videoTimeMsFor(intervalUs, videoPositionMs = 59_960L))

        // 使用者暫停在 60,000 ms，續播 20 ms 後收到一筆年齡 300 ms 的積壓樣本
        // （它其實產生於暫停期間，對應影片 59,720 ms）。
        timeline.reanchor()
        assertNull(
            timeline.videoTimeMsFor(10_000_000L, videoPositionMs = 60_020L, sampleAgeMs = 300L)
        )

        // 之後真正屬於續播段的樣本正常錨定。
        assertEquals(
            60_100L,
            timeline.videoTimeMsFor(10_120_000L, videoPositionMs = 60_140L, sampleAgeMs = 40L)
        )
    }

    /** 往回 seek 會清掉單調下限：新時戳本來就該回到前面（Core 端另外收到 engine.seek()）。 */
    @Test
    fun `往回 seek 允許時戳回到前面`() {
        val timeline = ImuVideoTimeline()
        timeline.videoTimeMsFor(0L, videoPositionMs = 60_000L)
        assertEquals(60_040L, timeline.videoTimeMsFor(intervalUs, videoPositionMs = 60_040L))

        timeline.reanchorAfterSeek()
        assertEquals(5_000L, timeline.videoTimeMsFor(2 * intervalUs, videoPositionMs = 5_000L))
    }

    /**
     * 手機 wall clock 被小幅校時（< DeviceModule 的 30 s 重錨門檻）時，DeviceModule 的 epoch
     * 基準不會更新，樣本年齡會**永久**偏掉；要靠「wall clock − 單調時鐘」的跳動量扣回來
     * （Codex QA 第三輪缺陷 2）。
     */
    @Test
    fun `wall clock 校時不會污染樣本年齡`() {
        val timeline = ImuVideoTimeline()

        // 正常狀態：epoch 與單調時鐘差 1,000,000；樣本延遲 60 ms。
        assertEquals(60L, timeline.sampleAgeMs(1_000_940L, 1_001_000L, 1_000L))
        // 手機往前校時 5 s：wall clock 跳 5 s，單調時鐘只走 40 ms。
        // DeviceModule 的 timestampMs 仍以**舊** epoch 換算（不會跟著跳），所以原始差值多了 5 s。
        assertEquals(60L, timeline.sampleAgeMs(1_000_980L, 1_006_040L, 1_040L))
        // 之後每一筆都要持續扣掉同一個校時量。
        assertEquals(60L, timeline.sampleAgeMs(1_001_020L, 1_006_080L, 1_080L))
    }

    /** 跳動超過 DeviceModule 自己的重錨門檻（30 s）時，改為歸零——它已經重新錨定過。 */
    @Test
    fun `校時超過裝置重錨門檻時不再補償`() {
        val timeline = ImuVideoTimeline()
        assertEquals(60L, timeline.sampleAgeMs(1_000_940L, 1_001_000L, 1_000L))

        // 往前校時 60 s：DeviceModule 會重新錨定 epoch，樣本時間跟著新的 wall clock。
        assertEquals(60L, timeline.sampleAgeMs(1_060_980L, 1_061_040L, 1_040L))
        assertEquals(60L, timeline.sampleAgeMs(1_061_020L, 1_061_080L, 1_080L))
    }

    /** 送達延遲的上限與負值處理。 */
    @Test
    fun `送達延遲估算夾在合理範圍`() {
        val timeline = ImuVideoTimeline()
        assertEquals(0L, timeline.sampleAgeMs(sampleEpochMs = 1_000L, nowEpochMs = 900L, nowElapsedRealtimeMs = 0L))
        assertEquals(
            ImuVideoTimeline.MAX_SAMPLE_AGE_MS,
            timeline.sampleAgeMs(sampleEpochMs = 0L, nowEpochMs = 60_000L, nowElapsedRealtimeMs = 59_100L)
        )
    }

    /** reset() 後回到全新狀態。 */
    @Test
    fun `reset 後以影片位置重新起算`() {
        val timeline = ImuVideoTimeline()
        timeline.videoTimeMsFor(0L, videoPositionMs = 1_000L)

        timeline.reset()
        assertEquals(300L, timeline.videoTimeMsFor(5_000_000L, videoPositionMs = 300L))
    }
}
