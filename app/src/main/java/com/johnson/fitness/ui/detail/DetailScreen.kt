@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package com.johnson.fitness.ui.detail

import android.widget.Toast
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
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.foundation.layout.widthIn
import com.johnson.fitness.model.Movie
import com.johnson.fitness.ui.common.isCompactWidth
import com.johnson.fitness.ui.common.touchClickable

@Composable
fun DetailScreen(
    movieId: Long,
    onWatchTrailer: () -> Unit,
    onRelatedMovieClick: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = viewModel { DetailViewModel(movieId) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is DetailEffect.NavigateToPlayback -> onWatchTrailer()
                is DetailEffect.NavigateToDetail -> onRelatedMovieClick(effect.movieId)
                is DetailEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val movie = state.movie ?: return

    Box(modifier = Modifier.fillMaxSize()) {
        GlideImage(
            model = movie.backgroundImageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
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
            Text(text = movie.studio, fontSize = 16.sp, color = Color.LightGray)
            Spacer(Modifier.height(16.dp))
            Text(
                text = movie.description,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f),
                lineHeight = 20.sp,
                maxLines = 4
            )
            Spacer(Modifier.height(24.dp))
            // 三顆按鈕在窄螢幕下可能比欄寬還寬（欄位又被 widthIn(max) 縮到手機螢幕寬度），
            // 加上橫向捲動避免文字被裁掉／按鈕被壓縮到點不到。
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val onWatchTrailer = { viewModel.onIntent(DetailIntent.WatchTrailer) }
                val onRent = { viewModel.onIntent(DetailIntent.Rent) }
                val onBuy = { viewModel.onIntent(DetailIntent.Buy) }
                Button(onClick = onWatchTrailer, modifier = Modifier.touchClickable(onClick = onWatchTrailer)) { Text("Watch Trailer") }
                Button(onClick = onRent, modifier = Modifier.touchClickable(onClick = onRent)) { Text("Rent \$3.99") }
                Button(onClick = onBuy, modifier = Modifier.touchClickable(onClick = onBuy)) { Text("Buy \$9.99") }
            }
            Spacer(Modifier.height(32.dp))
            Text(text = "Related Movies", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
    }
}

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
        GlideImage(
            model = movie.cardImageUrl,
            contentDescription = movie.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
