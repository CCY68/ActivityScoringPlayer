@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.johnson.fitness.ui.playback

import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.johnson.fitness.FitnessApp
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.common.isCompactWidth
import com.johnson.fitness.ui.common.touchClickable
import com.johnson.fitness.ui.theme.JohnsonColors
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.delay
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

// ─── Entry ────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(
    movieId: Long,
    launchConfig: PlaybackLaunchConfig,
    onBack: () -> Unit,
    viewModel: PlaybackViewModel = viewModel(key = "playback_${movieId}_${launchConfig.hashCode()}") {
        val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as FitnessApp
        PlaybackViewModel(movieId, app.scoringEngineFactory, app.deviceManager, app, app.lastDevicePreferences)
    }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, launchConfig) {
        when (launchConfig) {
            is PlaybackLaunchConfig.LiveB20 ->
                viewModel.onIntent(PlaybackIntent.UseLiveB20(launchConfig.recordCsv))
            is PlaybackLaunchConfig.ReplayCsv ->
                viewModel.onIntent(PlaybackIntent.CsvSelected(launchConfig.uri, launchConfig.displayName))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is PlaybackEffect.NavigateBack -> onBack()
                is PlaybackEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    when (state.mafLoadStatus) {
        MafLoadStatus.LOADING -> {
            MafLoadingScreen()
            return
        }
        MafLoadStatus.FAILED -> {
            MafLoadFailureScreen(
                message = state.mafLoadError.orEmpty(),
                onCancel = { viewModel.onIntent(PlaybackIntent.BackPressed) },
                onPlayWithoutScoring = { viewModel.onIntent(PlaybackIntent.PlayWithoutScoring) }
            )
            return
        }
        MafLoadStatus.READY,
        MafLoadStatus.PLAY_WITHOUT_SCORING -> Unit
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }

    LaunchedEffect(state.movie) {
        state.movie?.videoUrl?.let { url ->
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            exoPlayer.prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            // PlayerView 內建控制器（進度條、D-pad 快轉／倒轉）會直接呼叫播放器 seek，不會經過
            // 下面自訂進度條的 PlaybackIntent.Seek；只靠 onEvents 的 VideoStateChanged 無法辨識
            // 那是一次 seek，IMU 時間軸不會重錨、Core 也收不到 engine.seek()（Codex QA 缺陷 7）。
            // 這裡統一由播放器的位置不連續事件補送 Seek；與自訂進度條重複送出是無害的
            // （重錨冪等、engine.seek() 有去抖動、CSV Replay 索引重算結果相同）。
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    viewModel.onIntent(PlaybackIntent.Seek(newPosition.positionMs))
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                viewModel.onIntent(
                    PlaybackIntent.VideoStateChanged(
                        positionMs = player.currentPosition,
                        durationMs = player.duration.coerceAtLeast(0L),
                        isPlaying = player.isPlaying,
                        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        playbackSpeed = player.playbackParameters.speed,
                        hasEnded = player.playbackState == Player.STATE_ENDED
                    )
                )
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // UI 進度只推進單調時鐘；播放器位置的同步基準由上方 Player.Listener 事件提供。
    LaunchedEffect(exoPlayer) {
        while (true) {
            viewModel.onIntent(
                PlaybackIntent.VideoClockTick(SystemClock.elapsedRealtime())
            )
            delay(100)
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    // ── Root ──────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.Ink1000)
    ) {

        // 1. Full-bleed video
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer } },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Right-side dark gradient → keeps right panel readable
        // 400dp 是照 HUD 面板（284dp）+ 邊界留白抓的 TV 尺度，手機窄螢幕下這個寬度
        // 可能就等於整個畫面寬，縮小成跟 HUD 面板同一套響應式邏輯搭配。
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.30f)
                .widthIn(min = 120.dp, max = 220.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, JohnsonColors.Ink1000.copy(alpha = 0.92f))
                    )
                )
        )

        // 3. Bottom scrim
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(96.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, JohnsonColors.Ink1000.copy(alpha = 0.7f))
                    )
                )
        )

        val horizontalPadding = if (isCompactWidth()) 20.dp else 40.dp

        // 4. Top bar: LIVE + COACH badge / Title (left) + Timer + X (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiveBadge()
                    val category = state.movie?.category.orEmpty()
                    if (category.isNotBlank()) CategoryBadge(category)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.movie?.title ?: "",
                    color = JohnsonColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = state.videoPositionMs.toTimeString(),
                    color = JohnsonColors.Gray0,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 自動收錄模式只能由播放前選擇啟用，結束點固定為影片播完後 3 秒。
        if (state.isRecordingImu) {
            Text(
                text = "● CSV 收錄中",
                color = JohnsonColors.Brand,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = horizontalPadding)
            )
        }

        // 5. Feedback toast (center-left, 2 s 後自動消失)
        AnimatedVisibility(
            visible = state.feedbackLabel != null,
            enter = fadeIn() + slideInVertically { -32 },
            exit  = fadeOut() + slideOutVertically { -32 },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = horizontalPadding, bottom = 60.dp)
        ) {
            state.feedbackLabel?.let { label ->
                FeedbackToast(label = label, delta = state.feedbackDelta ?: "")
            }
        }

        // 6. Right HUD panel (3 cards, visible while scoring)
        if (state.isScoring) {
            // HUD 限制在畫面寬度約四分之一，避免遮住教練動作主體。
            // 三張卡（ScoreCard/HeartRateCard/AccuracyCard，含「結束評分」按鈕）疊起來的高度是照 TV
            // ≈720dp 高的螢幕抓的；手機（尤其橫向播放時）可用高度常常不到一半，超出的部分會被父層
            // Box 直接裁掉，導致最下面 AccuracyCard 裡的「結束評分」按鈕整個看不到也點不到。這裡限制
            // 面板最高只到「螢幕高度 - 底部播放列預留高度」，超出就用 verticalScroll 讓使用者滑得到。
            val bottomBarReservedHeight = 140.dp
            val hudMaxHeight = LocalConfiguration.current.screenHeightDp.dp - bottomBarReservedHeight
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = if (isCompactWidth()) 10.dp else 20.dp)
                    .fillMaxWidth(0.24f)
                    .widthIn(min = 120.dp, max = 180.dp)
                    .heightIn(max = hudMaxHeight.coerceAtLeast(0.dp))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScoreCard(
                    gameScore = state.gameScore,
                    awaitingMotion = state.awaitingMotion,
                    aspectScores = state.currentAspectScores,
                    aspectDiagnostics = state.currentAspectDiagnostics,
                    deviceStatus = state.deviceStatus,
                    imuSampleCount = state.imuSampleCount,
                    scoringStatus = state.scoringStatus
                )
                HeartRateCard(heartRate = state.heartRate, zone = state.heartRateZone)
                AccuracyCard(
                    accuracy = state.accuracy,
                    awaitingMotion = state.awaitingMotion,
                    aspectScores = state.currentAspectScores,
                    activeMs = state.participationActiveMs,
                    // 太極（showActivityStats = false）連 HUD 也不顯示：隱藏的理由是慢動作會被低估，
                    // 這個限制在播放中同樣成立，播放時給低估數字、結束後又拿掉會更難理解。
                    // 註：HUD 的即時三面向分數對太極**仍會顯示**（成果卡則不顯示）。這是刻意保留——
                    // 顯示層已經尊重 confidence（PR-P1），太極訊號不足時本來就會顯示「等待動作」而不是分數；
                    // 若連 HUD 分數也拿掉，整堂太極就完全沒有即時回饋。是否統一由使用者決定（見 DONE_Player.md）。
                    showActiveTime = state.participationHasData && state.showActivityStats,
                    onStop   = { viewModel.onIntent(PlaybackIntent.StopScoring) }
                )
            }
        }

        // 7. Bottom bar: pause icon + exercise name + progress
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayPauseButton(
                    isPlaying = state.isPlaying,
                    onToggle = { exoPlayer.playWhenReady = !exoPlayer.playWhenReady }
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "目前動作",
                        color = JohnsonColors.TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = state.movie?.description?.takeIf { it.isNotBlank() }
                            ?: state.movie?.title ?: "",
                        color = JohnsonColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Thin progress bar（可拖曳 seek）
            VideoProgressBar(
                positionMs = state.videoPositionMs,
                durationMs = state.videoDurationMs,
                onSeek = { newPositionMs ->
                    exoPlayer.seekTo(newPositionMs)
                    viewModel.onIntent(PlaybackIntent.Seek(newPositionMs))
                }
            )
        }

        // 8. Alert banner
        AnimatedVisibility(
            visible = state.alertMessage != null,
            enter = fadeIn(),
            exit  = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
        ) {
            state.alertMessage?.let { msg ->
                AlertBanner(
                    message = msg,
                    onDismiss = { viewModel.onIntent(PlaybackIntent.DismissAlert) }
                )
            }
        }

        // 9. Final score card
        AnimatedVisibility(
            visible = state.finalScore != null,
            enter = fadeIn(),
            exit  = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            state.finalScore?.let { score ->
                FinalScoreCard(
                    score          = score,
                    grade          = state.grade,
                    noValidScore   = state.finalNoValidScore,
                    aspectScores   = state.aspectScores,
                    durationMs     = state.exerciseDurationMs,
                    caloriesBurned = state.caloriesBurned,
                    avgHeartRate   = state.avgHeartRate,
                    avgBodyTemperatureC = state.avgBodyTemperatureC,
                    activeMs       = state.participationActiveMs,
                    longestRunMs   = state.participationLongestRunMs,
                    coverage       = state.participationCoverage,
                    rhythmRegularity = state.participationRhythmRegularity,
                    participationHasData = state.participationHasData,
                    participationSeeked = state.participationSeeked,
                    showActivityStats = state.showActivityStats,
                    onBack         = { viewModel.onIntent(PlaybackIntent.BackPressed) }
                )
            }
        }

        state.completedRecordingFileName?.let { fileName ->
            RecordingCompleteDialog(
                fileName = fileName,
                onConfirm = { viewModel.onIntent(PlaybackIntent.DismissRecordingComplete) }
            )
        }
    }
}

