package com.dji.flightsim

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dji.flightsim.engine.GamepadInputHandler
import com.dji.flightsim.ui.screens.FlightScreen
import com.dji.flightsim.ui.screens.MainMenuScreen

class MainActivity : ComponentActivity() {

    var gamepadState by mutableStateOf(GamepadInputHandler.GamepadState())
        private set
    var onGamepadMotorToggle: (() -> Unit)? = null
    var onGamepadPause: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            DJIFlightSimApp(this)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val state = GamepadInputHandler.processMotionEvent(event)
        if (state != null) {
            gamepadState = state
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            if (GamepadInputHandler.isMotorToggleButton(event)) {
                onGamepadMotorToggle?.invoke()
                return true
            }
            if (GamepadInputHandler.isPauseButton(event)) {
                onGamepadPause?.invoke()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

enum class Screen {
    MENU, FREE_FLIGHT, TRAINING, FIGURE_8
}

@Composable
fun DJIFlightSimApp(activity: MainActivity) {
    var currentScreen by remember { mutableStateOf(Screen.MENU) }

    when (currentScreen) {
        Screen.MENU -> {
            activity.onGamepadMotorToggle = null
            activity.onGamepadPause = null
            MainMenuScreen(
                onStartFreeFlight = { currentScreen = Screen.FREE_FLIGHT },
                onStartTraining = { currentScreen = Screen.TRAINING },
                onStartFigure8 = { currentScreen = Screen.FIGURE_8 }
            )
        }
        Screen.FREE_FLIGHT -> {
            FlightScreen(
                isTrainingMode = false,
                gamepadState = activity.gamepadState,
                onSetGamepadCallbacks = { motorToggle, pause ->
                    activity.onGamepadMotorToggle = motorToggle
                    activity.onGamepadPause = pause
                },
                onBack = { currentScreen = Screen.MENU }
            )
        }
        Screen.TRAINING -> {
            FlightScreen(
                isTrainingMode = true,
                gamepadState = activity.gamepadState,
                onSetGamepadCallbacks = { motorToggle, pause ->
                    activity.onGamepadMotorToggle = motorToggle
                    activity.onGamepadPause = pause
                },
                onBack = { currentScreen = Screen.MENU }
            )
        }
        Screen.FIGURE_8 -> {
            FlightScreen(
                isTrainingMode = true,
                isFigure8Mode = true,
                gamepadState = activity.gamepadState,
                onSetGamepadCallbacks = { motorToggle, pause ->
                    activity.onGamepadMotorToggle = motorToggle
                    activity.onGamepadPause = pause
                },
                onBack = { currentScreen = Screen.MENU }
            )
        }
    }
}
