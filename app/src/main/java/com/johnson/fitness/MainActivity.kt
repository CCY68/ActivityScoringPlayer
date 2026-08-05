package com.johnson.fitness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.johnson.fitness.navigation.AppNavigation
import com.johnson.fitness.ui.theme.DemoFitnessTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DemoFitnessTheme {
                AppNavigation()
            }
        }
    }
}