@Composable
private fun RecordingCompleteDialog(fileName: String, onConfirm: () -> Unit) {
    MaterialAlertDialog(
        onDismissRequest = {},
        title = { Text("收錄完成", color = JohnsonColors.Gray0) },
        text = {
            Text(
                "B20 IMU 已完成收錄：\n$fileName",
                color = JohnsonColors.Gray100
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.touchClickable(onClick = onConfirm),
                colors = ButtonDefaults.colors(
                    containerColor = JohnsonColors.Red500,
                    contentColor = JohnsonColors.Gray0,
                    focusedContainerColor = JohnsonColors.Red400,
                    focusedContentColor = JohnsonColors.Gray0
                )
            ) {
                Text("確定", color = JohnsonColors.Gray0)
            }
        },
        containerColor = JohnsonColors.Ink600,
        titleContentColor = JohnsonColors.Gray0,
        textContentColor = JohnsonColors.Gray100
    )
}

@Composable
private fun MafLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.Ink1000),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CircularProgressIndicator(color = JohnsonColors.Brand)
            Text(
                text = "正在載入評分資料…",
                color = JohnsonColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MafLoadFailureScreen(
    message: String,
    onCancel: () -> Unit,
    onPlayWithoutScoring: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.Ink1000)
    ) {
        MaterialAlertDialog(
            onDismissRequest = {},
            title = { Text("評分資料載入失敗") },
            text = {
                Text(
                    text = message.ifBlank { "無法載入這部影片的評分資料。" },
                    color = JohnsonColors.TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.touchClickable(onClick = onCancel)
                ) {
                    Text("取消觀看影片")
                }
            },
            dismissButton = {
                Button(
                    onClick = onPlayWithoutScoring,
                    modifier = Modifier.touchClickable(onClick = onPlayWithoutScoring)
                ) {
                    Text("直接看影片（不評分）")
                }
            },
            containerColor = JohnsonColors.Ink800,
            titleContentColor = JohnsonColors.TextPrimary,
            textContentColor = JohnsonColors.TextSecondary
        )
    }
}

