package com.johnson.fitness.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitness.device.api.IDeviceManager
import com.fitness.activityscoringcore.api.Availability
import com.fitness.activityscoringcore.api.Score
import com.fitness.activityscoringcore.engine.ScoringEngine
import com.motionmaf.format.MafLoadResult
import com.johnson.fitness.data.MovieRepository
import com.johnson.fitness.data.ScoringEngineFactory
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class PlaybackViewModel(
    val movieId: Long,
    engineFactory: ScoringEngineFactory,
    val deviceManager: IDeviceManager
) : ViewModel() {

    private val _state = MutableStateFlow(PlaybackState(movie = MovieRepository.getMovieById(movieId)))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _effect = Channel<PlaybackEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private val engine: ScoringEngine = engineFactory.create()
    private val motionAdapter = MotionDataAdapter(deviceManager)

    @Volatile private var videoPositionMs = 0L
    @Volatile private var videoIsPlaying = false

    // IMU/心率樣本的時間戳是裝置端 wall clock（epoch ms），但 ScoringEngine 的 videoTimeMs
    // 是「影片播放位置」時基（ADR 0018：每筆資料自己推進 videoTimeMs，不是外部 pull）。
    // 影片第一次開始播放時記錄兩個時鐘的差值，之後每筆樣本都靠這個 offset 換算。
    @Volatile private var videoTimeOffsetMs: Long? = null

    private fun toVideoTimeMs(deviceEpochMs: Long): Long? = videoTimeOffsetMs?.let { deviceEpochMs - it }

    private var feedbackDismissJob: Job? = null

    // ActivityScoringCore 已不提供聚合總分（ADR 0011：三面向永遠分開），課程結束時顯示的「最終分數」
    // 是這裡自行累積三個面向的平均值，非 ScoringEngine 提供。
    private class Accumulator {
        private var sum = 0.0
        private var count = 0
        fun add(score: Score) {
            if (score.availability == Availability.AVAILABLE) {
                sum += score.value
                count++
            }
        }
        val average: Int get() = if (count == 0) 0 else ((sum / count) * 100).toInt().coerceIn(0, 100)
        val hasData: Boolean get() = count > 0
    }

    private val tempoAcc = Accumulator()
    private val trajectoryAcc = Accumulator()
    private val segmentSimilarityAcc = Accumulator()

    // ScoringEngine 的 heartRate StateFlow 初始值就是 imuConnected = false（DeviceConnectivityWatchdog
    // 把「從沒收過樣本」也視為斷線），一進入播放頁、還沒收到裝置第一筆資料前就會先發出這個狀態。
    // 這裡要是預設 true，會把「還沒連上」誤判成「連線後斷線」，一進畫面就跳出斷線警告。
    private var wasImuConnected = false

    init {
        viewModelScope.launch {
            // 讀 assets + 解析 JSON 都是阻塞工作，丟到 IO dispatcher，避免卡住 Main
            val loadResult = withContext(Dispatchers.IO) {
                engineFactory.loadMaf(engine, movieId)
            }
            // 沒有 .maf 素材，或載入失敗（見 MafLoadResult 各失敗分支）：僅播放影片、不進入評分模式，
            // 跟舊版 loadReferenceData() 回傳 false 時的降級行為一致。
            _state.update { it.copy(isScoring = loadResult is MafLoadResult.Success) }
        }

        // 三個評分面向：即時 UI 反饋（累積成 accuracy/gameScore/combo）+ 課程平均（累積成最終分數）
        viewModelScope.launch {
            combine(engine.tempo, engine.trajectory, engine.sequence) { t, tr, s -> Triple(t, tr, s) }
                .collect { (tempo, trajectory, segmentSimilarity) ->
                    tempoAcc.add(tempo)
                    trajectoryAcc.add(trajectory)
                    segmentSimilarityAcc.add(segmentSimilarity)

                    val available = listOf(tempo, trajectory, segmentSimilarity)
                        .filter { it.availability == Availability.AVAILABLE }
                    if (available.isNotEmpty()) {
                        val displayScore = (available.map { it.value }.average() * 100).toInt().coerceIn(0, 100)
                        applyWindowScore(displayScore)
                    }
                }
        }

        // 心率獨立管線（Stream C）：bpm 直接顯示，safety 目前只用來偵測裝置斷線（見下方 imuConnected）
        viewModelScope.launch {
            engine.heartRate.collect { heartState ->
                _state.update {
                    it.copy(heartRate = heartState.bpm.takeIf(Float::isFinite)?.roundToInt() ?: it.heartRate)
                }
                if (wasImuConnected && !heartState.imuConnected) {
                    _state.update { it.copy(alertMessage = "手環裝置已斷線，請重新連線") }
                }
                wasImuConnected = heartState.imuConnected
            }
        }

        // 心率安全告警（過高/過低），message 已是可直接顯示的中文字串
        viewModelScope.launch {
            engine.alerts.collect { alert ->
                _state.update { it.copy(alertMessage = alert.message) }
            }
        }

        // IMU 樣本：換算到影片時間軸後才餵給引擎，見 videoTimeOffsetMs 說明
        viewModelScope.launch {
            motionAdapter.imuStream.collect { raw ->
                val videoTimeMs = toVideoTimeMs(raw.timestampMs) ?: return@collect
                engine.submitImuSample(raw.copy(timestampMs = videoTimeMs))
            }
        }

        // 心率樣本：HealthData 沒有隨流傳遞裝置時間戳，用收到當下的 wall clock 近似即可
        viewModelScope.launch {
            motionAdapter.heartRateStream.collect { bpm ->
                val videoTimeMs = toVideoTimeMs(System.currentTimeMillis()) ?: return@collect
                engine.submitHeartRateSample(bpm, videoTimeMs)
            }
        }
    }

    private fun applyWindowScore(displayScore: Int) {
        val prevCombo = _state.value.combo
        val newCombo = if (displayScore >= 70) (prevCombo + 1).coerceAtMost(8) else 1
        val delta = (displayScore / 10) * newCombo
        val label = when {
            displayScore >= 90 -> "動作完美！"
            displayScore >= 75 -> "動作標準！"
            displayScore >= 60 -> "繼續保持"
            displayScore >= 40 -> "注意節奏"
            else -> null
        }
        _state.update {
            it.copy(
                accuracy      = displayScore,
                gameScore     = it.gameScore + delta,
                combo         = newCombo,
                feedbackLabel = label,
                feedbackDelta = if (label != null) "+$delta" else null
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
            is PlaybackIntent.VideoStateChanged -> {
                val wasPlaying = videoIsPlaying
                videoPositionMs = intent.positionMs
                videoIsPlaying = intent.isPlaying
                _state.update {
                    it.copy(
                        videoPositionMs = intent.positionMs,
                        videoDurationMs = intent.durationMs.takeIf { it > 0 } ?: it.videoDurationMs,
                        isPlaying = intent.isPlaying
                    )
                }

                if (videoTimeOffsetMs == null && intent.isPlaying) {
                    // 影片首次開始播放：建立「裝置 epoch time -> videoTimeMs」的換算基準
                    videoTimeOffsetMs = System.currentTimeMillis() - intent.positionMs
                    viewModelScope.launch { engine.start(intent.positionMs) }
                } else if (!wasPlaying && intent.isPlaying) {
                    viewModelScope.launch { engine.resume() }
                } else if (wasPlaying && !intent.isPlaying) {
                    viewModelScope.launch { engine.pause() }
                }
            }
            is PlaybackIntent.DismissAlert -> {
                _state.update { it.copy(alertMessage = null) }
            }
            is PlaybackIntent.StopScoring -> {
                viewModelScope.launch {
                    if (_state.value.isScoring) {
                        engine.stop()
                        val finalScore = listOf(tempoAcc, trajectoryAcc, segmentSimilarityAcc)
                            .filter { it.hasData }
                            .let { accs -> if (accs.isEmpty()) 0 else accs.sumOf { it.average } / accs.size }
                        _state.update {
                            it.copy(
                                isScoring    = false,
                                finalScore   = finalScore,
                                grade        = gradeLabel(finalScore),
                                aspectScores = buildMap {
                                    if (tempoAcc.hasData) put("節奏", tempoAcc.average)
                                    if (trajectoryAcc.hasData) put("軌跡", trajectoryAcc.average)
                                    if (segmentSimilarityAcc.hasData) put("片段相似度", segmentSimilarityAcc.average)
                                }
                            )
                        }
                    }
                    // onFinalScore 舊版是引擎回調驅動；新版由這裡直接組裝，自動顯示成果卡
                }
            }
            is PlaybackIntent.BackPressed -> {
                viewModelScope.launch { _effect.send(PlaybackEffect.NavigateBack) }
            }
            is PlaybackIntent.Seek -> {
                videoPositionMs = intent.positionMs
                _state.update { it.copy(videoPositionMs = intent.positionMs) }
                // 只有已經建立過換算基準（影片已開始播放過）才需要重新校正；
                // 還沒開始播放就不會有這個 offset，維持 null 讓它在真正開始播放時正常建立。
                videoTimeOffsetMs?.let {
                    videoTimeOffsetMs = System.currentTimeMillis() - intent.positionMs
                }
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
        engine.release()
    }
}
