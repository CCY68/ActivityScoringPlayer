package com.johnson.fitness.ui.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitness.device.api.IDeviceManager
import com.fitness.device.model.ConnectionState
import com.fitness.device.model.ImuSampleRate
import com.fitness.activityscoringcore.api.Availability
import com.fitness.activityscoringcore.api.Score
import com.fitness.activityscoringcore.api.ScoreReason
import com.fitness.activityscoringcore.engine.ScoringEngine
import com.motionmaf.format.MafLoadResult
import com.johnson.fitness.data.CourseDisplaySettings
import com.johnson.fitness.data.DeviceAutoConnect
import com.johnson.fitness.data.LastDevicePreferences
import com.johnson.fitness.data.CoursePlayUrlRepository
import com.johnson.fitness.data.MovieRepository
import com.johnson.fitness.data.ScoringEngineFactory
import com.johnson.fitness.data.ImuCsvStore
import com.johnson.fitness.data.ImuVideoTimeline
import com.fitness.activityscoringcore.signal.RawImuSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Core 分數要拿給使用者看的最低 confidence（評分修復更新計畫 §0-C、Player PR-P1） */
private const val MIN_DISPLAY_CONFIDENCE = 0.3f

/** 可拿給使用者看的分數：可用且 Core 對這筆分數有足夠信心（靜止／訊號無週期結構時 confidence ≈ 0）。 */
private fun Score.isDisplayable(): Boolean =
    availability == Availability.AVAILABLE && confidence >= MIN_DISPLAY_CONFIDENCE

