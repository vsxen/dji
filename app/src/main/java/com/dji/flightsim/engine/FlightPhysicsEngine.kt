package com.dji.flightsim.engine

import kotlin.math.*

/**
 * Simplified drone flight physics engine.
 * Simulates position, velocity, rotation with gravity, drag, and thrust.
 */
data class DroneState(
    // Position (meters) - x: east, y: north, z: up (altitude)
    val x: Float = 0f,
    val y: Float = 0f,
    val altitude: Float = 0f,

    // Velocity (m/s)
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vz: Float = 0f,

    // Heading in degrees (0=north, clockwise)
    val heading: Float = 0f,

    // Pitch angle in degrees (positive = forward tilt)
    val pitch: Float = 0f,

    // Roll angle in degrees (positive = right tilt)
    val roll: Float = 0f,

    // Motor state
    val motorsRunning: Boolean = false,
    val batteryPercent: Float = 100f,

    // Flight stats
    val flightTimeSeconds: Float = 0f,
    val maxAltitude: Float = 0f,
    val distanceTraveled: Float = 0f,

    // Home position
    val homeX: Float = 0f,
    val homeY: Float = 0f
) {
    val speed: Float get() = sqrt(vx * vx + vy * vy)
    val speed3D: Float get() = sqrt(vx * vx + vy * vy + vz * vz)
    val distanceToHome: Float get() = sqrt((x - homeX).pow(2) + (y - homeY).pow(2))
    val isOnGround: Boolean get() = altitude <= 0.05f && vz <= 0f
    val isLowBattery: Boolean get() = batteryPercent < 20f
    val isCriticalBattery: Boolean get() = batteryPercent < 10f
}

/**
 * Control inputs from virtual joysticks.
 * All values range from -1.0 to 1.0
 */
data class ControlInput(
    val throttle: Float = 0f,   // Left stick Y: up/down
    val yaw: Float = 0f,        // Left stick X: rotate left/right
    val pitch: Float = 0f,      // Right stick Y: forward/backward
    val roll: Float = 0f        // Right stick X: left/right
)

class FlightPhysicsEngine {

    companion object {
        // Physical constants
        const val GRAVITY = 9.81f             // m/s²
        const val AIR_DRAG = 0.3f             // drag coefficient
        const val VERTICAL_DRAG = 0.5f        // vertical drag coefficient
        const val MAX_THRUST = 20.0f          // max thrust acceleration (m/s²), > gravity for climb
        const val HOVER_THRUST = GRAVITY      // thrust needed to hover

        // Control response rates
        const val MAX_VERTICAL_SPEED = 6.0f   // m/s (DJI Mini 3 Pro: 5m/s ascend)
        const val MAX_HORIZONTAL_SPEED = 16.0f // m/s (DJI Mini 3 Pro: 16m/s)
        const val MAX_YAW_RATE = 100.0f       // degrees/s
        const val MAX_TILT_ANGLE = 35.0f      // degrees

        // Attitude response
        const val TILT_RESPONSE = 5.0f        // how fast drone tilts to target (higher = snappier)
        const val YAW_RESPONSE = 3.0f

        // Battery
        const val BATTERY_DRAIN_HOVER = 0.04f  // %/s while hovering
        const val BATTERY_DRAIN_FLIGHT = 0.08f // %/s while flying
        const val BATTERY_DRAIN_IDLE = 0.005f  // %/s while motors off

        // Limits
        const val MAX_ALTITUDE = 120.0f       // meters (regulatory limit)
        const val MAX_DISTANCE = 500.0f       // meters from home
    }

