@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package com.johnson.fitness.ui.home

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.johnson.fitness.model.Movie
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

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is HomeEffect.NavigateToDetail -> onMovieClick(effect.movieId)
                is HomeEffect.NavigateToError -> onErrorClick()
                is HomeEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
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
            // Background image with gradient scrim
            GlideImage(
                model = state.backgroundUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                // Top bar
                item {
                    TopBar()
                }
                // Category rails
                items(state.categories) { category ->
                    CategoryRail(
                        category = category,
                        onMovieFocused = { viewModel.onIntent(HomeIntent.MovieFocused(it)) },
                        onMovieClicked = { viewModel.onIntent(HomeIntent.MovieClicked(it)) }
                    )
                }
                // Preferences / utility row
                item {
                    PreferencesRail(
                        onErrorClick = { viewModel.onIntent(HomeIntent.ErrorClicked) },
                        onPreferenceClick = { viewModel.onIntent(HomeIntent.PreferenceClicked(it)) }
                    )
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
private fun TopBar() {
    val horizontalPadding = if (isCompactWidth()) 20.dp else 56.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = 40.dp, bottom = 28.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "準備好了嗎",
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
                text = category.name,
                color = JohnsonColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "查看全部",
                color = JohnsonColors.TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(category.movies) { movie ->
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
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(if (compact) 184.dp else 256.dp)
            .height(if (compact) 110.dp else 152.dp)
            .touchClickable(onClick = onClick)
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(JohnsonColors.SurfaceCard, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, JohnsonColors.BorderSubtle, RoundedCornerShape(20.dp))
        ) {
            GlideImage(
                model = movie.cardImageUrl,
                contentDescription = movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Bottom scrim
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(80.dp)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = movie.studio,
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
private fun PreferencesRail(
    onErrorClick: () -> Unit,
    onPreferenceClick: (String) -> Unit
) {
    val horizontalPadding = if (isCompactWidth()) 20.dp else 56.dp
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = "PREFERENCES",
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
            item { UtilCard(label = "Grid View", onClick = { onPreferenceClick("Grid View") }) }
            item { UtilCard(label = "Error Fragment", onClick = onErrorClick) }
            item { UtilCard(label = "Personal Settings", onClick = { onPreferenceClick("Personal Settings") }) }
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
