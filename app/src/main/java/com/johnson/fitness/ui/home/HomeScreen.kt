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
    Column(
        modifier = Modifier
            .width(104.dp)
            .fillMaxHeight()
            .background(JohnsonColors.SurfaceBase)
            .border(
                width = 1.dp,
                color = JohnsonColors.BorderSubtle,
                shape = RoundedCornerShape(0.dp)
            )
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo mark
        Box(
            modifier = Modifier
                .size(42.dp)
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

        Spacer(Modifier.height(28.dp))

        NavItem(label = "首頁", isActive = true)
        NavItem(label = "探索")
        NavItem(label = "數據")

        Spacer(Modifier.weight(1f))

        // Settings at bottom
        Card(
            onClick = onSettingsClick,
            modifier = Modifier.size(52.dp)
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
private fun NavItem(label: String, isActive: Boolean = false) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) JohnsonColors.BrandTint else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) JohnsonColors.Brand else JohnsonColors.TextTertiary,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 28.dp),
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
                fontSize = 36.sp,
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
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 56.dp, bottom = 14.dp),
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
            contentPadding = PaddingValues(horizontal = 56.dp),
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
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(256.dp)
            .height(152.dp)
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
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = "PREFERENCES",
            modifier = Modifier.padding(start = 56.dp, bottom = 14.dp),
            color = JohnsonColors.TextTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.14.sp
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 56.dp),
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
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(180.dp)
            .height(72.dp)
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