class PlaybackViewModel(
    val movieId: Long,
    private val engineFactory: ScoringEngineFactory,
    val deviceManager: IDeviceManager,
    private val appContext: Context,
    private val lastDevicePreferences: LastDevicePreferences
) : ViewModel() {

    private val _state = MutableStateFlow(
        PlaybackState(
            movie = MovieRepository.getMovieById(appContext, movieId),
            // 課程設定（§9.2 弱訊號太極）：太極不顯示活動三項與三面向分數，只留參與時間與生理摘要
            showActivityStats = CourseDisplaySettings.showActivityStats(movieId)
        )
    )
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _effect = Channel<PlaybackEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private val engine: ScoringEngine = engineFactory.create()
    private val motionAdapter = MotionDataAdapter(deviceManager)
    private val imuCsvStore = ImuCsvStore(appContext, deviceManager) { elapsedRealtimeMs ->
        estimateVideoPositionMs(elapsedRealtimeMs).takeIf { videoIsPlaying }
    }
    private var csvSamples: List<RawImuSample> = emptyList()
    private var csvReplayIndex = 0

    @Volatile private var videoPositionMs = 0L
    @Volatile private var videoIsPlaying = false

    /** 最後一筆**有效**心率到達時的 `elapsedRealtime`；0 代表這堂課還沒收過心率。 */
    @Volatile private var lastHeartRateElapsedRealtimeMs = 0L
    @Volatile private var videoClockAnchorPositionMs = 0L
    @Volatile private var videoClockAnchorElapsedRealtimeMs = 0L
    @Volatile private var videoPlaybackSpeed = 1f

    private fun estimateVideoPositionMs(elapsedRealtimeMs: Long): Long {
        if (!videoIsPlaying || videoClockAnchorElapsedRealtimeMs == 0L) {
            return videoClockAnchorPositionMs
        }
        val elapsedMs = (elapsedRealtimeMs - videoClockAnchorElapsedRealtimeMs).coerceAtLeast(0L)
        return (videoClockAnchorPositionMs + elapsedMs * videoPlaybackSpeed)
            .toLong()
            .coerceAtLeast(0L)
    }

    // 心率回調沒有時間戳，影片第一次開始播放時記錄 wall clock 與影片時間的差值供它換算。
    @Volatile private var videoTimeOffsetMs: Long? = null

    private fun toVideoTimeMs(deviceEpochMs: Long): Long? = videoTimeOffsetMs?.let { deviceEpochMs - it }

    // IMU 的影片時間軸（P5）：DeviceModule 0bf8604 起 B20 每筆樣本都帶裝置端時鐘
    // （ImuData.deviceTimestampUs，不受 BLE 送達延遲影響），時間軸直接由它換算，
    // 開播／續播／seek 時重新錨定到播放器位置。丟樣會如實變成時間軸上的缺口（不補樣本），
    // 舊的計數式時間軸則會把丟樣累積成整條時間軸的漂移。詳見 ImuVideoTimeline。
    private val imuTimeline = ImuVideoTimeline(IMU_SAMPLE_INTERVAL_MS)

    /**
     * 開播、暫停、續播後讓 IMU 與錄製兩條時間軸的錨點失效（保留單調下限），下一筆樣本會以它
     * 到達當下的影片位置重新綁定。**與是否在評分無關**：不評分播放時的 CSV 錄製一樣要正確
     * （Codex QA 第一輪缺陷 3）。
     */
    private fun reanchorImuTimelines() {
        imuTimeline.reanchor()
        imuCsvStore.reanchor()
    }

    /** seek 後的重錨：連單調下限一起清掉（往回拉時時戳本來就該回到前面）。 */
    private fun reanchorImuTimelinesAfterSeek() {
        imuTimeline.reanchorAfterSeek()
        imuCsvStore.reanchorAfterSeek()
    }

    private var feedbackDismissJob: Job? = null
    private var mafLoadJob: Job? = null
    private var videoUrlJob: Job? = null
    private var deviceBridgeJob: Job? = null
    private var csvReplayJob: Job? = null
    private var seekJob: Job? = null
    private var recordingAutoStopJob: Job? = null
    private var receivedImuSampleCount = 0L

    // ActivityScoringCore 已不提供聚合總分（ADR 0011：三面向永遠分開），課程結束時顯示的「最終分數」
    // 是這裡自行累積三個面向的平均值，非 ScoringEngine 提供。
    private class Accumulator {
        private var sum = 0.0
        private var count = 0
        private var lastEventTimeMs: Long? = null
        private var lastFeatureTimeMs: Long? = null
        fun add(score: Score) {
            val isNewWindow = score.eventTimeMs != lastEventTimeMs || score.featureTimeMs != lastFeatureTimeMs
            if (score.isDisplayable() && isNewWindow) {
                sum += score.value
                count++
                lastEventTimeMs = score.eventTimeMs
                lastFeatureTimeMs = score.featureTimeMs
            }
        }
        val average: Int get() = if (count == 0) 0 else (sum / count).roundToInt().coerceIn(0, 100)
        val hasData: Boolean get() = count > 0
    }

    private val tempoAcc = Accumulator()
    private val trajectoryAcc = Accumulator()
    private val segmentSimilarityAcc = Accumulator()

    // FinalScoreCard 的「平均心率」：只累積播放中收到的手環樣本（見 startDeviceBridge()
    // 內 heartRateStream.collect 的 videoIsPlaying gating），跟課程平均分數同一套邏輯。
    private class Averager {
        private var sum = 0L
        private var count = 0
        fun add(bpm: Int) {
            if (bpm > 0) {
                sum += bpm
                count++
            }
        }
        val average: Int get() = if (count == 0) 0 else (sum.toDouble() / count).roundToInt()
    }

    private val heartRateAcc = Averager()

    // FinalScoreCard 的「平均體溫」：體核溫度（HealthData.coreTemperatureC），同樣只累積
    // 播放中收到的樣本；跟卡路里一樣「跟影片是否播放中無關、全程收集」的是原始 stream，這裡只在
    // 有效樣本進來時才累加。
    private class FloatAverager {
        private var sum = 0.0
        private var count = 0
        fun add(value: Float) {
            sum += value
            count++
        }
        val average: Float? get() = if (count == 0) null else (sum / count).toFloat()
    }

    private val temperatureAcc = FloatAverager()

    // ScoringEngine 的 heartRate StateFlow 初始值就是 imuConnected = false（DeviceConnectivityWatchdog
    // 把「從沒收過樣本」也視為斷線），一進入播放頁、還沒收到裝置第一筆資料前就會先發出這個狀態。
    // 這裡要是預設 true，會把「還沒連上」誤判成「連線後斷線」，一進畫面就跳出斷線警告。
    private var wasImuConnected = false
    private var lastAspectDiagnosticSignature: String? = null

    // 課程中曾拖曳進度條（seek）：Core 的參與統計以 monotonic session event time 計算，往前跳會被算成
    // 「未量測的缺口」、往回拉則讓時間停止推進，兩者都會讓量測完整度與連續活動失真。計畫 §9 的範圍
    // 明文是「開始播放 → 持續播放 → 結束或提前離開」，不含暫停／倒帶／重播；因此只要 seek 過，
    // 成果卡就不顯示活動統計並說明原因，而不是給一個算錯的數字（Codex QA 缺陷 1）。
    @Volatile private var seekedWhileScoring = false

    // 結算（StopScoring）只跑一次：ExoPlayer 的 onEvents 會連續送出多個 hasEnded = true 的
    // VideoStateChanged，使用者也可能同時按「結束評分」，而 StopScoring 是非同步的
    // （isScoring 要等協程跑到才會變 false）。自動與手動兩個入口共用這個旗標。
    @Volatile private var finalizingScoring = false

    // 進入播放頁只嘗試自動連線一次；失敗或沒有存檔裝置就交給既有的斷線提示引導使用者手動連線，
    // 不要每次 connectionState 回到 Disconnected 就再打一次（例如自動連線失敗、或使用者手動斷線）。
    private var autoConnectAttempted = false

    private val playUrlRepository = CoursePlayUrlRepository()

    init {
        resolveVideoUrl(forceReload = false)
        loadMafBeforePlayback()
        tryAutoConnectToLastDevice()

        // 三個評分面向：即時 UI 顯示目前可用面向與其平均；另累積各有效窗口供課程結束時計算平均。
        viewModelScope.launch {
            combine(engine.tempo, engine.trajectory, engine.sequence) { t, tr, s -> Triple(t, tr, s) }
                .collect { (tempo, trajectory, segmentSimilarity) ->
                    val scores = listOf(tempo, trajectory, segmentSimilarity)
                    _state.update { it.copy(scoringStatus = describeScoringStatus(scores)) }
                    tempoAcc.add(tempo)
                    trajectoryAcc.add(trajectory)
                    segmentSimilarityAcc.add(segmentSimilarity)

                    // 只平均「可顯示」的面向：AVAILABLE 且 confidence ≥ MIN_DISPLAY_CONFIDENCE。
                    // Core v1.1 起靜止時會誠實回 AVAILABLE + 低分 + confidence ≈ 0（決策 A1），
                    // 只看 availability 會把「等待動作」顯示成 0 分。沒有可顯示面向時為 null，不再退回 0。
                    val available = scores.filter { it.isDisplayable() }
                    val displayScore: Int? = available
                        .takeIf { it.isNotEmpty() }
                        ?.map { it.value }
                        ?.average()
                        ?.roundToInt()
                        ?.coerceIn(0, 100)
                    val awaitingMotion = displayScore == null && scores.any { it.availability == Availability.AVAILABLE }
                    val currentAspects = mapOf(
                        "節奏" to tempo.availableValue(),
                        "軌跡" to trajectory.availableValue(),
                        "順序" to segmentSimilarity.availableValue()
                    )
                    val diagnostics = mapOf(
                        "節奏" to tempo.diagnosticText(),
                        "軌跡" to trajectory.diagnosticText(),
                        "順序" to segmentSimilarity.diagnosticText()
                    )
                    logAspectDiagnosticsIfChanged(tempo, trajectory, segmentSimilarity)
                    applyWindowScore(displayScore, awaitingMotion, currentAspects, diagnostics)
                }
        }

        // 心率獨立管線（Stream C）：bpm 直接顯示，safety 目前只用來偵測裝置斷線（見下方 imuConnected）
        viewModelScope.launch {
            engine.heartRate.collect { heartState ->
                val bpm = heartState.bpm.takeIf(Float::isFinite)?.roundToInt()
                // 沒有有效 bpm 的 tick 先沿用上一筆（PPG 本來就約 1 Hz、整分鐘還會有健康資料停頓，
                // 每個空 tick 都清成「--」會一直閃）；但 Core 誠實回報這筆資料的年齡
                // （eventTimeMs − featureTimeMs），超過 STALE_HEART_RATE_MS 就代表手環真的斷了，
                // 這時要把心率與區間都清掉，不能讓 HUD 一直停在斷線前的讀值（Codex QA P4 第二輪缺陷 1）。
                if (bpm != null) lastHeartRateElapsedRealtimeMs = SystemClock.elapsedRealtime()
                val staleMs = heartState.eventTimeMs - heartState.featureTimeMs
                val stale = bpm == null && staleMs > STALE_HEART_RATE_MS
                _state.update {
                    it.copy(
                        heartRate = bpm ?: if (stale) 0 else it.heartRate,
                        heartRateZone = when {
                            bpm != null -> heartState.zone
                            stale -> -1
                            else -> it.heartRateZone
                        }
                    )
                }
                if (wasImuConnected && !heartState.imuConnected) {
                    _state.update { it.copy(alertMessage = "手環裝置已斷線，請重新連線") }
                }
                wasImuConnected = heartState.imuConnected
            }
        }

        // 上面的 stale 判斷只擋得住「PPG 掉、IMU 還在」：手環整支斷線時 Core 的事件時間是靠 IMU
        // 樣本推進的，不會再送任何 HeartState，收集器根本不會再跑。所以逾時另外用 App 端的單調
        // 時鐘自己算（Codex QA P4 第三輪缺陷）。只在影片播放中計時：暫停時本來就不該把心率清掉。
        viewModelScope.launch {
            while (isActive) {
                delay(HEART_RATE_STALE_CHECK_INTERVAL_MS)
                if (!videoIsPlaying) {
                    // 暫停期間不算逾時，續播時從當下重新起算，避免長暫停一回來就被清掉。
                    if (lastHeartRateElapsedRealtimeMs != 0L) {
                        lastHeartRateElapsedRealtimeMs = SystemClock.elapsedRealtime()
                    }
                    continue
                }
                val last = lastHeartRateElapsedRealtimeMs
                if (last == 0L || SystemClock.elapsedRealtime() - last <= STALE_HEART_RATE_MS) continue
                _state.update {
                    if (it.heartRate > 0 || it.heartRateZone > 0) {
                        it.copy(heartRate = 0, heartRateZone = -1)
                    } else {
                        it
                    }
                }
            }
        }

        // 心率安全告警（過高/過低），message 已是可直接顯示的中文字串
        viewModelScope.launch {
            engine.alerts.collect { alert ->
                _state.update { it.copy(alertMessage = alert.message) }
            }
        }

        // 活動參與指標（評分修復更新計畫 §9）：Core 的獨立累積器，不是分數、不與三面向合成。
        // 1 Hz（event time）更新，這裡收的是課程進行中的即時快照，供 HUD 顯示「活動 N 分」；
        // 成果卡另外用 StopScoring 裡 engine.stop() 的回傳值（已結算、含尾端斷線），見下方。
        viewModelScope.launch {
            engine.participation.collect { p ->
                _state.update {
                    // 成果卡已經建立（StopScoring 用 stop() 的結算快照填好）之後就不再寫：
                    // Core 的投影是獨立 coroutine，可能在 stop() 之後才發布結算前的舊快照，
                    // 那會讓完整度／最長連續活動短暫跳回結算前的數字（Codex QA 缺陷 3）
                    if (it.finalScore != null) return@update it
                    it.copy(
                        participationActiveMs = p.activeMs,
                        participationLongestRunMs = p.longestRunMs,
                        participationCoverage = p.coverage,
                        participationRhythmRegularity = p.rhythmRegularity,
                        participationHasData = p.expectedMs > 0L && !seekedWhileScoring,
                        participationSeeked = seekedWhileScoring
                    )
                }
            }
        }

    }

    /**
     * 進入播放頁時的自動連線兜底：正常情況下 App 一啟動（[com.johnson.fitness.FitnessApp.onCreate]）
     * 就已經嘗試連回上次的裝置，這裡只處理「當時沒連上」的情況再試一次——例如剛安裝/剛授權藍牙權限、
     * 或 App 啟動當下裝置還沒開機。只嘗試一次（[autoConnectAttempted]），沒有存檔裝置、沒有藍牙權限、
     * 或已經在連線中/已連線都直接跳過，交給既有的 connectionState 監聽（見 [startDeviceBridge]）
     * 顯示對應提示；連線失敗一樣會反映在 connectionState，沿用既有的錯誤提示即可，不特別處理。
     */
    private fun tryAutoConnectToLastDevice() {
        if (autoConnectAttempted) return
        autoConnectAttempted = true
        DeviceAutoConnect.tryConnect(viewModelScope, appContext, deviceManager, lastDevicePreferences)
    }

    /**
     * 影片真正開始播放後才註冊 DeviceModule listener。callbackFlow 的 awaitClose 會在這個 Job
     * 取消時移除 listener，避免離開播放頁後仍持續把手環資料送入已釋放的引擎。
     */
    private fun startDeviceBridge() {
        if (deviceBridgeJob?.isActive == true) return
        deviceBridgeJob = viewModelScope.launch {
            launch {
                motionAdapter.deviceImuStream.collect { data ->
                    if (!videoIsPlaying) return@collect
                    // seek 去抖動期間 engine.seek() 還沒套用，此時送樣本會讓 Core 先收到
                    // 重錨後（可能較早）的時戳；直接跳過，seek 完成後再繼續。
                    if (seekJob?.isActive == true) return@collect
                    // 錨點要綁在「這筆樣本到達當下」的影片位置，不是重錨當下的位置（QA 第一輪缺陷 1）；
                    // 並扣掉這筆樣本的送達延遲，否則用積壓樣本錨定會讓整段時間軸固定超前（第二輪缺陷 1）。
                    // 回傳 null＝這筆是暫停期間的積壓樣本，丟掉（第三輪缺陷 1）。
                    val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                    val ageMs = imuTimeline.sampleAgeMs(
                        data.timestampMs, System.currentTimeMillis(), elapsedRealtimeMs
                    )
                    val videoTimeMs = imuTimeline.videoTimeMsFor(
                        data.deviceTimestampUs,
                        estimateVideoPositionMs(elapsedRealtimeMs),
                        ageMs
                    ) ?: return@collect
                    engine.submitImuSample(data.toRawImuSample().copy(timestampMs = videoTimeMs))
                    receivedImuSampleCount++
                    if (receivedImuSampleCount == 1L || receivedImuSampleCount % 25L == 0L) {
                        // 這裡只計算已實際呼叫 submitImuSample() 的樣本，不是單純收到的 BLE 回調。
                        _state.update { it.copy(imuSampleCount = receivedImuSampleCount) }
                    }
                }
            }
            launch {
                motionAdapter.heartRateStream.collect { bpm ->
                    if (!videoIsPlaying) return@collect
                    val videoTimeMs = toVideoTimeMs(System.currentTimeMillis()) ?: return@collect
                    engine.submitHeartRateSample(bpm, videoTimeMs)
                    heartRateAcc.add(bpm)
                }
            }
            launch {
                motionAdapter.temperatureStream.collect { celsius ->
                    if (!videoIsPlaying) return@collect
                    temperatureAcc.add(celsius)
                }
            }
            launch {
                deviceManager.connectionState.collect { connection ->
                    when (connection) {
                        is ConnectionState.Connected -> {
                            // Core 的 canonical rate 是 25 Hz；裝置不支援時 DeviceModule 會自行軟體節流。
                            deviceManager.setImuSampleRate(ImuSampleRate.HZ_25)
                            // P5 之後這裡**不**重錨：時間軸由裝置端時鐘換算，DeviceModule 重連會沿用
                            // 同一條裝置時間軸，斷線期間自然成為時間軸上的缺口（正是 Core 要看到的）。
                            // 在這裡重錨反而會把斷線缺口壓平成連續資料——那正是 D2 當初要避免的問題。
                            _state.update {
                                val message = it.alertMessage.orEmpty()
                                if (message.startsWith("手環") || message.startsWith("尚未連接手環")) {
                                    it.copy(alertMessage = null, deviceStatus = "已連線")
                                } else {
                                    it.copy(deviceStatus = "已連線")
                                }
                            }
                        }
                        is ConnectionState.Connecting,
                        is ConnectionState.Reconnecting ->
                            _state.update { it.copy(alertMessage = "手環連線中，請稍候…", deviceStatus = "連線中") }
                        is ConnectionState.Disconnected ->
                            _state.update {
                                it.copy(
                                    alertMessage = "尚未連接手環，請先到藍牙設定完成連線",
                                    deviceStatus = "未連線"
                                )
                            }
                        is ConnectionState.Error ->
                            _state.update {
                                it.copy(
                                    alertMessage = "手環連線失敗：${connection.message}",
                                    deviceStatus = "連線失敗"
                                )
                            }
                    }
                }
            }
        }
    }

    /** 以播放器為主時鐘，在每列 CSV 的 timestamp_ms（影片時間）原樣送出六軸值。 */
    private fun startCsvReplay() {
        if (csvReplayJob?.isActive == true) return
        if (csvSamples.isEmpty()) return
        if (csvReplayIndex >= csvSamples.size) return
        csvReplayJob = viewModelScope.launch {
            while (csvReplayIndex < csvSamples.size) {
                if (!videoIsPlaying) {
                    delay(CSV_CLOCK_CHECK_INTERVAL_MS)
                    continue
                }
                val playerPositionMs = estimateVideoPositionMs(android.os.SystemClock.elapsedRealtime())
                val sample = csvSamples[csvReplayIndex]
                if (playerPositionMs < sample.timestampMs) {
                    delay(
                        (sample.timestampMs - playerPositionMs)
                            .coerceIn(CSV_CLOCK_CHECK_INTERVAL_MS, IMU_SAMPLE_INTERVAL_MS)
                    )
                    continue
                }

                // timestamp_ms 同時是 Replay 排程時間與 Core 使用的影片時間。
                engine.submitImuSample(sample.copy(packetId = csvReplayIndex.toLong()))
                csvReplayIndex++
                receivedImuSampleCount++
                // Replay 模式逐筆更新，讓 Seek 往回後是否重新送出可直接從畫面確認。
                _state.update { it.copy(imuSampleCount = receivedImuSampleCount) }
            }
            _state.update { it.copy(scoringStatus = "CSV 資料已播放完畢") }
        }
    }

    /** 回傳第一筆 timestamp_ms（影片時間）>= positionMs 的索引。 */
    private fun findCsvIndexAtOrAfter(positionMs: Long): Int {
        var low = 0
        var high = csvSamples.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (csvSamples[mid].timestampMs < positionMs) low = mid + 1 else high = mid
        }
        return low
    }

    private fun describeScoringStatus(scores: List<Score>): String {
        if (scores.any { it.isDisplayable() }) return "Core 評分中"
        if (scores.any { it.availability == Availability.AVAILABLE }) return "等待動作（訊號穩定、未偵測到動作）"
        if (scores.any { it.availability == Availability.WARMING_UP }) return "Core 暖機中"
        val reasons = scores.map { it.reason }.toSet()
        return when {
            ScoreReason.NOT_STARTED in reasons -> "Core 尚未啟動"
            ScoreReason.PAUSED in reasons -> "Core 已暫停"
            ScoreReason.NO_ACTIVE_SEGMENT in reasons -> "等待 MAF 評分區段"
            ScoreReason.INSUFFICIENT_COVERAGE in reasons -> "IMU 資料累積中"
            ScoreReason.PLAYBACK_RATE_ABNORMAL in reasons -> "播放速度不支援評分"
            ScoreReason.ASPECT_NOT_APPLICABLE in reasons -> "目前區段無適用面向"
            else -> "等待 Core 輸出"
        }
    }

    /**
     * 查這堂課的播放網址（HLS master playlist）。播放網址會換，所以不寫進 `courses.json`，
     * 每次進播放頁向免驗證端點查一次（同 process 內有快取）。
     *
     * 失敗時**不會**自己重試無限次：把錯誤放進 state 讓畫面給「重試／返回」，
     * 免得課程開始前就在背景一直打網路。
     */
    private fun resolveVideoUrl(forceReload: Boolean) {
        val courseId = _state.value.movie?.courseId
        if (courseId.isNullOrBlank()) {
            _state.update { it.copy(videoUrl = null, videoUrlError = "課程目錄裡找不到這堂課（courseId 為空）") }
            return
        }
        videoUrlJob?.cancel()
        // 只清「還沒開始播就查不到網址」的錯誤。播放中失敗的對話框留著等結果，
        // 一來使用者知道重試還在跑，二來清掉之後畫面會空一段時間（播放器已經 IDLE）。
        _state.update { it.copy(videoUrlError = null) }
        videoUrlJob = viewModelScope.launch {
            when (val result = playUrlRepository.resolve(courseId, forceReload = forceReload)) {
                is CoursePlayUrlRepository.Result.Success ->
                    _state.update {
                        it.copy(
                            videoUrl = result.playUrl,
                            videoUrlError = null,
                            videoPlaybackError = null,
                            // 查回同一個網址時 videoUrl 不變，靠這個計數讓畫面重新 prepare。
                            videoUrlAttempt = it.videoUrlAttempt + 1
                        )
                    }

                is CoursePlayUrlRepository.Result.Failure ->
                    _state.update {
                        if (it.videoUrl != null) {
                            // 這堂課已經有播放 session（播到一半斷網後重試）：**不要**清掉 videoUrl，
                            // 否則畫面會走 early return 把播放器連同播放位置一起釋放，
                            // 而評分引擎仍停在原本進度，恢復後時間軸就對不上了
                            //（Codex QA 第三輪缺陷 1）。保留播放器，只換對話框內容讓使用者再重試。
                            it.copy(videoPlaybackError = result.message)
                        } else {
                            it.copy(videoUrl = null, videoUrlError = result.message)
                        }
                    }
            }
        }
    }

    private fun loadMafBeforePlayback() {
        mafLoadJob?.cancel()
        // 目錄裡就標明沒有課程檔的影片（播放測試片）不是「載入失敗」，直接進「只播放、不評分」，
        // 不要跳錯誤畫面要使用者再按一次「直接看影片」（Codex QA P4 缺陷 2）。
        if (_state.value.movie?.hasScoringData == false) {
            _state.update {
                it.copy(
                    mafLoadStatus = MafLoadStatus.PLAY_WITHOUT_SCORING,
                    mafLoadError = null,
                    isScoring = false
                )
            }
            return
        }
        _state.update {
            it.copy(
                mafLoadStatus = MafLoadStatus.LOADING,
                mafLoadError = null,
                isScoring = false
            )
        }
        mafLoadJob = viewModelScope.launch {
            // 讀 assets、AES-GCM 解密與 JSON 驗證都是阻塞工作，避免在 Main thread 執行。
            val result = withContext(Dispatchers.IO) {
                engineFactory.loadMaf(engine, movieId)
            }
            _state.update {
                if (result is MafLoadResult.Success) {
                    it.copy(
                        mafLoadStatus = MafLoadStatus.READY,
                        mafLoadError = null,
                        isScoring = true
                    )
                } else {
                    it.copy(
                        mafLoadStatus = MafLoadStatus.FAILED,
                        mafLoadError = describeMafLoadFailure(result),
                        isScoring = false
                    )
                }
            }
        }
    }

    private fun describeMafLoadFailure(result: MafLoadResult?): String = when (result) {
        null -> "找不到這部影片對應的 MAF 評分檔或內容金鑰。"
        is MafLoadResult.Success -> ""
        is MafLoadResult.SchemaVersionRejected ->
            "MAF 版本不支援（${result.found}）。"
        is MafLoadResult.IntegrityFailure ->
            "MAF 完整性驗證失敗。"
        is MafLoadResult.ParseError ->
            "MAF 解密或解析失敗：${result.message}"
        is MafLoadResult.SegmentValidationFailed ->
            "MAF 內容驗證失敗，共 ${result.errors.size} 個問題。"
        is MafLoadResult.ReviewStatusRejected ->
            "MAF 尚未通過要求的人工複核狀態。"
    }

    private fun Score.availableValue(): Int? =
        takeIf { it.isDisplayable() }
            ?.value
            ?.roundToInt()
            ?.coerceIn(0, 100)


    private fun Score.diagnosticText(): String {
        val availabilityText = when (availability) {
            Availability.AVAILABLE -> "可用"
            Availability.WARMING_UP -> "暖機"
            Availability.UNAVAILABLE -> "不可用"
        }
        val coveragePercent = (validCoverage * 100f).roundToInt().coerceIn(0, 100)
        return "$availabilityText · $reason · ${coveragePercent}%"
    }

    private fun logAspectDiagnosticsIfChanged(tempo: Score, trajectory: Score, sequence: Score) {
        val namedScores = listOf("tempo" to tempo, "trajectory" to trajectory, "sequence" to sequence)
        val signature = namedScores.joinToString("|") { (_, score) ->
            "${score.availability}:${score.reason}:${(score.validCoverage * 100f).roundToInt()}"
        }
        if (signature == lastAspectDiagnosticSignature) return
        lastAspectDiagnosticSignature = signature
        namedScores.forEach { (name, score) ->
            Log.d(
                ASPECT_LOG_TAG,
                "$name availability=${score.availability} reason=${score.reason} " +
                    "coverage=${score.validCoverage} confidence=${score.confidence} " +
                    "value=${score.value} eventTimeMs=${score.eventTimeMs} featureTimeMs=${score.featureTimeMs}"
            )
        }
    }

    private fun applyWindowScore(
        displayScore: Int?,
        awaitingMotion: Boolean,
        currentAspects: Map<String, Int?>,
        diagnostics: Map<String, String>
    ) {
        val label = when {
            displayScore == null -> null
            displayScore >= 90 -> "動作完美！"
            displayScore >= 75 -> "動作標準！"
            displayScore >= 60 -> "繼續保持"
            displayScore >= 40 -> "注意節奏"
            else -> null
        }
        _state.update {
            it.copy(
                accuracy      = displayScore ?: 0,
                gameScore     = displayScore ?: 0,
                awaitingMotion = awaitingMotion,
                combo         = 1,
                currentAspectScores = currentAspects,
                currentAspectDiagnostics = diagnostics,
                feedbackLabel = label,
                feedbackDelta = null
            )
        }
        feedbackDismissJob?.cancel()
        if (label != null) {
            feedbackDismissJob = viewModelScope.launch {
                delay(2000)
                _state.update { it.copy(feedbackLabel = null, feedbackDelta = null) }
            }
        }
    }

    fun onIntent(intent: PlaybackIntent) {
        when (intent) {
            is PlaybackIntent.RetryVideoUrl -> resolveVideoUrl(forceReload = true)
            is PlaybackIntent.PlayerFailed ->
                // 播放器已經自己重試過才會走到這裡；直接告訴使用者並給重試，不要停在黑畫面。
                _state.update { it.copy(videoPlaybackError = intent.message) }
            is PlaybackIntent.PlayWithoutScoring -> {
                _state.update {
                    it.copy(
                        mafLoadStatus = MafLoadStatus.PLAY_WITHOUT_SCORING,
                        mafLoadError = null,
                        isScoring = false
                    )
                }
            }
            is PlaybackIntent.VideoStateChanged -> {
                val wasPlaying = videoIsPlaying
                videoClockAnchorPositionMs = intent.positionMs
                videoClockAnchorElapsedRealtimeMs = intent.elapsedRealtimeMs
                videoPlaybackSpeed = intent.playbackSpeed
                videoIsPlaying = intent.isPlaying
                videoPositionMs = intent.positionMs
                // 播放／暫停切換就重錨兩條 IMU 時間軸，避免暫停期間走掉的裝置時鐘灌進影片時間。
                if (wasPlaying != intent.isPlaying) reanchorImuTimelines()
                _state.update {
                    it.copy(
                        videoPositionMs = intent.positionMs,
                        videoDurationMs = intent.durationMs.takeIf { it > 0 } ?: it.videoDurationMs,
                        isPlaying = intent.isPlaying
                    )
                }

                if (intent.hasEnded && _state.value.isRecordingImu && recordingAutoStopJob?.isActive != true) {
                    recordingAutoStopJob = viewModelScope.launch {
                        delay(RECORDING_TAIL_DURATION_MS)
                        val fileName = _state.value.recordingFileName
                        val result = withContext(Dispatchers.IO) { imuCsvStore.stopRecording() }
                        result.onSuccess {
                            _state.update {
                                it.copy(
                                    isRecordingImu = false,
                                    recordingFileName = null,
                                    completedRecordingFileName = fileName,
                                    alertMessage = null
                                )
                            }
                        }.onFailure { error ->
                            _state.update { it.copy(alertMessage = "自動停止收錄失敗：${error.message}") }
                        }
                    }
                }

                // 影片播完自動結算：否則使用者跟練到最後只會停在 HUD，要自己按「結束評分」
                // 才看得到成果卡與活動參與統計（既有缺口，Codex QA D2 第二輪指出）。
                // 要求 videoTimeOffsetMs != null：MAF 一載好 isScoring 就是 true，但引擎要到影片
                // 第一次真正播放才 start()；還沒開始就收到 hasEnded 不該把整堂課結掉（第三輪缺陷 2）。
                // 互斥旗標由 StopScoring 自己設，這裡只派送 intent（第三輪缺陷 1）。
                if (intent.hasEnded && _state.value.isScoring && videoTimeOffsetMs != null) {
                    onIntent(PlaybackIntent.StopScoring)
                }

                if (_state.value.isScoring && videoTimeOffsetMs == null && intent.isPlaying) {
                    // 影片首次開始播放：建立「裝置 epoch time -> videoTimeMs」的換算基準
                    videoTimeOffsetMs = System.currentTimeMillis() - intent.positionMs
                    viewModelScope.launch {
                        engine.start(intent.positionMs)
                        when (_state.value.imuDataSource) {
                            ImuDataSource.LIVE_B20 -> startDeviceBridge()
                            ImuDataSource.CSV -> startCsvReplay()
                            ImuDataSource.NOT_SELECTED -> Unit
                        }
                    }
                } else if (_state.value.isScoring && !wasPlaying && intent.isPlaying) {
                    viewModelScope.launch {
                        engine.resume()
                        // 還有 seek 在去抖動排隊時不要自己恢復 Replay：那批樣本會搶在
                        // engine.seek() 之前送進 Core（Codex QA 第三輪缺陷 3）。
                        // 待執行的 seekJob 完成 engine.seek() 後會自己啟動 Replay。
                        if (_state.value.imuDataSource == ImuDataSource.CSV && seekJob?.isActive != true) {
                            startCsvReplay()
                        }
                    }
                } else if (_state.value.isScoring && wasPlaying && !intent.isPlaying) {
                    csvReplayJob?.cancel()
                    csvReplayJob = null
                    viewModelScope.launch { engine.pause() }
                }
            }
            is PlaybackIntent.VideoClockTick -> {
                val estimatedPositionMs = estimateVideoPositionMs(intent.elapsedRealtimeMs)
                videoPositionMs = estimatedPositionMs
                _state.update { state ->
                    state.copy(
                        videoPositionMs = estimatedPositionMs.coerceAtMost(
                            state.videoDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                        )
                    )
                }
            }
            is PlaybackIntent.DismissAlert -> {
                _state.update { it.copy(alertMessage = null) }
            }
            is PlaybackIntent.StopScoring -> {
                // 自動（影片播完）與手動（按「結束評分」）共用同一個互斥旗標：engine.stop() 是
                // suspend，isScoring 要等協程跑到才變 false，只靠它擋不住兩個入口同時進來——
                // 第二次 stop() 會拿到空的心率結算摘要，把成果卡的參與時間與熱量蓋成 0
                // （Codex QA D2 第三輪缺陷 1）。
                if (finalizingScoring || !_state.value.isScoring) return
                finalizingScoring = true
                viewModelScope.launch {
                    if (_state.value.isScoring) {
                        seekJob?.cancel()
                        seekJob = null
                        deviceBridgeJob?.cancel()
                        deviceBridgeJob = null
                        csvReplayJob?.cancel()
                        csvReplayJob = null
                        // Core 的 stop() 會等參與統計結算完才返回，回傳值就是成果卡該用的最終快照；
                        // 傳入影片位置讓 Core 把「最後一筆樣本到結束之間」的斷線算成未量測（§9.1）。
                        // seek 過的課程不把影片位置當 stop 時刻：影片位置與 session event time 已經對不起來，
                        // 傳進去只會憑空多出一段「未量測」。這種課程的參與統計本來就不顯示。
                        val participation =
                            if (seekedWhileScoring) engine.stop() else engine.stop(videoTimeMs = videoPositionMs)
                        val exerciseSession = engine.exerciseSession.value
                        // 整堂課都沒有可顯示分數（例如全程靜止、Core 只回低 confidence）時不折成 0 分／D 級，
                        // 成果卡改顯示「無有效評分」（決策 A1）。
                        val accs = listOf(tempoAcc, trajectoryAcc, segmentSimilarityAcc).filter { it.hasData }
                        val hasValidScore = accs.isNotEmpty()
                        val finalScore = if (hasValidScore) accs.sumOf { it.average } / accs.size else 0
                        _state.update {
                            it.copy(
                                isScoring    = false,
                                finalScore   = finalScore,
                                grade        = if (hasValidScore) gradeLabel(finalScore) else "－",
                                finalNoValidScore = !hasValidScore,
                                // 順序（片段相似度）面向依決策 A3 延後，成果卡不顯示；仍納入總平均
                                aspectScores = buildMap {
                                    if (tempoAcc.hasData) put("節奏", tempoAcc.average)
                                    if (trajectoryAcc.hasData) put("軌跡", trajectoryAcc.average)
                                },
                                exerciseDurationMs = exerciseSession.durationMs,
                                avgHeartRate = heartRateAcc.average,
                                caloriesBurned = exerciseSession.caloriesKcal.roundToInt(),
                                avgBodyTemperatureC = temperatureAcc.average,
                                // 活動參與指標：用 stop() 的結算快照，不用即時 collector 的最後一份
                                participationActiveMs = participation.activeMs,
                                participationLongestRunMs = participation.longestRunMs,
                                participationCoverage = participation.coverage,
                                participationRhythmRegularity = participation.rhythmRegularity,
                                participationHasData = participation.expectedMs > 0L && !seekedWhileScoring,
                                participationSeeked = seekedWhileScoring,
                                // 生理參與（§13）：與熱量／參與時間同一個 HeartRateSessionAnalyzer 時基，
                                // 但本身不受 seek 影響的結論交給 Player 顯示層處理（同樣以 participationSeeked
                                // 為準，理由與熱量欄一致：seek 過的 session 是以單調時間結算，跳過去的時間
                                // 也會被計入）。
                                physioParticipationAvailable = exerciseSession.physioParticipation.available,
                                physioHrAboveRestMs = exerciseSession.physioParticipation.hrAboveRestMs,
                                physioHrAboveRestRatio = exerciseSession.physioParticipation.hrAboveRestRatio
                            )
                        }
                    }
                    // onFinalScore 舊版是引擎回調驅動；新版由這裡直接組裝，自動顯示成果卡
                }
            }
            is PlaybackIntent.BackPressed -> {
                viewModelScope.launch {
                    seekJob?.cancel()
                    recordingAutoStopJob?.cancel()
                    withContext(Dispatchers.IO) { imuCsvStore.stopRecording() }
                    _effect.send(PlaybackEffect.NavigateBack)
                }
            }
            is PlaybackIntent.Seek -> {
                // 只有「評分 session 真的開始過」才算中途 seek：`isScoring` 在 MAF 載入完成就是 true，
                // 但引擎要到影片第一次真正播放才 start()（那時才會有 videoTimeOffsetMs）。
                // 播放前先拖到想開始的位置不是中途跳時基，不該讓整堂課的統計被隱藏（Codex QA 缺陷 P4）。
                if (_state.value.isScoring && videoTimeOffsetMs != null && !seekedWhileScoring) {
                    seekedWhileScoring = true
                    // 立刻反映到畫面：Core 用單調 event time，倒帶後可能要好幾分鐘才發下一份參與快照，
                    // 期間 HUD 會一直顯示已經決定要隱藏的舊數字（Codex QA 缺陷 P5）
                    _state.update { it.copy(participationHasData = false, participationSeeked = true) }
                }
                videoPositionMs = intent.positionMs
                videoClockAnchorPositionMs = intent.positionMs
                videoClockAnchorElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                reanchorImuTimelinesAfterSeek()
                if (_state.value.imuDataSource == ImuDataSource.CSV) {
                    csvReplayJob?.cancel()
                    csvReplayJob = null
                    csvReplayIndex = findCsvIndexAtOrAfter(intent.positionMs)
                }
                _state.update { it.copy(videoPositionMs = intent.positionMs) }
                // 只有已經建立過換算基準（影片已開始播放過）才需要重新校正；
                // 還沒開始播放就不會有這個 offset，維持 null 讓它在真正開始播放時正常建立。
                videoTimeOffsetMs?.let {
                    videoTimeOffsetMs = System.currentTimeMillis() - intent.positionMs
                    if (_state.value.isScoring) {
                        // 進度條拖曳會密集送出 Seek；只處理使用者停止移動後的最後位置，
                        // 避免數十個 engine.seek() 排隊讓 CSV Replay 長時間無法恢復。
                        seekJob?.cancel()
                        seekJob = viewModelScope.launch {
                            delay(SEEK_DEBOUNCE_MS)
                            engine.seek(intent.positionMs)
                            if (_state.value.imuDataSource == ImuDataSource.CSV && videoIsPlaying) {
                                startCsvReplay()
                            }
                        }
                    }
                }
            }
            is PlaybackIntent.UseLiveB20 -> {
                csvSamples = emptyList()
                csvReplayIndex = 0
                _state.update {
                    it.copy(
                        imuDataSource = ImuDataSource.LIVE_B20,
                        selectedCsvName = null,
                        csvSampleCount = 0,
                        isPlaying = false
                    )
                }
                if (intent.recordCsv) onIntent(PlaybackIntent.StartImuRecording)
            }
            is PlaybackIntent.CsvSelected -> {
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { imuCsvStore.read(intent.uri) } }
                    result.onSuccess { samples ->
                        csvSamples = samples
                        csvReplayIndex = 0
                        _state.update {
                            it.copy(
                                imuDataSource = ImuDataSource.CSV,
                                selectedCsvName = intent.displayName ?: intent.uri.lastPathSegment,
                                csvSampleCount = samples.size,
                                isPlaying = false,
                                alertMessage = null,
                                deviceStatus = "CSV 25 Hz"
                            )
                        }
                    }.onFailure { error ->
                        _state.update { it.copy(alertMessage = "CSV 讀取失敗：${error.message}") }
                    }
                }
            }
            is PlaybackIntent.StartImuRecording -> {
                viewModelScope.launch {
                    // 檔名要帶課程編號（見 ImuCsvFileNaming），拿不到 courseId 時退回 "unknown"，
                    // 不讓整次收錄直接失敗。
                    val courseId = _state.value.movie?.courseId?.takeIf { it.isNotBlank() } ?: "unknown"
                    val result = withContext(Dispatchers.IO) { imuCsvStore.startRecording(courseId) }
                    result.onSuccess { fileName ->
                        _state.update {
                            it.copy(
                                isRecordingImu = true,
                                recordingFileName = fileName,
                                alertMessage = null
                            )
                        }
                        _effect.send(PlaybackEffect.ShowToast("已開始收錄"))
                    }.onFailure { error ->
                        _state.update { it.copy(alertMessage = "無法開始收錄：${error.message}") }
                    }
                }
            }
            is PlaybackIntent.StopImuRecording -> {
                viewModelScope.launch {
                    val fileName = _state.value.recordingFileName
                    val result = withContext(Dispatchers.IO) { imuCsvStore.stopRecording() }
                    result.onSuccess {
                        _state.update {
                            it.copy(
                                isRecordingImu = false,
                                recordingFileName = null,
                                alertMessage = fileName?.let { name -> "收錄完成：$name" }
                            )
                        }
                    }.onFailure { error ->
                        _state.update { it.copy(alertMessage = "停止收錄失敗：${error.message}") }
                    }
                }
            }
            is PlaybackIntent.DismissRecordingComplete -> {
                _state.update { it.copy(completedRecordingFileName = null) }
            }
        }
    }

    // 跟 PlaybackScreen 的 FinalScoreCard 配色門檻（gradeColor()）保持一致：90/75/60/45
    private fun gradeLabel(score: Int): String = when {
        score >= 90 -> "S"
        score >= 75 -> "A"
        score >= 60 -> "B"
        score >= 45 -> "C"
        else        -> "D"
    }

    override fun onCleared() {
        super.onCleared()
        deviceBridgeJob?.cancel()
        csvReplayJob?.cancel()
        seekJob?.cancel()
        recordingAutoStopJob?.cancel()
        imuCsvStore.stopRecording()
        engine.release()
    }

    private companion object {
        const val IMU_SAMPLE_INTERVAL_MS = 40L
        const val CSV_CLOCK_CHECK_INTERVAL_MS = 5L
        const val RECORDING_TAIL_DURATION_MS = 3_000L
        const val SEEK_DEBOUNCE_MS = 120L
        // 心率讀值最多沿用這麼久；超過就當作手環斷了，HUD 顯示「--」而不是斷線前的舊值。
        // 取 15 s 是為了蓋過 B20 每整分鐘的健康資料停頓與 PPG 的 1 Hz 更新間隔。
        const val STALE_HEART_RATE_MS = 15_000L
        const val HEART_RATE_STALE_CHECK_INTERVAL_MS = 1_000L
        const val ASPECT_LOG_TAG = "ScoringAspect"
    }
}
