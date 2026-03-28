package com.dji.flightsim.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dji.flightsim.ui.theme.DJIColors
import kotlin.math.sqrt

/**
 * Virtual joystick component that provides x,y values from -1 to 1.
 * @param onValueChange Called with (x, y) normalized to -1..1
 * @param label Label to show in the center when idle
 */
@Composable
fun VirtualJoystick(
    modifier: Modifier = Modifier,
    onValueChange: (Float, Float) -> Unit,
    returnToCenter: Boolean = true,
    label: String = ""
) {
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    val size = 160.dp

    Canvas(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val center = Offset(this.size.width / 2f, this.size.height / 2f)
                        stickOffset = clampToCircle(offset - center, this.size.width / 2f)
                        val normalized = normalizeStick(stickOffset, this.size.width / 2f)
                        onValueChange(normalized.x, -normalized.y) // invert Y for intuitive control
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val center = Offset(this.size.width / 2f, this.size.height / 2f)
                        stickOffset = clampToCircle(change.position - center, this.size.width / 2f)
                        val normalized = normalizeStick(stickOffset, this.size.width / 2f)
                        onValueChange(normalized.x, -normalized.y)
                    },
                    onDragEnd = {
                        isDragging = false
                        if (returnToCenter) {
                            stickOffset = Offset.Zero
                            onValueChange(0f, 0f)
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        if (returnToCenter) {
                            stickOffset = Offset.Zero
                            onValueChange(0f, 0f)
                        }
                    }
                )
            }
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outerRadius = this.size.width / 2f
        val innerRadius = outerRadius * 0.35f

        // Outer ring
        drawCircle(
            color = DJIColors.SurfaceLight,
            radius = outerRadius,
            center = center
        )
        drawCircle(
            color = if (isDragging) DJIColors.Accent else DJIColors.TextMuted,
            radius = outerRadius,
            center = center,
            style = Stroke(width = 2f)
        )

        // Cross hair
        val crossSize = outerRadius * 0.3f
        val crossColor = DJIColors.TextMuted
        drawLine(crossColor, Offset(center.x - crossSize, center.y), Offset(center.x + crossSize, center.y), strokeWidth = 1f)
        drawLine(crossColor, Offset(center.x, center.y - crossSize), Offset(center.x, center.y + crossSize), strokeWidth = 1f)

        // Stick position
        val stickCenter = center + stickOffset
        drawCircle(
            color = if (isDragging) DJIColors.Accent.copy(alpha = 0.3f) else Color.Transparent,
            radius = innerRadius * 1.2f,
            center = stickCenter
        )
        drawCircle(
            color = if (isDragging) DJIColors.Accent else DJIColors.TextDim,
            radius = innerRadius,
            center = stickCenter
        )
        drawCircle(
            color = Color.White,
            radius = innerRadius,
            center = stickCenter,
            style = Stroke(width = 2f)
        )

        // Direction indicator line
        if (isDragging && stickOffset != Offset.Zero) {
            drawLine(
                color = DJIColors.Accent,
                start = center,
                end = stickCenter,
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun clampToCircle(offset: Offset, maxRadius: Float): Offset {
    val dist = sqrt(offset.x * offset.x + offset.y * offset.y)
    val clampRadius = maxRadius * 0.85f
    return if (dist > clampRadius) {
        val scale = clampRadius / dist
        Offset(offset.x * scale, offset.y * scale)
    } else {
        offset
    }
}

private fun normalizeStick(offset: Offset, maxRadius: Float): Offset {
    val clampRadius = maxRadius * 0.85f
    return Offset(
        (offset.x / clampRadius).coerceIn(-1f, 1f),
        (offset.y / clampRadius).coerceIn(-1f, 1f)
    )
}