// ─── Top bar components ───────────────────────────────────────────────────────

@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier
            .background(JohnsonColors.Ink700, RoundedCornerShape(999.dp))
            .border(1.dp, JohnsonColors.BorderDefault, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(JohnsonColors.Brand, CircleShape)
        )
        Text(
            text = "LIVE",
            color = JohnsonColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

/** 顯示課程分類（原本掛的是示範用的教練名，見 MovieRepository 的 PR-P4 清理）。 */
@Composable
private fun CategoryBadge(name: String) {
    Row(
        modifier = Modifier
            .background(JohnsonColors.Ink700, RoundedCornerShape(999.dp))
            .border(1.dp, JohnsonColors.BorderDefault, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(JohnsonColors.Gray300, CircleShape)
        )
        Text(
            text = name,
            color = JohnsonColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape = CircleShape),
        modifier = Modifier
            .size(40.dp)
            .touchClickable(onClick = onClick),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Text("✕", color = JohnsonColors.TextPrimary, fontSize = 15.sp)
    }
}

// ─── Feedback toast ───────────────────────────────────────────────────────────

@Composable
private fun FeedbackToast(label: String, delta: String) {
    Column {
        Text(
            text = label,
            color = JohnsonColors.AccentScore,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black
        )
        if (delta.isNotEmpty()) {
            Text(
                text = delta,
                color = JohnsonColors.Lime300,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ─── Right HUD cards ──────────────────────────────────────────────────────────

@Composable
private fun HudCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JohnsonColors.SurfaceCard)
            .border(1.dp, JohnsonColors.BorderDefault, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        content = content
    )
}

@Composable
private fun ScoreCard(
    gameScore: Int,
    awaitingMotion: Boolean = false,
    aspectScores: Map<String, Int?>,
    aspectDiagnostics: Map<String, String>,
    deviceStatus: String,
    imuSampleCount: Long,
    scoringStatus: String
) {
    HudCard {
        Text(
            text = "分數  SCORE",
            color = JohnsonColors.TextTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (awaitingMotion) "等待動作" else gameScore.toString(),
            color = JohnsonColors.AccentScore,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp
        )
        AspectScoresRow(aspectScores)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "手環 $deviceStatus · IMU $imuSampleCount",
            color = if (imuSampleCount > 0) JohnsonColors.Lime300 else JohnsonColors.TextTertiary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = scoringStatus,
            color = JohnsonColors.TextSecondary,
            fontSize = 9.sp,
            lineHeight = 11.sp
        )
        Spacer(Modifier.height(5.dp))
        listOf("節奏", "軌跡").forEach { name ->   // 順序面向依決策 A3 延後，不顯示
            Text(
                text = "$name ${aspectDiagnostics[name] ?: "－"}",
                color = if (aspectScores[name] == null) JohnsonColors.TextTertiary else JohnsonColors.Lime300,
                fontSize = 8.sp,
                lineHeight = 10.sp
            )
        }
    }
}

@Composable
private fun HeartRateCard(heartRate: Int, zone: Int) {
    // 區間一律用 Core 算好的（它才知道使用者的年齡與靜息心率），畫面不自己算門檻。
    val effectiveZone = if (heartRate > 0 && zone in 1..5) zone else 0
    val zColor = zoneColor(effectiveZone)
    val zName  = zoneName(effectiveZone)

    HudCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("♥", color = JohnsonColors.Brand, fontSize = 17.sp, lineHeight = 32.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (heartRate > 0) heartRate.toString() else "--",
                color = JohnsonColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 32.sp
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "BPM",
                color = JohnsonColors.TextTertiary,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (effectiveZone > 0) "ZONE $effectiveZone · $zName" else "-- --",
            color = if (effectiveZone > 0) zColor else JohnsonColors.TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(7.dp))
        HrZoneBars(activeZone = effectiveZone)
    }
}

@Composable
private fun HrZoneBars(activeZone: Int) {
    val zones = listOf(
        JohnsonColors.HrZ1 to "Z1",
        JohnsonColors.HrZ2 to "Z2",
        JohnsonColors.HrZ3 to "Z3",
        JohnsonColors.HrZ4 to "Z4",
        JohnsonColors.HrZ5 to "Z5"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        zones.forEachIndexed { idx, (color, label) ->
            val isActive = (idx + 1) == activeZone
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .width(21.dp)
                        .height(if (isActive) 6.dp else 4.dp)
                        .background(
                            color = if (isActive) color else color.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    color = if (isActive) color else JohnsonColors.TextTertiary,
                    fontSize = 8.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun AccuracyCard(
    accuracy: Int,
    awaitingMotion: Boolean = false,
    aspectScores: Map<String, Int?>,
    /** Core 參與統計的即時 `activeMs`（評分修復更新計畫 §9.5「HUD 可即時顯示『活動 N 分』」） */
    activeMs: Long = 0L,
    showActiveTime: Boolean = false,
    onStop: () -> Unit
) {
    HudCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Circular progress ring
            Box(
                modifier = Modifier.size(58.dp),
                contentAlignment = Alignment.Center
            ) {
                val trackColor = JohnsonColors.Ink500
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color      = trackColor,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter  = false,
                        style      = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    if (accuracy > 0 && !awaitingMotion) {
                        drawArc(
                            color      = JohnsonColors.AccentScore,
                            startAngle = 135f,
                            sweepAngle = 270f * accuracy / 100f,
                            useCenter  = false,
                            style      = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text       = if (awaitingMotion) "－" else accuracy.toString(),
                        color      = JohnsonColors.TextPrimary,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp
                    )
                    Text(
                        text     = if (awaitingMotion) "" else " %",
                        color    = JohnsonColors.TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = "動作準度",
                    color      = JohnsonColors.TextTertiary,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                AspectScoresRow(aspectScores)
                // 活動參與（§9.5）：與動作準度並列，回答的是「有沒有跟著動」而不是「動得對不對」
                if (showActiveTime) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "活動 ${activeMinutesLabel(activeMs)}",
                        color = JohnsonColors.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 12.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .touchClickable(onClick = onStop),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 5.dp
                    ),
                    colors = ButtonDefaults.colors(
                        containerColor        = JohnsonColors.AccentScore,
                        contentColor          = JohnsonColors.Ink900,
                        focusedContainerColor = JohnsonColors.Lime300,
                        focusedContentColor   = JohnsonColors.Ink1000
                    )
                ) {
                    Text(
                        text       = "結束評分",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AspectScoresRow(scores: Map<String, Int?>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf("節奏", "軌跡").forEach { name ->   // 順序面向依決策 A3 延後，不顯示
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = name,
                    color = JohnsonColors.TextTertiary,
                    fontSize = 8.sp,
                    lineHeight = 10.sp
                )
                Text(
                    text = scores[name]?.toString() ?: "－",
                    color = if (scores[name] == null) JohnsonColors.TextTertiary else JohnsonColors.TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 12.sp
                )
            }
        }
    }
}

// ─── Bottom bar ───────────────────────────────────────────────────────────────

// 手機沒有遙控器媒體鍵，這顆按鈕本來只是裝飾用的暫停圖示（TV 上也沒接任何按鍵），
// 現在改成真的可以切換播放/暫停，並依播放狀態切換圖示（沿用 tv-material Button
// 才能同時保留 D-pad Enter 跟 touchClickable 的觸控 tap 兩種輸入）。
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onToggle: () -> Unit) {
    Button(
        onClick = onToggle,
        shape = ButtonDefaults.shape(shape = CircleShape),
        modifier = Modifier
            .size(40.dp)
            .touchClickable(onClick = onToggle),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        if (isPlaying) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(14.dp)
                            .background(JohnsonColors.Gray50, RoundedCornerShape(1.5.dp))
                    )
                }
            }
        } else {
            Canvas(modifier = Modifier.size(16.dp)) {
                val triangle = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(triangle, color = JohnsonColors.Gray50)
            }
        }
    }
}

// 可拖曳 seek 的進度條：視覺上維持原本 3dp 細線，但外層包一個 24dp 高的觸控熱區，
// 手指點/拖到哪就直接呼叫 onSeek 換算後的 positionMs（drag() 沒有 touch slop，
// 一按下去就會立刻反應，符合一般播放器進度條的拖曳手感）。
@Composable
private fun VideoProgressBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val progress = if (durationMs > 0)
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var barWidthPx by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .onSizeChanged { barWidthPx = it.width.toFloat() }
            .pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                fun seekTo(x: Float) {
                    if (barWidthPx <= 0f) return
                    onSeek(((x / barWidthPx).coerceIn(0f, 1f) * durationMs).toLong())
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    seekTo(down.position.x)
                    drag(down.id) { change ->
                        change.consume()
                        seekTo(change.position.x)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(JohnsonColors.Ink500)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .background(JohnsonColors.Brand)
        )
    }
}

