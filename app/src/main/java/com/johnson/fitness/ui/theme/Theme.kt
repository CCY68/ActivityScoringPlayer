package com.johnson.fitness.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Composable
fun DemoFitnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary    = JohnsonColors.Brand,
            onPrimary  = JohnsonColors.Gray0,
            secondary  = JohnsonColors.AccentScore,
            onSecondary= JohnsonColors.Ink1000,
            background = JohnsonColors.BgApp,
            onBackground = JohnsonColors.TextPrimary,
            surface    = JohnsonColors.SurfaceCard,
            onSurface  = JohnsonColors.TextPrimary,
        ),
        content = content
    )
}
