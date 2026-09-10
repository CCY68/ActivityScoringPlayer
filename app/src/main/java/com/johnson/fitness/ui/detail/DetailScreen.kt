@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package com.johnson.fitness.ui.detail

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.foundation.layout.widthIn
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.common.isCompactWidth
import com.johnson.fitness.ui.common.touchClickable
import com.johnson.fitness.ui.playback.PlaybackLaunchConfig
import com.johnson.fitness.ui.theme.JohnsonColors

@Composable
fun DetailScreen(
    movieId: Long,
    onWatchTrailer: (PlaybackLaunchConfig) -> Unit,
    onRelatedMovieClick: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = viewModel { DetailViewModel(movieId) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSourceDialog by remember { mutableStateOf(false) }
    var showB20RecordingDialog by remember { mutableStateOf(false) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> cursor.takeIf { it.moveToFirst() }?.getString(0) }
        showSourceDialog = false
        onWatchTrailer(PlaybackLaunchConfig.ReplayCsv(uri, displayName))
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is DetailEffect.NavigateToPlayback -> showSourceDialog = true
                is DetailEffect.NavigateToDetail -> onRelatedMovieClick(effect.movieId)
            }
        }
    }

    val movie = state.movie ?: return

    // 沒有課程主視覺時，底色要自己畫：Activity 沒有包 Surface，不墊底就會露出系統主題背景。
    Box(modifier = Modifier.fillMaxSize().background(JohnsonColors.BgApp)) {
        if (movie.backgroundImageUrl.isNotBlank()) {
            GlideImage(
                model = movie.backgroundImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
        )
        // 固定 520dp 是照 TV 寬螢幕設計的資訊面板寬度；手機螢幕（例如 380dp 寬）直接套用
        // 會整個溢出畫面。改成「盡量撐滿可用寬度，但最多到 520dp」，TV/平板維持原本觀感，
        // 手機則自動縮到螢幕寬度以內。
        val horizontalPadding = if (isCompactWidth()) 20.dp else 48.dp
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(start = horizontalPadding, top = 48.dp, end = horizontalPadding)
        ) {
            Text(text = movie.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(text = movie.category, fontSize = 16.sp, color = Color.LightGray)
            Spacer(Modifier.height(16.dp))
            Text(
                text = movie.description,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f),
                lineHeight = 20.sp,
                maxLines = 4
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (movie.hasScoringData) "已標註課程，配戴手環可評分" else "無 .maf 課程檔，僅播放不評分",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(24.dp))
            // 按鈕在窄螢幕下可能比欄寬還寬（欄位又被 widthIn(max) 縮到手機螢幕寬度），
            // 加上橫向捲動避免文字被裁掉／按鈕被壓縮到點不到。
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val onStartCourse = { viewModel.onIntent(DetailIntent.StartCourse) }
                val onBackClick = { onBack() }
                Button(onClick = onStartCourse, modifier = Modifier.touchClickable(onClick = onStartCourse)) { Text("開始課程") }
                Button(onClick = onBackClick, modifier = Modifier.touchClickable(onClick = onBackClick)) { Text("返回") }
            }
            Spacer(Modifier.height(32.dp))
            Text(text = "其他課程", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.relatedMovies) { related ->
                    RelatedMovieCard(
                        movie = related,
                        onClick = { viewModel.onIntent(DetailIntent.RelatedMovieClicked(related)) }
                    )
                }
            }
        }

        if (showSourceDialog) {
            PlaybackModeDialog(
                onUseLiveB20 = {
                    showSourceDialog = false
                    showB20RecordingDialog = true
                },
                onSelectCsv = {
                    csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
                },
                onCancel = { showSourceDialog = false }
            )
        }

        if (showB20RecordingDialog) {
            B20RecordingDialog(
                onRecord = {
                    showB20RecordingDialog = false
                    onWatchTrailer(PlaybackLaunchConfig.LiveB20(recordCsv = true))
                },
                onDoNotRecord = {
                    showB20RecordingDialog = false
                    onWatchTrailer(PlaybackLaunchConfig.LiveB20(recordCsv = false))
                },
                onBack = {
                    showB20RecordingDialog = false
                    showSourceDialog = true
                }
            )
        }
    }
}