    fun update(state: DroneState, input: ControlInput, deltaTime: Float): DroneState {
        if (!state.motorsRunning) {
            return state.copy(
                batteryPercent = (state.batteryPercent - BATTERY_DRAIN_IDLE * deltaTime).coerceAtLeast(0f)
            )
        }

        // --- Attitude control ---
        val targetPitch = input.pitch * MAX_TILT_ANGLE
        val targetRoll = input.roll * MAX_TILT_ANGLE
        val newPitch = lerp(state.pitch, targetPitch, TILT_RESPONSE * deltaTime)
        val newRoll = lerp(state.roll, targetRoll, TILT_RESPONSE * deltaTime)

        // Yaw control
        val yawDelta = input.yaw * MAX_YAW_RATE * deltaTime
        val newHeading = (state.heading + yawDelta + 360f) % 360f

        // --- Thrust and movement ---
        val headingRad = Math.toRadians(newHeading.toDouble()).toFloat()
        val pitchRad = Math.toRadians(newPitch.toDouble()).toFloat()
        val rollRad = Math.toRadians(newRoll.toDouble()).toFloat()

        // Horizontal acceleration from tilt
        val horizontalAccelFromPitch = MAX_THRUST * sin(pitchRad)
        val horizontalAccelFromRoll = MAX_THRUST * sin(rollRad)

        // Decompose into world-space x,y using heading
        val ax = horizontalAccelFromPitch * sin(headingRad) + horizontalAccelFromRoll * cos(headingRad)
        val ay = horizontalAccelFromPitch * cos(headingRad) - horizontalAccelFromRoll * sin(headingRad)

        // Vertical: throttle controls climb rate
        val targetVz = input.throttle * MAX_VERTICAL_SPEED
        val az = (targetVz - state.vz) * 3.0f // PD-like vertical speed control

        // Apply drag
        val dragX = -state.vx * AIR_DRAG
        val dragY = -state.vy * AIR_DRAG
        val dragZ = -state.vz * VERTICAL_DRAG

        // Integrate velocity
        var newVx = state.vx + (ax + dragX) * deltaTime
        var newVy = state.vy + (ay + dragY) * deltaTime
        var newVz = state.vz + (az + dragZ) * deltaTime

        // Clamp horizontal speed
        val hSpeed = sqrt(newVx * newVx + newVy * newVy)
        if (hSpeed > MAX_HORIZONTAL_SPEED) {
            val scale = MAX_HORIZONTAL_SPEED / hSpeed
            newVx *= scale
            newVy *= scale
        }
        newVz = newVz.coerceIn(-MAX_VERTICAL_SPEED, MAX_VERTICAL_SPEED)

        // Integrate position
        var newX = state.x + newVx * deltaTime
        var newY = state.y + newVy * deltaTime
        var newAlt = state.altitude + newVz * deltaTime

        // Ground collision
        if (newAlt <= 0f) {
            newAlt = 0f
            newVz = 0f.coerceAtLeast(newVz)
            // Friction on ground
            newVx *= 0.9f
            newVy *= 0.9f
        }

        // Altitude limit
        if (newAlt >= MAX_ALTITUDE) {
            newAlt = MAX_ALTITUDE
            newVz = newVz.coerceAtMost(0f)
        }

        // Battery drain
        val drainRate = if (hSpeed > 1f || abs(newVz) > 0.5f) BATTERY_DRAIN_FLIGHT else BATTERY_DRAIN_HOVER
        val newBattery = (state.batteryPercent - drainRate * deltaTime).coerceAtLeast(0f)

        // Distance traveled
        val dx = newX - state.x
        val dy = newY - state.y
        val segmentDist = sqrt(dx * dx + dy * dy)

        return state.copy(
            x = newX,
            y = newY,
            altitude = newAlt,
            vx = newVx,
            vy = newVy,
            vz = newVz,
            heading = newHeading,
            pitch = newPitch,
            roll = newRoll,
            batteryPercent = newBattery,
            flightTimeSeconds = state.flightTimeSeconds + deltaTime,
            maxAltitude = maxOf(state.maxAltitude, newAlt),
            distanceTraveled = state.distanceTraveled + segmentDist
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return a + (b - a) * clamped
    }
}
