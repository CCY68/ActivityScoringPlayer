@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.johnson.fitness.ui.playback

import android.net.Uri
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

// ─── Entry ────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(
    movieId: Long,
    onBack: () -> Unit,
    viewModel: PlaybackViewModel = viewModel(key = "playback_$movieId") {
        val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as FitnessApp
        PlaybackViewModel(movieId, app.scoringEngineFactory, app.deviceManager, app, app.lastDevicePreferences)
    }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is PlaybackEffect.NavigateBack -> onBack()
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
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    LaunchedEffect(state.movie) {
        state.movie?.videoUrl?.let { url ->
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            exoPlayer.prepare()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            viewModel.onIntent(
                PlaybackIntent.VideoStateChanged(
                    positionMs = exoPlayer.currentPosition,
                    durationMs = exoPlayer.duration.coerceAtLeast(0L),
                    isPlaying  = exoPlayer.isPlaying
                )
            )
            delay(500)
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
                    val coach = state.movie?.studio.orEmpty()
                    if (coach.isNotBlank()) CoachBadge(coach)
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
                CloseButton { viewModel.onIntent(PlaybackIntent.BackPressed) }
            }
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
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = if (isCompactWidth()) 10.dp else 20.dp)
                    .fillMaxWidth(0.24f)
                    .widthIn(min = 120.dp, max = 180.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScoreCard(
                    gameScore = state.gameScore,
                    aspectScores = state.currentAspectScores,
                    aspectDiagnostics = state.currentAspectDiagnostics,
                    deviceStatus = state.deviceStatus,
                    imuSampleCount = state.imuSampleCount,
                    scoringStatus = state.scoringStatus
                )
                HeartRateCard(heartRate = state.heartRate)
                AccuracyCard(
                    accuracy = state.accuracy,
                    aspectScores = state.currentAspectScores,
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
                    score        = score,
                    grade        = state.grade,
                    aspectScores = state.aspectScores,
                    onBack       = { viewModel.onIntent(PlaybackIntent.BackPressed) }
                )
            }
        }
    }
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

@Composable
private fun CoachBadge(name: String) {
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
            text = "COACH ${name.uppercase()}",
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
            text = gameScore.toString(),
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
        listOf("節奏", "軌跡", "順序").forEach { name ->
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
private fun HeartRateCard(heartRate: Int) {
    val zone  = heartRateZone(heartRate)
    val zColor = zoneColor(zone)
    val zName  = zoneName(zone)

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
            text = if (heartRate > 0) "ZONE $zone · $zName" else "-- --",
            color = if (heartRate > 0) zColor else JohnsonColors.TextTertiary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(7.dp))
        HrZoneBars(activeZone = zone)
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
    aspectScores: Map<String, Int?>,
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
                    if (accuracy > 0) {
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
                        text       = accuracy.toString(),
                        color      = JohnsonColors.TextPrimary,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp
                    )
                    Text(
                        text     = " %",
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
        listOf("節奏", "軌跡", "順序").forEach { name ->
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
    aspectScores: Map<String, Int>,
    onBack: () -> Unit
) {
    // 固定 420dp 在手機直向/窄螢幕下可能比螢幕還寬；改成「撐滿可用寬度的 92%，但最多 420dp」，
    // TV/平板維持原本 420dp 觀感，手機自動收斂到螢幕寬度以內並留一點邊距。
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(JohnsonColors.SurfaceCard)
            .border(1.dp, JohnsonColors.BorderDefault, RoundedCornerShape(28.dp))
            .padding(if (isCompactWidth()) 24.dp else 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text       = "訓練成果",
            color      = JohnsonColors.TextTertiary,
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text       = grade,
            color      = gradeColor(score),
            fontSize   = 80.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 80.sp
        )
        Text(
            text       = "$score 分",
            color      = JohnsonColors.TextPrimary,
            fontSize   = 32.sp,
            fontWeight = FontWeight.Bold
        )

        // 三個評分面向各自的課程平均分數（節奏/軌跡/片段相似度），App 端自行平均而來
        if (aspectScores.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(JohnsonColors.BorderSubtle)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text       = "各面向評分",
                color      = JohnsonColors.TextTertiary,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            )
            Spacer(Modifier.height(12.dp))
            val entries = aspectScores.entries.toList()
            entries.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (label, s) ->
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
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick  = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .touchClickable(onClick = onBack)
        ) {
            Text("返回首頁", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun heartRateZone(bpm: Int): Int = when {
    bpm <= 0  -> 0
    bpm < 115 -> 1
    bpm < 133 -> 2
    bpm < 152 -> 3
    bpm < 172 -> 4
    else      -> 5
}

private fun zoneName(zone: Int): String = when (zone) {
    1    -> "恢復"
    2    -> "燃脂"
    3    -> "有氧"
    4    -> "力量"
    5    -> "衝刺"
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
                    CoachBadge("Vivian")
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
            HeartRateCard(heartRate = 150)
            AccuracyCard(
                accuracy = 89,
                aspectScores = mapOf("節奏" to 92, "軌跡" to 86, "順序" to null),
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
            onBack = {}
        )
    }
}
