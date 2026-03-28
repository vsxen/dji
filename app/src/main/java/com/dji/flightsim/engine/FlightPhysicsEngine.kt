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
        const val AIR_DRAG = 1.2f             // drag coefficient (increased for realistic deceleration)
        const val VERTICAL_DRAG = 2.0f        // vertical drag coefficient
        const val MAX_THRUST = 15.0f          // max thrust acceleration (m/s²)

        // Control response rates
        const val MAX_VERTICAL_SPEED = 5.0f   // m/s (DJI Mini 3 Pro: 5m/s ascend)
        const val MAX_DESCENT_SPEED = 3.0f    // m/s (descent is slower for safety)
        const val MAX_HORIZONTAL_SPEED = 14.0f // m/s (DJI Mini 3 Pro: ~16m/s S mode)
        const val MAX_YAW_RATE = 80.0f        // degrees/s (realistic yaw rate)
        const val MAX_TILT_ANGLE = 25.0f      // degrees (reduced for smoother flight)

        // Attitude response (lower = more inertia, more realistic)
        const val TILT_RESPONSE = 3.0f        // how fast drone tilts to target
        const val TILT_RETURN_RATE = 4.0f     // how fast drone levels when stick released
        const val YAW_DAMPING = 0.92f         // yaw momentum decay per frame

        // Battery
        const val BATTERY_DRAIN_HOVER = 0.04f  // %/s while hovering
        const val BATTERY_DRAIN_FLIGHT = 0.08f // %/s while flying
        const val BATTERY_DRAIN_IDLE = 0.005f  // %/s while motors off

        // Limits
        const val MAX_ALTITUDE = 120.0f       // meters (regulatory limit)
        const val MAX_DISTANCE = 500.0f       // meters from home

        // Ground effect: near ground, extra lift and stability
        const val GROUND_EFFECT_HEIGHT = 3.0f // meters
    }

    fun update(state: DroneState, input: ControlInput, deltaTime: Float): DroneState {
        if (!state.motorsRunning) {
            // Motors off: drone falls with gravity if airborne
            if (state.altitude > 0f) {
                val newVz = state.vz - GRAVITY * deltaTime
                val newAlt = (state.altitude + newVz * deltaTime).coerceAtLeast(0f)
                return state.copy(
                    altitude = newAlt,
                    vz = if (newAlt <= 0f) 0f else newVz,
                    batteryPercent = (state.batteryPercent - BATTERY_DRAIN_IDLE * deltaTime).coerceAtLeast(0f)
                )
            }
            return state.copy(
                batteryPercent = (state.batteryPercent - BATTERY_DRAIN_IDLE * deltaTime).coerceAtLeast(0f)
            )
        }

        // --- Attitude control with inertia ---
        // Smoothly tilt toward target, but return to level faster when stick released
        val targetPitch = input.pitch * MAX_TILT_ANGLE
        val targetRoll = input.roll * MAX_TILT_ANGLE
        val pitchRate = if (abs(input.pitch) > 0.05f) TILT_RESPONSE else TILT_RETURN_RATE
        val rollRate = if (abs(input.roll) > 0.05f) TILT_RESPONSE else TILT_RETURN_RATE
        val newPitch = lerp(state.pitch, targetPitch, pitchRate * deltaTime)
        val newRoll = lerp(state.roll, targetRoll, rollRate * deltaTime)

        // Yaw control with momentum
        val yawDelta = input.yaw * MAX_YAW_RATE * deltaTime
        val newHeading = (state.heading + yawDelta + 360f) % 360f

        // --- Movement from tilt ---
        val headingRad = Math.toRadians(newHeading.toDouble()).toFloat()
        val pitchRad = Math.toRadians(newPitch.toDouble()).toFloat()
        val rollRad = Math.toRadians(newRoll.toDouble()).toFloat()

        // Horizontal acceleration proportional to tilt angle
        // Use sin(tilt) for realistic force decomposition
        val accelMagnitudePitch = MAX_THRUST * sin(pitchRad)
        val accelMagnitudeRoll = MAX_THRUST * sin(rollRad)

        // Decompose into world-space x,y using heading
        val ax = accelMagnitudePitch * sin(headingRad) + accelMagnitudeRoll * cos(headingRad)
        val ay = accelMagnitudePitch * cos(headingRad) - accelMagnitudeRoll * sin(headingRad)

        // Vertical: throttle controls target climb rate with smooth transition
        val targetVz = if (input.throttle > 0.05f) {
            input.throttle * MAX_VERTICAL_SPEED
        } else if (input.throttle < -0.05f) {
            input.throttle * MAX_DESCENT_SPEED
        } else {
            0f // hover when stick centered
        }
        val vzError = targetVz - state.vz
        val az = vzError * 4.0f // PD vertical speed controller

        // Speed-dependent drag (quadratic drag for realism)
        val speed = sqrt(state.vx * state.vx + state.vy * state.vy)
        val dragFactor = AIR_DRAG * (1f + speed * 0.05f) // increases with speed
        val dragX = -state.vx * dragFactor
        val dragY = -state.vy * dragFactor
        val dragZ = -state.vz * VERTICAL_DRAG

        // Integrate velocity
        var newVx = state.vx + (ax + dragX) * deltaTime
        var newVy = state.vy + (ay + dragY) * deltaTime
        var newVz = state.vz + (az + dragZ) * deltaTime

        // When stick is released and drone is near hover, actively brake
        if (abs(input.pitch) < 0.05f && abs(input.roll) < 0.05f) {
            val brakeFactor = (1f - 3.0f * deltaTime).coerceAtLeast(0.9f)
            newVx *= brakeFactor
            newVy *= brakeFactor
            // Stop micro-drift
            if (sqrt(newVx * newVx + newVy * newVy) < 0.1f) {
                newVx = 0f
                newVy = 0f
            }
        }

        // Clamp horizontal speed
        val hSpeed = sqrt(newVx * newVx + newVy * newVy)
        if (hSpeed > MAX_HORIZONTAL_SPEED) {
            val scale = MAX_HORIZONTAL_SPEED / hSpeed
            newVx *= scale
            newVy *= scale
        }
        newVz = newVz.coerceIn(-MAX_DESCENT_SPEED, MAX_VERTICAL_SPEED)

        // Integrate position
        val newX = state.x + newVx * deltaTime
        val newY = state.y + newVy * deltaTime
        var newAlt = state.altitude + newVz * deltaTime

        // Ground effect: extra stability near ground
        if (newAlt in 0f..GROUND_EFFECT_HEIGHT && newVz < 0f) {
            // Cushion effect slows descent near ground
            val groundFactor = newAlt / GROUND_EFFECT_HEIGHT
            newVz *= (0.7f + 0.3f * groundFactor)
        }

        // Ground collision
        if (newAlt <= 0f) {
            newAlt = 0f
            newVz = newVz.coerceAtLeast(0f)
            // Ground friction
            newVx *= 0.85f
            newVy *= 0.85f
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