@Composable
private fun PlaybackModeDialog(
    onUseLiveB20: () -> Unit,
    onSelectCsv: () -> Unit,
    onCancel: () -> Unit
) {
    MaterialAlertDialog(
        onDismissRequest = {},
        title = { Text("選擇播放模式", color = JohnsonColors.Gray0) },
        text = {
            Text(
                "進入影片前，請選擇正常 B20 模式或 Replay CSV 測試模式。",
                color = JohnsonColors.Gray100
            )
        },
        confirmButton = {
            Button(
                onClick = onUseLiveB20,
                modifier = Modifier.touchClickable(onClick = onUseLiveB20),
                colors = dialogPrimaryButtonColors()
            ) {
                Text("正常模式（B20）", color = JohnsonColors.Gray0)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSelectCsv,
                    modifier = Modifier.touchClickable(onClick = onSelectCsv),
                    colors = dialogSecondaryButtonColors()
                ) {
                    Text("Replay CSV", color = JohnsonColors.Gray0)
                }
                Button(
                    onClick = onCancel,
                    modifier = Modifier.touchClickable(onClick = onCancel),
                    colors = dialogSecondaryButtonColors()
                ) {
                    Text("取消", color = JohnsonColors.Gray0)
                }
            }
        },
        containerColor = JohnsonColors.Ink600,
        titleContentColor = JohnsonColors.Gray0,
        textContentColor = JohnsonColors.Gray100
    )
}

@Composable
private fun B20RecordingDialog(
    onRecord: () -> Unit,
    onDoNotRecord: () -> Unit,
    onBack: () -> Unit
) {
    MaterialAlertDialog(
        onDismissRequest = {},
        title = { Text("是否收錄 B20 IMU？", color = JohnsonColors.Gray0) },
        text = {
            Text(
                "收錄會在進入播放頁時開始，影片播完 3 秒後自動完成 CSV。",
                color = JohnsonColors.Gray100
            )
        },
        confirmButton = {
            Button(
                onClick = onRecord,
                modifier = Modifier.touchClickable(onClick = onRecord),
                colors = dialogPrimaryButtonColors()
            ) {
                Text("收錄 CSV", color = JohnsonColors.Gray0)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDoNotRecord,
                    modifier = Modifier.touchClickable(onClick = onDoNotRecord),
                    colors = dialogSecondaryButtonColors()
                ) { Text("不收錄", color = JohnsonColors.Gray0) }
                Button(
                    onClick = onBack,
                    modifier = Modifier.touchClickable(onClick = onBack),
                    colors = dialogSecondaryButtonColors()
                ) {
                    Text("返回", color = JohnsonColors.Gray0)
                }
            }
        },
        containerColor = JohnsonColors.Ink600,
        titleContentColor = JohnsonColors.Gray0,
        textContentColor = JohnsonColors.Gray100
    )
}

@Composable
private fun dialogPrimaryButtonColors() = ButtonDefaults.colors(
    containerColor = JohnsonColors.Red500,
    contentColor = JohnsonColors.Gray0,
    focusedContainerColor = JohnsonColors.Red400,
    focusedContentColor = JohnsonColors.Gray0
)

@Composable
private fun dialogSecondaryButtonColors() = ButtonDefaults.colors(
    containerColor = JohnsonColors.Ink400,
    contentColor = JohnsonColors.Gray0,
    focusedContainerColor = JohnsonColors.Ink500,
    focusedContentColor = JohnsonColors.Gray0
)

@Composable
private fun RelatedMovieCard(movie: Movie, onClick: () -> Unit) {
    val compact = isCompactWidth()
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(if (compact) 128.dp else 160.dp)
            .height(if (compact) 80.dp else 100.dp)
            .touchClickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(JohnsonColors.SurfaceCard)) {
            if (movie.cardImageUrl.isNotBlank()) {
                GlideImage(
                    model = movie.cardImageUrl,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 沒有縮圖時（目前全部如此）至少要看得到是哪一堂課。
            Text(
                text = movie.title,
                color = JohnsonColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
        }
    }
}
