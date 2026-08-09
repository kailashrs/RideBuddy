package com.spaceboy.ridebuddy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class LineChartScalePolicy {
    ZeroBased,
    AutoRange,
}

internal data class LineChartPoint(
    val x: Float,
    val y: Float,
)

internal fun lineChartPoints(
    values: List<Double>,
    width: Float,
    height: Float,
    scalePolicy: LineChartScalePolicy,
    clampNegativeValues: Boolean,
): List<LineChartPoint> {
    if (values.isEmpty()) return emptyList()

    val valueToY: (Double) -> Float = when (scalePolicy) {
        LineChartScalePolicy.ZeroBased -> {
            val maximum = values.maxOrNull()?.takeIf { it > 0.0 }
                ?: if (values.size == 1) 1.0 else return emptyList()
            val transform: (Double) -> Float = { value ->
                val plottedValue = if (clampNegativeValues) value.coerceAtLeast(0.0) else value
                height - (plottedValue / maximum * height).toFloat()
            }
            transform
        }
        LineChartScalePolicy.AutoRange -> {
            val minimum = values.minOrNull() ?: return emptyList()
            val maximum = values.maxOrNull() ?: return emptyList()
            val range = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
            val transform: (Double) -> Float = { value ->
                height - ((value - minimum) / range * height).toFloat()
            }
            transform
        }
    }
    val dx = if (values.size == 1) 0f else width / values.lastIndex
    return values.mapIndexed { index, value ->
        LineChartPoint(
            x = if (values.size == 1) width / 2f else index * dx,
            y = if (values.size == 1) height / 2f else valueToY(value),
        )
    }
}

@Composable
internal fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    topPadding: Dp = 0.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String = "Line chart",
    scalePolicy: LineChartScalePolicy = LineChartScalePolicy.AutoRange,
    clampNegativeValues: Boolean = false,
    smooth: Boolean = true,
    strokeWidth: Float = 3f,
    fillAlpha: Float? = 0.3f,
    drawBaseline: Boolean = false,
    baselineColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(top = topPadding)
            .semantics { this.contentDescription = contentDescription }
    ) {
        if (drawBaseline) {
            drawLine(baselineColor, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        }
        val points = lineChartPoints(
            values = values,
            width = size.width,
            height = size.height,
            scalePolicy = scalePolicy,
            clampNegativeValues = clampNegativeValues,
        )
        if (points.isEmpty()) return@Canvas
        if (points.size == 1) {
            drawCircle(
                color = color,
                radius = strokeWidth.coerceAtLeast(3f),
                center = Offset(points.single().x, points.single().y),
            )
            return@Canvas
        }
        val path = Path()
        var previousX = points.first().x
        var previousY = points.first().y
        path.moveTo(previousX, previousY)

        for (i in 1 until points.size) {
            val (x, y) = points[i]
            if (smooth) {
                val controlX1 = previousX + (x - previousX) / 2f
                val controlX2 = previousX + (x - previousX) / 2f
                path.cubicTo(controlX1, previousY, controlX2, y, x, y)
            } else {
                path.lineTo(x, y)
            }
            previousX = x
            previousY = y
        }

        drawPath(path, color, style = Stroke(strokeWidth))

        if (fillAlpha != null) {
            val fillPath = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = fillAlpha), Color.Transparent),
                    startY = 0f,
                    endY = size.height
                )
            )
        }
    }
}
