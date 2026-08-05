package com.johnson.fitness.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.johnson.fitness.ui.bluetooth.BluetoothScreen
import com.johnson.fitness.ui.detail.DetailScreen
import com.johnson.fitness.ui.error.ErrorScreen
import com.johnson.fitness.ui.home.HomeScreen
import com.johnson.fitness.ui.playback.PlaybackScreen
import com.johnson.fitness.ui.settings.SettingsScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onMovieClick = { movieId -> navController.navigate("detail/$movieId") },
                onErrorClick = { navController.navigate("error") },
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable(
            route = "detail/{movieId}",
            arguments = listOf(navArgument("movieId") { type = NavType.LongType })
        ) { backStackEntry ->
            val movieId = backStackEntry.arguments?.getLong("movieId") ?: 0L
            DetailScreen(
                movieId = movieId,
                onWatchTrailer = { navController.navigate("playback/$movieId") },
                onRelatedMovieClick = { id -> navController.navigate("detail/$id") },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "playback/{movieId}",
            arguments = listOf(navArgument("movieId") { type = NavType.LongType })
        ) { backStackEntry ->
            val movieId = backStackEntry.arguments?.getLong("movieId") ?: 0L
            PlaybackScreen(
                movieId = movieId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("error") {
            ErrorScreen(onDismiss = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onBluetoothClick = { navController.navigate("bluetooth") }
            )
        }
        composable("bluetooth") {
            BluetoothScreen(onBack = { navController.popBackStack() })
        }
    }
}
