@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package com.johnson.fitness.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.fitness.device.model.ConnectionState
import com.johnson.fitness.FitnessApp
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.common.CourseCardStyle
import com.johnson.fitness.ui.common.isCompactWidth
import com.johnson.fitness.ui.common.touchClickable
import com.johnson.fitness.ui.theme.JohnsonColors

@Composable
fun HomeScreen(
    onMovieClick: (Long) -> Unit,
    onErrorClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deviceManager = (context.applicationContext as FitnessApp).deviceManager
    val connectionState by deviceManager.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is HomeEffect.NavigateToDetail -> onMovieClick(effect.movieId)
                is HomeEffect.NavigateToError -> onErrorClick()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(JohnsonColors.BgApp)
    ) {
        // Left Nav Rail
        NavRail(onSettingsClick = onSettingsClick)

        // Main content
        Box(modifier = Modifier.fillMaxSize()) {
            // Background image with gradient scrim（沒有縮圖時只留下面的漸層底色）
            if (state.backgroundUrl.isNotBlank()) {
                GlideImage(
                    model = state.backgroundUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Dark overlay so text is legible
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(JohnsonColors.BgApp.copy(alpha = 0.75f), JohnsonColors.BgApp.copy(alpha = 0.92f))
                        )
                    )
            )

            when {
                state.errorMessage != null -> CatalogErrorPanel(
                    message = state.errorMessage.orEmpty(),
                    onRetry = { viewModel.onIntent(HomeIntent.Retry) }
                )

                state.isLoading -> CatalogLoadingPanel()

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    // Top bar
                    item(key = "topBar") {
                        TopBar(
                            isBluetoothConnected = connectionState is ConnectionState.Connected,
                            courseCount = state.courseCount,
                            scorableCount = state.scorableCount
                        )
                    }
                    // 影片牆：一個分類一列，列內橫向捲動
                    items(
                        state.categories,
                        // 「可評分課程」是跨分類的快捷列，名稱有可能跟真的分類撞名，key 另外標記。
                        key = { if (it.isScorableShortcut) "__scorable__" else "cat:${it.name}" }
                    ) { category ->
                        CategoryRail(
                            category = category,
                            onMovieFocused = { viewModel.onIntent(HomeIntent.MovieFocused(it)) },
                            onMovieClicked = { viewModel.onIntent(HomeIntent.MovieClicked(it)) }
                        )
                    }
                    // 示範用工具列
                    item(key = "demoTools") {
                        DemoToolsRail(onErrorClick = { viewModel.onIntent(HomeIntent.ErrorClicked) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRail(onSettingsClick: () -> Unit) {
    // 104dp 寬的側邊導覽欄是照 TV 10-foot 畫面的比例設計的；手機螢幕窄很多，同樣寬度會佔掉
    // 過高比例的畫面，這裡窄螢幕時縮小欄寬、圖示與間距，而不是整個拿掉（維持左側常駐導覽的結構）。
    val compact = isCompactWidth()
    val railWidth = if (compact) 68.dp else 104.dp
    val logoSize = if (compact) 36.dp else 42.dp
    val verticalPadding = if (compact) 16.dp else 28.dp

    Column(
        modifier = Modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(JohnsonColors.SurfaceBase)
            .border(
                width = 1.dp,
                color = JohnsonColors.BorderSubtle,
                shape = RoundedCornerShape(0.dp)
            )
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo mark
        Box(
            modifier = Modifier
                .size(logoSize)
                .background(JohnsonColors.Brand, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "JA",
                color = JohnsonColors.Gray0,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.height(if (compact) 16.dp else 28.dp))

        NavItem(label = "首頁", isActive = true, compact = compact)
        NavItem(label = "探索", compact = compact)
        NavItem(label = "數據", compact = compact)

        Spacer(Modifier.weight(1f))

        // Settings at bottom
        Card(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(if (compact) 44.dp else 52.dp)
                .touchClickable(onClick = onSettingsClick)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(JohnsonColors.SurfaceRaised)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "設定",
                    tint = JohnsonColors.TextTertiary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun NavItem(label: String, isActive: Boolean = false, compact: Boolean = false) {
    Box(
        modifier = Modifier
            .width(if (compact) 56.dp else 72.dp)
            .height(if (compact) 44.dp else 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) JohnsonColors.BrandTint else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) JohnsonColors.Brand else JohnsonColors.TextTertiary,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun TopBar(isBluetoothConnected: Boolean, courseCount: Int, scorableCount: Int) {
    val horizontalPadding = if (isCompactWidth()) 20.dp else 56.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = 40.dp, bottom = 28.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "課程目錄 $courseCount 支，其中 $scorableCount 支可評分",
                color = JohnsonColors.TextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.14.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "今天，動起來",
                color = JohnsonColors.TextPrimary,
                fontSize = if (isCompactWidth()) 26.sp else 36.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        }
        Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = if (isBluetoothConnected) "藍牙已連線" else "藍牙未連線",
            tint = if (isBluetoothConnected) JohnsonColors.Blue500 else JohnsonColors.TextTertiary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun CategoryRail(
    category: HomeCategory,
    onMovieFocused: (Movie) -> Unit,
    onMovieClicked: (Movie) -> Unit
) {
    val horizontalPadding = if (isCompactWidth()) 20.dp else 56.dp
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (category.isScorableShortcut) "${category.name}（有 .maf）" else category.name,
                color = JohnsonColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${category.movies.size} 支課程",
                color = JohnsonColors.TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        // key 用課程編號：60 張卡在列間捲動時 Compose 才不會把整列重建（卡片本身沒有非同步載入，
        // 沒有縮圖時是純色底，捲動成本只有文字排版）。
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(category.movies, key = { it.id }) { movie ->
                ClassCard(
                    movie = movie,
                    onFocused = { onMovieFocused(movie) },
                    onClick = { onMovieClicked(movie) }
                )
            }
        }
    }
}

@Composable
private fun ClassCard(movie: Movie, onFocused: () -> Unit, onClick: () -> Unit) {
    val compact = isCompactWidth()
    var isFocused by remember { mutableStateOf(false) }
    // 焦點放大：D-pad 移到哪一張卡，那張卡放大並亮起焦點框，10-foot 距離下才看得出焦點在哪。
    val cardScale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "cardScale"
    )
    Card(
        onClick = onClick,
        modifier = Modifier
            .scale(cardScale)
            .width(if (compact) 184.dp else 256.dp)
            .height(if (compact) 118.dp else 160.dp)
            .touchClickable(onClick = onClick)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(JohnsonColors.SurfaceCard, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) JohnsonColors.FocusRing else JohnsonColors.BorderSubtle,
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            if (movie.cardImageUrl.isNotBlank()) {
                GlideImage(
                    model = movie.cardImageUrl,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 沒有縮圖時用依課程編號決定的純色底（同一支課程顏色固定），不是空白卡。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CourseCardStyle.placeholderBrush(movie))
                )
            }
            // 右上角：可評分／僅播放
            ScoringBadge(
                movie = movie,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            )
            // Bottom scrim
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(88.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, JohnsonColors.Ink1000.copy(alpha = 0.92f))
                        )
                    )
            )
            // Text content
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = movie.title,
                    color = JohnsonColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val duration = CourseCardStyle.formatDuration(movie.durationSec)
                Text(
                    text = if (duration.isEmpty()) movie.category else "${movie.category} · $duration",
                    color = JohnsonColors.TextTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ScoringBadge(movie: Movie, modifier: Modifier = Modifier) {
    val scorable = movie.hasScoringData
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (scorable) JohnsonColors.AccentTint else JohnsonColors.SurfaceGlass)
            .border(
                width = 1.dp,
                color = if (scorable) JohnsonColors.AccentScore else JohnsonColors.BorderDefault,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = CourseCardStyle.scoringBadge(movie),
            color = if (scorable) JohnsonColors.AccentScore else JohnsonColors.TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 目錄還在讀 assets 時的過場；讀一份 13 KB 的 JSON 通常一瞬間就結束，但不要讓畫面是全黑的。 */
@Composable
private fun CatalogLoadingPanel() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = JohnsonColors.Brand)
            Spacer(Modifier.height(16.dp))
            Text(text = "載入課程目錄…", color = JohnsonColors.TextSecondary, fontSize = 14.sp)
        }
    }
}

/**
 * 目錄載入／解析失敗時的明確錯誤畫面（**不留白**）：說明是哪個檔案、錯在哪，並提供重試。
 * 這是給拿 Demo 機的人看的，所以直接寫出 `assets/courses.json` 這個檔名。
 */
@Composable
private fun CatalogErrorPanel(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Text(
                text = "課程目錄載入失敗",
                color = JohnsonColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = JohnsonColors.TextSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "請檢查 app/src/main/assets/courses.json 的格式（欄位說明見專案 README）。",
                color = JohnsonColors.TextTertiary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.touchClickable(onClick = onRetry)) {
                Text("重試")
            }
        }
    }
}

@Composable
private fun DemoToolsRail(onErrorClick: () -> Unit) {
    val horizontalPadding = if (isCompactWidth()) 20.dp else 56.dp
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = "示範工具",
            modifier = Modifier.padding(start = horizontalPadding, bottom = 14.dp),
            color = JohnsonColors.TextTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.14.sp
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 錯誤畫面沒有其他入口，保留這張卡當作它的示範入口。
            item { UtilCard(label = "錯誤畫面示範", onClick = onErrorClick) }
        }
    }
}

@Composable
private fun UtilCard(label: String, onClick: () -> Unit) {
    val compact = isCompactWidth()
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(if (compact) 148.dp else 180.dp)
            .height(if (compact) 64.dp else 72.dp)
            .touchClickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(JohnsonColors.SurfaceCard, RoundedCornerShape(14.dp))
                .border(1.dp, JohnsonColors.BorderSubtle, RoundedCornerShape(14.dp))
        ) {
            Text(
                text = label,
                color = JohnsonColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
