package com.dji.flightsim.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dji.flightsim.engine.*
import com.dji.flightsim.ui.components.*
import com.dji.flightsim.ui.theme.DJIColors
import kotlinx.coroutines.delay

@Composable
fun FlightScreen(
    isTrainingMode: Boolean,
    onBack: () -> Unit
) {
    val physicsEngine = remember { FlightPhysicsEngine() }
    val terrain = remember {
        if (isTrainingMode) TerrainMap.createTrainingCourse()
        else TerrainMap()
    }

    var droneState by remember { mutableStateOf(DroneState()) }
    var controlInput by remember { mutableStateOf(ControlInput()) }
    var trail by remember { mutableStateOf(listOf<Offset>()) }
    var isCollision by remember { mutableStateOf(false) }
    var collisionCooldown by remember { mutableFloatStateOf(0f) }
    var currentWaypointIndex by remember { mutableIntStateOf(0) }
    var showCrashDialog by remember { mutableStateOf(false) }
    var showCompleteDialog by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }

    // Game loop
    LaunchedEffect(isPaused) {
        if (isPaused) return@LaunchedEffect
        var lastTime = System.nanoTime()

        while (true) {
            delay(16) // ~60fps
            val now = System.nanoTime()
            val dt = ((now - lastTime) / 1_000_000_000f).coerceAtMost(0.05f)
            lastTime = now

            if (showCrashDialog || showCompleteDialog) continue

            // Update physics
            val newState = physicsEngine.update(droneState, controlInput, dt)

            // Collision detection
            val collision = terrain.checkCollision(newState)
            if (collision != null && collisionCooldown <= 0f) {
                isCollision = true
                collisionCooldown = 2f

                if (newState.speed3D > 3f) {
                    showCrashDialog = true
                } else {
                    // Soft collision - push back
                    val pushX = newState.x - collision.x
                    val pushY = newState.y - collision.y
                    val pushDist = kotlin.math.sqrt(pushX * pushX + pushY * pushY)
                    if (pushDist > 0) {
                        droneState = newState.copy(
                            x = collision.x + pushX / pushDist * (collision.radius + 1f),
                            y = collision.y + pushY / pushDist * (collision.radius + 1f),
                            vx = 0f, vy = 0f
                        )
                    }
                    continue
                }
            } else {
                isCollision = false
            }
            collisionCooldown = (collisionCooldown - dt).coerceAtLeast(0f)

            // Waypoint checking
            if (isTrainingMode && currentWaypointIndex < terrain.waypoints.size) {
                if (terrain.checkWaypointReached(newState, currentWaypointIndex)) {
                    currentWaypointIndex++
                    if (currentWaypointIndex >= terrain.waypoints.size) {
                        showCompleteDialog = true
                    }
                }
            }

            // Battery death
            if (newState.batteryPercent <= 0f) {
                showCrashDialog = true
            }

            droneState = newState

            // Trail (record every few frames to save memory)
            if (trail.isEmpty() || run {
                    val last = trail.last()
                    val dx = last.x - newState.x
                    val dy = last.y - newState.y
                    dx * dx + dy * dy > 4f
                }) {
                trail = (trail + Offset(newState.x, newState.y)).takeLast(500)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(DJIColors.Background)) {
        // Flight map
        FlightMapView(
            droneState = droneState,
            terrain = terrain,
            trail = trail,
            currentWaypointIndex = currentWaypointIndex
        )

        // HUD overlay
        HudOverlay(
            droneState = droneState,
            isCollision = isCollision
        )

        // Left joystick - throttle (Y) and yaw (X)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 24.dp)
        ) {
            VirtualJoystick(
                onValueChange = { x, y ->
                    controlInput = controlInput.copy(yaw = x, throttle = y)
                }
            )
        }

        // Right joystick - pitch (Y) and roll (X)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp)
        ) {
            VirtualJoystick(
                onValueChange = { x, y ->
                    controlInput = controlInput.copy(roll = x, pitch = y)
                }
            )
        }

        // Motor toggle button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            MotorButton(
                isRunning = droneState.motorsRunning,
                onClick = {
                    droneState = droneState.copy(motorsRunning = !droneState.motorsRunning)
                }
            )
        }

        // Back button
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onBack() }
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = DJIColors.Text,
                modifier = Modifier.size(20.dp)
            )
        }

        // Pause button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 140.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { isPaused = !isPaused }
                .padding(8.dp)
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (isPaused) "Resume" else "Pause",
                tint = DJIColors.Text,
                modifier = Modifier.size(20.dp)
            )
        }

        // Waypoint indicator for training mode
        if (isTrainingMode && currentWaypointIndex < terrain.waypoints.size) {
            val wp = terrain.waypoints[currentWaypointIndex]
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DJIColors.Waypoint.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "WP ${currentWaypointIndex + 1}/${terrain.waypoints.size} → " +
                            "X:${wp.x.toInt()} Y:${wp.y.toInt()} ALT:${wp.altitude.toInt()}m",
                    color = DJIColors.Waypoint,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Crash dialog
        if (showCrashDialog) {
            DialogOverlay(
                title = "CRASH!",
                message = "Flight time: ${formatFlightTime(droneState.flightTimeSeconds)}\n" +
                        "Max altitude: ${droneState.maxAltitude.toInt()}m\n" +
                        "Distance traveled: ${droneState.distanceTraveled.toInt()}m",
                buttonText = "RESTART",
                titleColor = DJIColors.Danger,
                onAction = {
                    droneState = DroneState()
                    controlInput = ControlInput()
                    trail = emptyList()
                    currentWaypointIndex = 0
                    showCrashDialog = false
                    isCollision = false
                },
                onBack = onBack
            )
        }

        // Training complete dialog
        if (showCompleteDialog) {
            DialogOverlay(
                title = "MISSION COMPLETE!",
                message = "Flight time: ${formatFlightTime(droneState.flightTimeSeconds)}\n" +
                        "Battery remaining: ${droneState.batteryPercent.toInt()}%\n" +
                        "Distance traveled: ${droneState.distanceTraveled.toInt()}m",
                buttonText = "RESTART",
                titleColor = DJIColors.Success,
                onAction = {
                    droneState = DroneState()
                    controlInput = ControlInput()
                    trail = emptyList()
                    currentWaypointIndex = 0
                    showCompleteDialog = false
                },
                onBack = onBack
            )
        }

        // Pause overlay
        if (isPaused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PAUSED",
                    color = DJIColors.Accent,
                    fontSize = 32.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MotorButton(
    isRunning: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isRunning) DJIColors.Success.copy(alpha = 0.2f)
                else DJIColors.Surface
            )
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Filled.FlightTakeoff else Icons.Filled.FlightLand,
                contentDescription = null,
                tint = if (isRunning) DJIColors.Success else DJIColors.TextDim,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (isRunning) "MOTORS ON" else "START MOTORS",
                color = if (isRunning) DJIColors.Success else DJIColors.TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DialogOverlay(
    title: String,
    message: String,
    buttonText: String,
    titleColor: Color,
    onAction: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(DJIColors.Surface)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = message,
                color = DJIColors.TextDim,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DJIColors.SurfaceLight)
                        .clickable { onBack() }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("MENU", color = DJIColors.TextDim, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(titleColor.copy(alpha = 0.3f))
                        .clickable { onAction() }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(buttonText, color = titleColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatFlightTime(seconds: Float): String {
    val mins = (seconds / 60).toInt()
    val secs = (seconds % 60).toInt()
    return "${mins}m ${secs}s"
}
