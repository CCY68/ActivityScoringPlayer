@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package com.johnson.fitness.ui.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.johnson.fitness.model.Movie

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
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(520.dp)
                .padding(start = 48.dp, top = 48.dp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.onIntent(DetailIntent.WatchTrailer) }) { Text("Watch Trailer") }
                Button(onClick = { viewModel.onIntent(DetailIntent.Rent) }) { Text("Rent \$3.99") }
                Button(onClick = { viewModel.onIntent(DetailIntent.Buy) }) { Text("Buy \$9.99") }
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
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(160.dp)
            .height(100.dp)
    ) {
        GlideImage(
            model = movie.cardImageUrl,
            contentDescription = movie.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