// ─── Alert ────────────────────────────────────────────────────────────────────

@Composable
private fun AlertBanner(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(JohnsonColors.Brand.copy(alpha = 0.95f))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text       = "健康警告",
            color      = JohnsonColors.Gray0,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(text = message, color = JohnsonColors.Gray0, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDismiss, modifier = Modifier.touchClickable(onClick = onDismiss)) {
            Text("知道了", color = JohnsonColors.Gray0)
        }
    }
}

// ─── Final score card ─────────────────────────────────────────────────────────

@Composable
private fun FinalScoreCard(
    score: Int,
    grade: String,
    noValidScore: Boolean = false,
    aspectScores: Map<String, Int>,
    durationMs: Long,
    caloriesBurned: Int?,
    avgHeartRate: Int,
    avgBodyTemperatureC: Float?,
    // ── 活動參與指標（Core ParticipationSnapshot，評分修復更新計畫 §9.5）─────────────
    activeMs: Long = 0L,
    longestRunMs: Long = 0L,
    coverage: Float = 0f,
    rhythmRegularity: Float? = null,
    participationHasData: Boolean = false,
    /** 課程中曾拖曳進度條：參與統計會失真，改為說明原因而不是給錯的數字 */
    participationSeeked: Boolean = false,
    /** false（目前：太極）時只顯示參與時間、量測完整度與生理摘要，活動三項與三面向分數都不顯示（§9.2） */
    showActivityStats: Boolean = true,
    onBack: () -> Unit
) {
    // 固定 420dp 在手機直向/窄螢幕下可能比螢幕還寬；改成「撐滿可用寬度的 92%，但最多 420dp」，
    // TV/平板維持原本 420dp 觀感，手機自動收斂到螢幕寬度以內並留一點邊距。
    // 卡片高度另外用螢幕高度扣掉上下留白封頂：手機（尤其橫向播放）高度常不夠放完整張卡，
    // 內容超出時 Column 改用 verticalScroll 讓使用者滑得到，不會整段被裁掉看不到。
    val maxCardHeight = LocalConfiguration.current.screenHeightDp.dp - 48.dp
    Box(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 420.dp)
            .heightIn(max = maxCardHeight.coerceAtLeast(0.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(JohnsonColors.SurfaceCard)
            .border(1.dp, JohnsonColors.BorderDefault, RoundedCornerShape(28.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(if (isCompactWidth()) 24.dp else 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text       = "訓練成果",
                color      = JohnsonColors.TextTertiary,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            // ── 主區塊：活動參與（§9.5）────────────────────────────────────────────────
            // 三面向分數不再是主標題分數；成果卡先回答「有沒有跟著運動、我們量到多少」，
            // 「動作是否精準符合示範」退到下面的「輔助資訊」。
            Spacer(Modifier.height(16.dp))
            val participationMetrics = buildList {
                // seek 過的課程連「參與時間」也不能給：Core 的 exerciseSession 在 seek 過的 session
                // 是以單調 event time 結算的（stop() 不傳影片位置），跳過去的那段會被算進時長與熱量
                // ——播 5 分、跳到第 10 分、再播 5 分會顯示約 15 分（Codex QA D2 缺陷 2）。
                add("參與時間" to if (participationSeeked) "－" else durationMs.toTimeString())
                if (showActivityStats) {
                    add("偵測到活動" to if (participationHasData) activeMinutesLabel(activeMs) else "－")
                    add("最長連續活動" to if (participationHasData) activeMinutesLabel(longestRunMs) else "－")
                }
                add("量測完整度" to if (participationHasData) "${(coverage * 100).roundToInt()}%" else "－")
                if (showActivityStats) {
                    add("節奏規律" to rhythmRegularityLabel(rhythmRegularity, participationHasData))
                }
            }
            participationMetrics.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (label, value) ->
                        FinalMetricStat(value = value, label = label, modifier = Modifier.weight(1f))
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text  = when {
                    participationSeeked ->
                        "這堂課中途調整過播放進度，參與時間、活動時間、量測完整度與消耗熱量都會失真，因此不顯示。"
                    !showActivityStats ->
                        "這堂課的動作較慢，手環目前難以區分「慢動作」與「靜止」，因此不顯示活動時間與節奏。"
                    else ->
                        "「參與時間」是實際跟著播放的時間，「偵測到活動」是手環量到動作的時間，兩者回答不同問題。"
                },
                color = JohnsonColors.TextTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center
            )

            // ── 生理摘要：課程結束當下才算出來的運動數據，與動作評分無關 ──────────────────
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(JohnsonColors.BorderSubtle)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text       = "生理摘要",
                color      = JohnsonColors.TextTertiary,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    // 熱量與參與時間同一個時基，seek 過就一起不顯示（見上）；
                    // 平均心率／體溫是 Player 自己對「播放中收到的樣本」取平均，不受 seek 影響。
                    "消耗卡路里 kcal" to (caloriesBurned?.takeIf { !participationSeeked }?.toString() ?: "－"),
                    "平均心率 bpm" to (if (avgHeartRate > 0) "$avgHeartRate" else "－"),
                    "平均體溫 ℃" to (avgBodyTemperatureC?.let { "%.1f".format(it) } ?: "－")
                ).forEach { (label, value) ->
                    FinalMetricStat(value = value, label = label, modifier = Modifier.weight(1f))
                }
            }

            // ── 輔助資訊：三面向分數（§9.5「三面向分數移到輔助資訊區塊」）──────────────────
            // showActivityStats = false 的課程（太極）連三面向分數一起不顯示。
            if (showActivityStats) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(JohnsonColors.BorderSubtle)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text       = "輔助資訊 · 動作準度",
                    color      = JohnsonColors.TextTertiary,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                )
                Spacer(Modifier.height(8.dp))
                if (noValidScore || aspectScores.isEmpty()) {
                    // 整堂課都沒有可顯示分數（例如全程靜止、Core 只回低 confidence）時不折成 0 分／D 級（決策 A1）
                    Text(
                        text = "無有效評分",
                        color = JohnsonColors.TextTertiary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "$grade · $score 分",
                        color = gradeColor(score),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        aspectScores.forEach { (label, s) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(JohnsonColors.Ink600)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text       = "$s",
                                    color      = JohnsonColors.AccentScore,
                                    fontSize   = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text  = label,
                                    color = JohnsonColors.TextTertiary,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        repeat((2 - aspectScores.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // 「返回首頁」疊在卡片左上角的圓形按鈕，跟主要內容分開、不隨 Column 一起捲動，
        // 確保捲到最下面也一定看得到、按得到。
        Button(
            onClick  = onBack,
            shape    = ButtonDefaults.shape(shape = CircleShape),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(40.dp)
                .touchClickable(onClick = onBack)
        ) {
            Text("←", color = JohnsonColors.TextPrimary, fontSize = 18.sp)
        }
    }
}

/**
 * 「偵測到活動 14 分鐘」用的簡短標籤（§9.1 Player 顯示欄）。不足 1 分鐘改用秒，
 * 避免把 40 秒顯示成「0 分」讓使用者以為完全沒量到。
 */
private fun activeMinutesLabel(ms: Long): String = when {
    ms <= 0L -> "0 分"
    ms < 60_000L -> "${(ms / 1000L).coerceAtLeast(1L)} 秒"
    else -> "${(ms / 60_000.0).roundToInt().coerceAtLeast(1)} 分"
}

/**
 * 節奏規律性的呈現（§9.1「節奏規律／留白」）。Core 的 `rhythmRegularity` 是使用者自身腕部幅度訊號的
 * ACF 正規化峰值，**不是**跟拍正確率，所以不顯示成分數；分級門檻沿用 §0-C 的 periodicity 閘門
 * 0.40–0.70（Core 已把 < 0.40 的窗視為「無週期證據」而不納入平均，所以這裡只需分 0.70 一刀）。
 * `null` ＝ 合格窗不足、不可判斷 → 留白（顯示「－」）。
 */
private fun rhythmRegularityLabel(rhythmRegularity: Float?, hasData: Boolean): String = when {
    !hasData || rhythmRegularity == null -> "－"
    rhythmRegularity >= 0.70f -> "規律"
    else -> "尚可"
}

/** FinalScoreCard 運動數據列的單一格：與下方「各面向評分」格子同一套視覺，數字改用較低調的主文字色。 */
@Composable
private fun FinalMetricStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(JohnsonColors.Ink600)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text       = value,
            color      = JohnsonColors.TextPrimary,
            fontSize   = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text      = label,
            color     = JohnsonColors.TextTertiary,
            fontSize  = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

// 名稱對應 Core `IntensityLevelCalculator` 的 %HRR 分界（20／40／60／80）：
// 白＝<20%、藍＝20–40%、綠＝40–60%、黃＝60–80%、紅＝≥80%。
private fun zoneName(zone: Int): String = when (zone) {
    1    -> "極輕鬆"
    2    -> "熱身"
    3    -> "有氧"
    4    -> "強度"
    5    -> "極高"
    else -> "--"
}

private fun zoneColor(zone: Int): Color = when (zone) {
    1    -> JohnsonColors.HrZ1
    2    -> JohnsonColors.HrZ2
    3    -> JohnsonColors.HrZ3
    4    -> JohnsonColors.HrZ4
    5    -> JohnsonColors.HrZ5
    else -> JohnsonColors.TextTertiary
}

private fun gradeColor(score: Int): Color = when {
    score >= 90 -> JohnsonColors.AccentScore
    score >= 75 -> JohnsonColors.Green500
    score >= 60 -> JohnsonColors.Blue500
    score >= 45 -> JohnsonColors.HrZ4
    else        -> JohnsonColors.Brand
}

private fun Long.toTimeString(): String {
    val s = this / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(name = "Playback – Scoring Active", widthDp = 1280, heightDp = 720, showBackground = false)
@Composable
private fun PlaybackScoringPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B2231))
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, JohnsonColors.Ink1000.copy(alpha = 0.92f))
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, JohnsonColors.Ink1000.copy(alpha = 0.7f))
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 24.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiveBadge()
                    CategoryBadge("高強度間歇")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "30 分鐘 HIIT 爆汗衝刺",
                    color = JohnsonColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("00:50", color = JohnsonColors.Gray0, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                CloseButton {}
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 40.dp, bottom = 60.dp)
        ) {
            FeedbackToast(label = "動作標準！", delta = "+120")
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 32.dp)
                .width(284.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScoreCard(
                gameScore = 89,
                aspectScores = mapOf("節奏" to 92, "軌跡" to 86, "順序" to null),
                aspectDiagnostics = mapOf(
                    "節奏" to "可用 · OK · 100%",
                    "軌跡" to "可用 · OK · 100%",
                    "順序" to "不可用 · ASPECT_NOT_APPLICABLE · 0%"
                ),
                deviceStatus = "已連線",
                imuSampleCount = 325,
                scoringStatus = "Core 評分中"
            )
            HeartRateCard(heartRate = 150, zone = 3)
            AccuracyCard(
                accuracy = 89,
                aspectScores = mapOf("節奏" to 92, "軌跡" to 86, "順序" to null),
                activeMs = 843_000L,
                showActiveTime = true,
                onStop = {}
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayPauseButton(isPlaying = true, onToggle = {})
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("目前動作", color = JohnsonColors.TextTertiary, fontSize = 11.sp)
                    Text("深蹲跳 ×15", color = JohnsonColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            VideoProgressBar(positionMs = 50_000L, durationMs = 1_800_000L, onSeek = {})
        }
    }
}

@Preview(name = "Playback – Final Score Card", widthDp = 1280, heightDp = 720, showBackground = false)
@Composable
private fun PlaybackFinalScorePreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.Ink1000.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        FinalScoreCard(
            score = 87,
            grade = "A",
            aspectScores = mapOf(
                "節奏"       to 87,
                "軌跡"       to 82,
                "片段相似度" to 85
            ),
            durationMs     = 1_830_000L,
            caloriesBurned = 216,
            avgHeartRate   = 138,
            avgBodyTemperatureC = 33.6f,
            activeMs       = 931_700L,
            longestRunMs   = 179_100L,
            coverage       = 0.983f,
            rhythmRegularity = 0.537f,
            participationHasData = true,
            showActivityStats = true,
            onBack = {}
        )
    }
}
