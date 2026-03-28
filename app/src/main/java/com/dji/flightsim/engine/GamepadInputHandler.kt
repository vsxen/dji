package com.dji.flightsim.engine

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Handles gamepad/joystick input from physical controllers.
 * Supports DJI RC (via USB simulator mode), Xbox, PlayStation, and generic gamepads.
 *
 * DJI RC-N1/RC Pro in simulator mode maps to standard Android gamepad axes:
 * - Left stick:  AXIS_X (yaw), AXIS_Y (throttle)
 * - Right stick: AXIS_Z (roll), AXIS_RZ (pitch)
 *
 * Standard gamepad mapping (Xbox/PS layout):
 * - Left stick:  AXIS_X, AXIS_Y
 * - Right stick: AXIS_Z (or AXIS_RX), AXIS_RZ (or AXIS_RY)
 */
class GamepadInputHandler {

    data class GamepadState(
        val leftX: Float = 0f,   // yaw
        val leftY: Float = 0f,   // throttle
        val rightX: Float = 0f,  // roll
        val rightY: Float = 0f,  // pitch
        val connected: Boolean = false
    )

    companion object {
        // Dead zone to prevent stick drift
        private const val DEAD_ZONE = 0.08f

        fun isGamepad(device: InputDevice?): Boolean {
            device ?: return false
            val sources = device.sources
            return (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                   (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }

        fun processMotionEvent(event: MotionEvent): GamepadState? {
            if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
                return null
            }

            // Left stick
            val leftX = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_X))
            val leftY = applyDeadZone(event.getAxisValue(MotionEvent.AXIS_Y))

            // Right stick - try AXIS_Z/AXIS_RZ first (most gamepads), fall back to AXIS_RX/AXIS_RY
            var rightX = event.getAxisValue(MotionEvent.AXIS_Z)
            var rightY = event.getAxisValue(MotionEvent.AXIS_RZ)

            // Some controllers use RX/RY instead of Z/RZ
            if (rightX == 0f && rightY == 0f) {
                rightX = event.getAxisValue(MotionEvent.AXIS_RX)
                rightY = event.getAxisValue(MotionEvent.AXIS_RY)
            }

            rightX = applyDeadZone(rightX)
            rightY = applyDeadZone(rightY)

            return GamepadState(
                leftX = leftX,
                leftY = -leftY,    // Invert Y: stick up = positive (climb)
                rightX = rightX,
                rightY = -rightY,  // Invert Y: stick up = positive (forward)
                connected = true
            )
        }

        /**
         * Handle key events from gamepad buttons.
         * Returns true if the event was consumed.
         */
        fun isMotorToggleButton(event: KeyEvent): Boolean {
            // Start/Options button or A button to toggle motors
            return event.action == KeyEvent.ACTION_DOWN &&
                   (event.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                    event.keyCode == KeyEvent.KEYCODE_BUTTON_A)
        }

        fun isPauseButton(event: KeyEvent): Boolean {
            return event.action == KeyEvent.ACTION_DOWN &&
                   (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                    event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE)
        }

        fun hasGamepadConnected(): Boolean {
            val deviceIds = InputDevice.getDeviceIds()
            return deviceIds.any { id ->
                val device = InputDevice.getDevice(id)
                isGamepad(device)
            }
        }

        fun getGamepadName(): String? {
            val deviceIds = InputDevice.getDeviceIds()
            for (id in deviceIds) {
                val device = InputDevice.getDevice(id)
                if (isGamepad(device)) {
                    return device?.name
                }
            }
            return null
        }

        private fun applyDeadZone(value: Float): Float {
            return if (abs(value) < DEAD_ZONE) 0f else value
        }
    }
}
