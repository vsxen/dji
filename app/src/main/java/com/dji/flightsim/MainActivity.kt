package com.dji.flightsim

import android.content.Intent
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
import com.dji.flightsim.engine.DjiUsbRcReader
import com.dji.flightsim.engine.GamepadInputHandler
import com.dji.flightsim.ui.screens.FlightScreen
import com.dji.flightsim.ui.screens.MainMenuScreen

class MainActivity : ComponentActivity() {

    // Observable gamepad state shared with Compose
    var gamepadState by mutableStateOf(GamepadInputHandler.GamepadState())
        private set
    var onGamepadMotorToggle: (() -> Unit)? = null
    var onGamepadPause: (() -> Unit)? = null

    // DJI USB RC reader
    private lateinit var djiRcReader: DjiUsbRcReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start DJI USB RC reader
        djiRcReader = DjiUsbRcReader(this)
        djiRcReader.onChannelsUpdated = { channels ->
            // Update gamepad state from DJI RC (runs on background thread, but mutableStateOf is thread-safe)
            gamepadState = GamepadInputHandler.GamepadState(
                leftX = channels.leftX,
                leftY = channels.leftY,
                rightX = channels.rightX,
                rightY = channels.rightY,
                connected = channels.connected
            )
        }
        djiRcReader.start()

        // Handle USB device attached via intent
        handleUsbIntent(intent)

        setContent {
            DJIFlightSimApp(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            // Re-scan for DJI devices when a USB device is attached
            djiRcReader.scanForDevices()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-scan in case device was attached while paused
        djiRcReader.scanForDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        djiRcReader.stop()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Standard gamepad (Bluetooth/USB HID gamepads, Xbox, PS controllers)
        val state = GamepadInputHandler.processMotionEvent(event)
        if (state != null) {
            // Only use standard gamepad if DJI RC is not connected
            if (!djiRcReader.channels.connected) {
                gamepadState = state
            }
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
            // Clear gamepad callbacks when in menu
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
