package com.dji.flightsim.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import com.dji.flightsim.engine.*
import com.dji.flightsim.ui.theme.DJIColors
import kotlin.math.*

@Composable
fun FlightMapView(
    droneState: DroneState,
    terrain: TerrainMap,
    trail: List<Offset>,
    currentWaypointIndex: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasW = size.width
        val canvasH = size.height
        val centerX = canvasW / 2f
        val centerY = canvasH / 2f

        // Scale: pixels per meter. Zoom based on altitude for a nice effect
        val baseScale = minOf(canvasW, canvasH) / 300f
        val altZoomFactor = 1f + (droneState.altitude / 150f).coerceAtMost(1.5f)
        val scale = baseScale / altZoomFactor

        // Camera follows drone
        val camX = droneState.x
        val camY = droneState.y

        fun worldToScreen(wx: Float, wy: Float): Offset {
            return Offset(
                centerX + (wx - camX) * scale,
                centerY - (wy - camY) * scale // Y flipped for screen coords
            )
        }

        // --- Background ---
        drawRect(DJIColors.Ground)

        // --- Grid ---
        val gridSpacing = 50f // meters
        val visibleRange = (canvasW / scale / 2f + gridSpacing).toInt()

        val gridStartX = ((camX - visibleRange) / gridSpacing).toInt() * gridSpacing.toInt()
        val gridStartY = ((camY - visibleRange) / gridSpacing).toInt() * gridSpacing.toInt()

        for (gx in gridStartX..(camX + visibleRange).toInt() step gridSpacing.toInt()) {
            val screenPos = worldToScreen(gx.toFloat(), 0f)
            drawLine(DJIColors.Grid, Offset(screenPos.x, 0f), Offset(screenPos.x, canvasH), strokeWidth = 1f)
        }
        for (gy in gridStartY..(camY + visibleRange).toInt() step gridSpacing.toInt()) {
            val screenPos = worldToScreen(0f, gy.toFloat())
            drawLine(DJIColors.Grid, Offset(0f, screenPos.y), Offset(canvasW, screenPos.y), strokeWidth = 1f)
        }

        // --- Obstacles ---
        for (obs in terrain.obstacles) {
            val pos = worldToScreen(obs.x, obs.y)
            val r = obs.radius * scale

            // Skip if off screen
            if (pos.x < -r || pos.x > canvasW + r || pos.y < -r || pos.y > canvasH + r) continue

            when (obs.type) {
                ObstacleType.BUILDING -> {
                    // Buildings: rectangles with shadow
                    val opacity = if (droneState.altitude > obs.height) 0.5f else 1.0f
                    drawRect(
                        color = DJIColors.Building.copy(alpha = opacity),
                        topLeft = Offset(pos.x - r, pos.y - r),
                        size = Size(r * 2, r * 2)
                    )
                    // Height label shadow effect
                    if (droneState.altitude < obs.height) {
                        val shadowOffset = 3f * scale
                        drawRect(
                            color = DJIColors.BuildingTop.copy(alpha = 0.4f),
                            topLeft = Offset(pos.x - r + shadowOffset, pos.y - r + shadowOffset),
                            size = Size(r * 2, r * 2)
                        )
                    }
                    // Rooftop marker
                    drawRect(
                        color = DJIColors.BuildingTop.copy(alpha = opacity),
                        topLeft = Offset(pos.x - r * 0.3f, pos.y - r * 0.3f),
                        size = Size(r * 0.6f, r * 0.6f)
                    )
                }
                ObstacleType.TREE -> {
                    val opacity = if (droneState.altitude > obs.height) 0.4f else 0.9f
                    drawCircle(
                        color = DJIColors.Tree.copy(alpha = opacity),
                        radius = r,
                        center = pos
                    )
                    // Trunk
                    drawCircle(
                        color = Color(0xFF5D4037).copy(alpha = opacity),
                        radius = r * 0.2f,
                        center = pos
                    )
                }
                ObstacleType.TOWER -> {
                    val opacity = if (droneState.altitude > obs.height) 0.5f else 1f
                    // Tower base
                    drawCircle(
                        color = DJIColors.Tower.copy(alpha = opacity),
                        radius = r,
                        center = pos
                    )
                    // Blinking warning light
                    val blink = (System.currentTimeMillis() / 500) % 2 == 0L
                    if (blink) {
                        drawCircle(
                            color = DJIColors.Danger,
                            radius = r * 0.4f,
                            center = pos
                        )
                    }
                }
                ObstacleType.LANDING_PAD -> {
                    // Landing pad - H shape
                    drawCircle(
                        color = DJIColors.LandingPad.copy(alpha = 0.6f),
                        radius = r * 2f,
                        center = pos
                    )
                    drawCircle(
                        color = DJIColors.LandingPad,
                        radius = r * 2f,
                        center = pos,
                        style = Stroke(width = 2f)
                    )
                    // H letter
                    val hSize = r * 1.2f
                    drawLine(Color.White, Offset(pos.x - hSize, pos.y - hSize), Offset(pos.x - hSize, pos.y + hSize), strokeWidth = 2f)
                    drawLine(Color.White, Offset(pos.x + hSize, pos.y - hSize), Offset(pos.x + hSize, pos.y + hSize), strokeWidth = 2f)
                    drawLine(Color.White, Offset(pos.x - hSize, pos.y), Offset(pos.x + hSize, pos.y), strokeWidth = 2f)
                }
            }
        }

        // --- Waypoints ---
        for ((index, wp) in terrain.waypoints.withIndex()) {
            val pos = worldToScreen(wp.x, wp.y)
            val isReached = index < currentWaypointIndex
            val isCurrent = index == currentWaypointIndex
            val wpColor = if (isReached) DJIColors.WaypointReached
                          else if (isCurrent) DJIColors.Waypoint
                          else DJIColors.Waypoint.copy(alpha = 0.4f)

            // Connect waypoints with lines
            if (index > 0) {
                val prevPos = worldToScreen(terrain.waypoints[index - 1].x, terrain.waypoints[index - 1].y)
                drawLine(
                    color = if (isReached) DJIColors.WaypointReached.copy(alpha = 0.5f) else wpColor.copy(alpha = 0.3f),
                    start = prevPos,
                    end = pos,
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                )
            }

            drawCircle(color = wpColor, radius = 8f, center = pos)
            if (isCurrent) {
                // Pulsing ring for current waypoint
                val pulse = (System.currentTimeMillis() % 1000) / 1000f
                drawCircle(
                    color = DJIColors.Waypoint.copy(alpha = 1f - pulse),
                    radius = 8f + pulse * 12f,
                    center = pos,
                    style = Stroke(width = 2f)
                )
            }
        }

        // --- Flight trail ---
        if (trail.size > 1) {
            for (i in 1 until trail.size) {
                val p1 = worldToScreen(trail[i - 1].x, trail[i - 1].y)
                val p2 = worldToScreen(trail[i].x, trail[i].y)
                val alpha = (i.toFloat() / trail.size) * 0.6f
                drawLine(
                    color = DJIColors.Trail.copy(alpha = alpha),
                    start = p1,
                    end = p2,
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
            }
        }

        // --- Home indicator ---
        val homePos = worldToScreen(droneState.homeX, droneState.homeY)
        if (droneState.distanceToHome > 10f) {
            drawCircle(
                color = DJIColors.Success.copy(alpha = 0.3f),
                radius = 6f,
                center = homePos
            )
        }

        // --- Drone ---
        val dronePos = worldToScreen(droneState.x, droneState.y)
        val headingRad = Math.toRadians(droneState.heading.toDouble()).toFloat()
        val droneSize = 12f

        // Shadow
        drawCircle(
            color = Color.Black.copy(alpha = 0.3f),
            radius = droneSize + 2f,
            center = Offset(dronePos.x + 3f, dronePos.y + 3f)
        )

        // Drone body
        rotate(degrees = droneState.heading, pivot = dronePos) {
            // Arms
            val armLength = droneSize * 1.2f
            val armColor = DJIColors.DroneStroke
            drawLine(armColor, Offset(dronePos.x - armLength, dronePos.y - armLength), Offset(dronePos.x + armLength, dronePos.y + armLength), strokeWidth = 2.5f)
            drawLine(armColor, Offset(dronePos.x + armLength, dronePos.y - armLength), Offset(dronePos.x - armLength, dronePos.y + armLength), strokeWidth = 2.5f)

            // Motors
            val motorPositions = listOf(
                Offset(dronePos.x - armLength, dronePos.y - armLength),
                Offset(dronePos.x + armLength, dronePos.y - armLength),
                Offset(dronePos.x - armLength, dronePos.y + armLength),
                Offset(dronePos.x + armLength, dronePos.y + armLength)
            )
            for (mPos in motorPositions) {
                drawCircle(DJIColors.DroneFill.copy(alpha = 0.3f), radius = 5f, center = mPos)
                drawCircle(DJIColors.DroneFill, radius = 3f, center = mPos)
            }

            // Center body
            drawCircle(DJIColors.DroneFill, radius = droneSize * 0.4f, center = dronePos)

            // Forward direction indicator
            drawLine(
                color = DJIColors.Danger,
                start = dronePos,
                end = Offset(dronePos.x, dronePos.y - droneSize * 1.5f),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }

        // Altitude ring around drone
        if (droneState.altitude > 0.5f) {
            val altRingRadius = droneSize + 4f + (droneState.altitude / 30f).coerceAtMost(8f)
            drawCircle(
                color = DJIColors.Accent.copy(alpha = 0.4f),
                radius = altRingRadius,
                center = dronePos,
                style = Stroke(width = 1.5f)
            )
        }

        // --- Minimap ---
        val minimapSize = 120f
        val minimapPadding = 12f
        val minimapX = canvasW - minimapSize - minimapPadding
        val minimapY = minimapPadding
        val minimapScale = minimapSize / 600f // show 600m range

        // Minimap background
        drawRect(
            color = Color.Black.copy(alpha = 0.7f),
            topLeft = Offset(minimapX, minimapY),
            size = Size(minimapSize, minimapSize)
        )
        drawRect(
            color = DJIColors.Accent.copy(alpha = 0.5f),
            topLeft = Offset(minimapX, minimapY),
            size = Size(minimapSize, minimapSize),
            style = Stroke(width = 1f)
        )

        // Minimap obstacles
        for (obs in terrain.obstacles) {
            val mmX = minimapX + minimapSize / 2f + obs.x * minimapScale
            val mmY = minimapY + minimapSize / 2f - obs.y * minimapScale
            if (mmX in minimapX..minimapX + minimapSize && mmY in minimapY..minimapY + minimapSize) {
                val color = when (obs.type) {
                    ObstacleType.BUILDING -> DJIColors.Building
                    ObstacleType.TREE -> DJIColors.Tree
                    ObstacleType.TOWER -> DJIColors.Tower
                    ObstacleType.LANDING_PAD -> DJIColors.LandingPad
                }
                drawCircle(color.copy(alpha = 0.6f), radius = 2f, center = Offset(mmX, mmY))
            }
        }

        // Minimap drone
        val mmDroneX = minimapX + minimapSize / 2f + droneState.x * minimapScale
        val mmDroneY = minimapY + minimapSize / 2f - droneState.y * minimapScale
        drawCircle(DJIColors.DroneFill, radius = 3f, center = Offset(mmDroneX, mmDroneY))

        // Minimap home
        val mmHomeX = minimapX + minimapSize / 2f + droneState.homeX * minimapScale
        val mmHomeY = minimapY + minimapSize / 2f - droneState.homeY * minimapScale
        drawCircle(DJIColors.Success, radius = 2f, center = Offset(mmHomeX, mmHomeY))

        // --- Distance limit ring on minimap ---
        val limitRadius = FlightPhysicsEngine.MAX_DISTANCE * minimapScale
        drawCircle(
            color = DJIColors.Warning.copy(alpha = 0.3f),
            radius = limitRadius,
            center = Offset(minimapX + minimapSize / 2f, minimapY + minimapSize / 2f),
            style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        )
    }
}
