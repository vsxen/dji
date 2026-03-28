package com.dji.flightsim.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dji.flightsim.engine.DroneState
import com.dji.flightsim.ui.theme.DJIColors
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HudOverlay(
    droneState: DroneState,
    isCollision: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Top bar - telemetry data
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left top: altitude and vertical speed
            Column {
                TelemetryItem("ALT", "${droneState.altitude.roundToInt()}m",
                    if (droneState.altitude > 100f) DJIColors.Warning else DJIColors.Accent)
                TelemetryItem("V/S", "${formatVSpeed(droneState.vz)}m/s",
                    if (abs(droneState.vz) > 4f) DJIColors.Warning else DJIColors.Text)
            }

            // Center top: mode indicator
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val modeText = when {
                    !droneState.motorsRunning -> "MOTORS OFF"
                    droneState.isOnGround -> "GROUND"
                    droneState.altitude < 2f -> "TAKEOFF"
                    else -> "GPS"
                }
                val modeColor = when {
                    !droneState.motorsRunning -> DJIColors.TextDim
                    isCollision -> DJIColors.Danger
                    droneState.isCriticalBattery -> DJIColors.Danger
                    droneState.isLowBattery -> DJIColors.Warning
                    else -> DJIColors.Success
                }
                HudBadge(modeText, modeColor)

                if (isCollision) {
                    Spacer(Modifier.height(4.dp))
                    HudBadge("COLLISION!", DJIColors.Danger)
                }
            }

            // Right top: speed and distance
            Column(horizontalAlignment = Alignment.End) {
                TelemetryItem("SPD", "${droneState.speed.roundToInt()}m/s", DJIColors.Accent)
                TelemetryItem("DIST", "${droneState.distanceToHome.roundToInt()}m",
                    if (droneState.distanceToHome > 400f) DJIColors.Warning else DJIColors.Text)
            }
        }

        // Bottom center: compass heading
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CompassDisplay(heading = droneState.heading)
        }

        // Left side: battery + flight time
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            BatteryIndicator(droneState.batteryPercent)
            TelemetryItem("TIME", formatTime(droneState.flightTimeSeconds), DJIColors.TextDim)
        }

        // Right side: attitude indicator
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TelemetryItem("HDG", "${droneState.heading.roundToInt()}°", DJIColors.TextDim)
            TelemetryItem("PITCH", "${droneState.pitch.roundToInt()}°", DJIColors.TextDim)
            TelemetryItem("ROLL", "${droneState.roll.roundToInt()}°", DJIColors.TextDim)
        }
    }
}

@Composable
private fun TelemetryItem(label: String, value: String, valueColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = DJIColors.TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HudBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BatteryIndicator(percent: Float) {
    val color = when {
        percent < 10f -> DJIColors.Danger
        percent < 20f -> DJIColors.Warning
        else -> DJIColors.Success
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Battery icon (simplified)
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DJIColors.SurfaceLight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percent / 100f)
                    .background(color)
            )
        }
        Text(
            text = "${percent.roundToInt()}%",
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CompassDisplay(heading: Float) {
    val direction = when {
        heading < 22.5f || heading >= 337.5f -> "N"
        heading < 67.5f -> "NE"
        heading < 112.5f -> "E"
        heading < 157.5f -> "SE"
        heading < 202.5f -> "S"
        heading < 247.5f -> "SW"
        heading < 292.5f -> "W"
        else -> "NW"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = "${heading.roundToInt()}° $direction",
            color = DJIColors.Accent,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatVSpeed(vz: Float): String {
    val sign = if (vz >= 0) "+" else ""
    return "$sign${(vz * 10).roundToInt() / 10f}"
}

private fun formatTime(seconds: Float): String {
    val mins = (seconds / 60).toInt()
    val secs = (seconds % 60).toInt()
    return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
}
