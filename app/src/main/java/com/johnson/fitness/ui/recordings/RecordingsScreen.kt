@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.johnson.fitness.ui.recordings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.johnson.fitness.data.ImuCsvStore
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.common.CourseCardStyle
import com.johnson.fitness.ui.common.isCompactWidth
import com.johnson.fitness.ui.common.touchClickable
import com.johnson.fitness.ui.playback.PlaybackLaunchConfig
import com.johnson.fitness.ui.theme.JohnsonColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首頁「數據」的目的地：列出本機收錄的 IMU CSV，可直接回放、可刪除。
 *
 * 取代原本「探索」一起被拿來裝飾的不可點標籤——PR 之前這裡什麼都沒有，
 * 收錄完的 CSV 只能靠系統檔案選擇器（`DetailScreen` 的 `csvPicker`）找，電視上幾乎沒法操作。
 */
@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onReplay: (movieId: Long, config: PlaybackLaunchConfig.ReplayCsv) -> Unit,
    viewModel: RecordingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is RecordingsEffect.Replay -> onReplay(effect.movieId, effect.config)
                is RecordingsEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val horizontalPadding = if (isCompactWidth()) 20.dp else 56.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.BgApp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header（比照 SettingsScreen：標題＋副標＋右上「返回」）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "錄製資料",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = JohnsonColors.TextPrimary,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${state.storageLocationLabel}・共 ${state.items.size} 筆",
                        color = JohnsonColors.TextTertiary,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onBack, modifier = Modifier.touchClickable(onClick = onBack)) { Text("返回") }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .height(1.dp)
                    .background(JohnsonColors.BorderSubtle)
            )

            when {
                state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = JohnsonColors.Brand)
                }

                state.items.isEmpty() -> EmptyState(horizontalPadding)

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = 20.dp,
                        bottom = 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.items, key = { it.recording.uri.toString() }) { item ->
                        RecordingRow(
                            item = item,
                            onReplay = { viewModel.onIntent(RecordingsIntent.ReplayClicked(item.recording)) },
                            onDelete = { viewModel.onIntent(RecordingsIntent.DeleteRequested(item.recording)) }
                        )
                    }
                }
            }
        }

        state.pendingDelete?.let { recording ->
            DeleteConfirmDialog(
                fileName = recording.fileName,
                onConfirm = { viewModel.onIntent(RecordingsIntent.DeleteConfirmed) },
                onCancel = { viewModel.onIntent(RecordingsIntent.DeleteCancelled) }
            )
        }

        state.pendingReplaySelection?.let {
            ChooseCourseDialog(
                courses = state.scorableCourses,
                onChoose = { movieId -> viewModel.onIntent(RecordingsIntent.CourseChosenForReplay(movieId)) },
                onCancel = { viewModel.onIntent(RecordingsIntent.DismissCourseDialog) }
            )
        }
    }
}

@Composable
private fun EmptyState(horizontalPadding: Dp) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "尚無錄製資料——在課程詳情頁選「正常模式（B20）→ 收錄 CSV」即可開始",
            color = JohnsonColors.TextTertiary,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = horizontalPadding)
        )
    }
}

@Composable
private fun RecordingRow(item: RecordingItem, onReplay: () -> Unit, onDelete: () -> Unit) {
    val recording = item.recording
    RowFrame {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recording.fileName,
                color = JohnsonColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.courseTitle ?: "未知課程",
                color = JohnsonColors.TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${formatRecordedAt(recording.recordedAt)}・${formatBytes(recording.bytes)}・${summaryLabel(item.summary)}",
                color = JohnsonColors.TextTertiary,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(16.dp))
        Button(onClick = onReplay, modifier = Modifier.touchClickable(onClick = onReplay)) { Text("回放") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onDelete,
            modifier = Modifier.touchClickable(onClick = onDelete),
            colors = ButtonDefaults.colors(
                containerColor = JohnsonColors.Ink400,
                contentColor = JohnsonColors.Gray0,
                focusedContainerColor = JohnsonColors.Red600,
                focusedContentColor = JohnsonColors.Gray0
            )
        ) { Text("刪除") }
    }
}

@Composable
private fun RowFrame(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(JohnsonColors.SurfaceCard)
            .border(1.dp, JohnsonColors.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun DeleteConfirmDialog(fileName: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    // onDismissRequest 刻意留空（比照 DetailScreen 的對話框）：TV 遙控器的返回鍵不該悄悄關掉
    // 確認對話框，一定要按到「刪除」或「取消」其中一個才離開。
    MaterialAlertDialog(
        onDismissRequest = {},
        title = { Text("確定要刪除這筆錄製資料？", color = JohnsonColors.Gray0) },
        text = {
            Text("$fileName 刪除後無法復原。", color = JohnsonColors.Gray100)
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
            ) { Text("刪除", color = JohnsonColors.Gray0) }
        },
        dismissButton = {
            Button(
                onClick = onCancel,
                modifier = Modifier.touchClickable(onClick = onCancel),
                colors = ButtonDefaults.colors(
                    containerColor = JohnsonColors.Ink400,
                    contentColor = JohnsonColors.Gray0,
                    focusedContainerColor = JohnsonColors.Ink500,
                    focusedContentColor = JohnsonColors.Gray0
                )
            ) { Text("取消", color = JohnsonColors.Gray0) }
        },
        containerColor = JohnsonColors.Ink600,
        titleContentColor = JohnsonColors.Gray0,
        textContentColor = JohnsonColors.Gray100
    )
}

/** courseId 對不到課程時（舊檔或課程已從目錄移除），回放前先讓使用者自己選一支有 `.maf` 的課程。 */
@Composable
private fun ChooseCourseDialog(courses: List<Movie>, onChoose: (Long) -> Unit, onCancel: () -> Unit) {
    MaterialAlertDialog(
        onDismissRequest = {},
        title = { Text("選擇課程", color = JohnsonColors.Gray0) },
        text = {
            if (courses.isEmpty()) {
                Text("目錄裡沒有可評分的課程。", color = JohnsonColors.Gray100)
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    courses.forEach { movie ->
                        val onClick = { onChoose(movie.id) }
                        Button(
                            onClick = onClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .touchClickable(onClick = onClick)
                        ) {
                            Text(movie.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCancel, modifier = Modifier.touchClickable(onClick = onCancel)) {
                Text("取消")
            }
        },
        containerColor = JohnsonColors.Ink600,
        titleContentColor = JohnsonColors.Gray0,
        textContentColor = JohnsonColors.Gray100
    )
}

private fun summaryLabel(summary: ImuCsvStore.RecordingSummary?): String {
    if (summary == null) return "計算中…"
    if (summary.sampleCount <= 0) return "0 筆"
    val durationSec = ((summary.lastMs - summary.firstMs).coerceAtLeast(0) / 1000).toInt()
    val durationLabel = CourseCardStyle.formatDuration(durationSec).ifEmpty { "未滿 1 分鐘" }
    return "${summary.sampleCount} 筆・$durationLabel"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatRecordedAt(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).format(Date(epochMs))
