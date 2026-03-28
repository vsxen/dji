package com.dji.flightsim

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dji.flightsim.ui.screens.FlightScreen
import com.dji.flightsim.ui.screens.MainMenuScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            DJIFlightSimApp()
        }
    }
}

enum class Screen {
    MENU, FREE_FLIGHT, TRAINING
}

@Composable
fun DJIFlightSimApp() {
    var currentScreen by remember { mutableStateOf(Screen.MENU) }

    when (currentScreen) {
        Screen.MENU -> {
            MainMenuScreen(
                onStartFreeFlight = { currentScreen = Screen.FREE_FLIGHT },
                onStartTraining = { currentScreen = Screen.TRAINING }
            )
        }
        Screen.FREE_FLIGHT -> {
            FlightScreen(
                isTrainingMode = false,
                onBack = { currentScreen = Screen.MENU }
            )
        }
        Screen.TRAINING -> {
            FlightScreen(
                isTrainingMode = true,
                onBack = { currentScreen = Screen.MENU }
            )
        }
    }
}
