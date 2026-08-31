package com.spaceboy.ridebuddy.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How a chart's vertical axis is scaled. */
internal enum class LineChartScalePolicy {
    /**
     * Baseline at zero. Right for magnitudes like speed or RPM, where the distance from
     * zero is the point and an auto-ranged axis would exaggerate small variation.
     */
    ZeroBased,

    /** Fits the data's own range. Right for values that vary within a narrow band. */
    AutoRange,
}

/** A point in normalised chart space: both axes in 0..1, independent of pixel size. */
internal data class LineChartPoint(
    val x: Float,
    val y: Float,
)

/**
 * Converts values into drawable segments, splitting at gaps.
 *
 * A null or non-finite value ends the current segment and starts a new one, so a gap in the
 * data is drawn as a gap rather than a straight line bridging it — which would invent a
 * reading that was never recorded.
 *
 * The geometry is pure and separated from drawing so it can be unit-tested, and so it is
 * recomputed only when the data changes rather than on every layout pass. Degenerate inputs
 * are handled explicitly: a single point is centred, since it has no range to scale within.
 */
internal fun lineChartSegments(
    values: List<Double?>,
    width: Float,
    height: Float,
    scalePolicy: LineChartScalePolicy,
    clampNegativeValues: Boolean,
): List<List<LineChartPoint>> {
    val validValues = values.mapNotNull { it?.takeIf(Double::isFinite) }
    if (validValues.isEmpty()) return emptyList()

    val valueToY: (Double) -> Float = when (scalePolicy) {
        LineChartScalePolicy.ZeroBased -> {
            val maximum = validValues.maxOrNull()?.takeIf { it > 0.0 }
                ?: if (validValues.size == 1) 1.0 else return emptyList()
            val transform: (Double) -> Float = { value ->
                val plottedValue = if (clampNegativeValues) value.coerceAtLeast(0.0) else value
                height - (plottedValue / maximum * height).toFloat()
            }
            transform
        }
        LineChartScalePolicy.AutoRange -> {
            val minimum = validValues.minOrNull() ?: return emptyList()
            val maximum = validValues.maxOrNull() ?: return emptyList()
            val range = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
            val transform: (Double) -> Float = { value ->
                height - ((value - minimum) / range * height).toFloat()
            }
            transform
        }
    }
    val dx = if (values.size == 1) 0f else width / values.lastIndex
    val segments = mutableListOf<List<LineChartPoint>>()
    var segment = mutableListOf<LineChartPoint>()
    values.forEachIndexed { index, nullableValue ->
        val value = nullableValue?.takeIf(Double::isFinite)
        if (value == null) {
            if (segment.isNotEmpty()) segments += segment
            segment = mutableListOf()
        } else {
            segment += LineChartPoint(
                x = if (values.size == 1) width / 2f else index * dx,
                y = if (validValues.size == 1) height / 2f else valueToY(value),
            )
        }
    }
    if (segment.isNotEmpty()) segments += segment
    return segments
}

@Composable
internal fun LineChart(
    values: List<Double?>,
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
    // Geometry is normalized once per data change and shared with the unit-tested geometry path.
    // drawWithCache then scales it and builds Paths only when data, style, or size changes.
    val normalizedSegments = remember(values, scalePolicy, clampNegativeValues) {
        lineChartSegments(
            values = values,
            width = 1f,
            height = 1f,
            scalePolicy = scalePolicy,
            clampNegativeValues = clampNegativeValues,
        )
    }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(top = topPadding)
            .semantics { this.contentDescription = contentDescription }
            .drawWithCache {
                val renderedSegments = normalizedSegments.mapNotNull { points ->
                    points.takeIf { it.isNotEmpty() }?.toRenderedSegment(
                        width = size.width,
                        height = size.height,
                        smooth = smooth,
                        includeFill = fillAlpha != null,
                    )
                }
                val fillBrush = fillAlpha?.let { alpha ->
                    Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        startY = 0f,
                        endY = size.height,
                    )
                }
                onDrawBehind {
                    if (drawBaseline) {
                        drawLine(baselineColor, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                    }
                    renderedSegments.forEach { segment ->
                        segment.point?.let { point ->
                            drawCircle(color, radius = strokeWidth.coerceAtLeast(3f), center = point)
                        }
                        segment.linePath?.let { path -> drawPath(path, color, style = Stroke(strokeWidth)) }
                        if (fillBrush != null) {
                            segment.fillPath?.let { path -> drawPath(path, fillBrush) }
                        }
                    }
                }
            },
    )
}

/** A segment scaled to pixels. Either a single [point], or the line and its fill. */
private data class RenderedLineChartSegment(
    val point: Offset? = null,
    val linePath: Path? = null,
    val fillPath: Path? = null,
)

/**
 * Scales a normalised segment to pixels and builds its paths.
 *
 * Smoothing uses cubic curves with control points at the horizontal midpoint between
 * samples, which rounds the line without letting it overshoot past the values it connects —
 * important for telemetry, where an overshoot would imply a speed that was never reached.
 */
private fun List<LineChartPoint>.toRenderedSegment(
    width: Float,
    height: Float,
    smooth: Boolean,
    includeFill: Boolean,
): RenderedLineChartSegment {
    val first = first().scaled(width, height)
    if (size == 1) return RenderedLineChartSegment(point = first)

    var previous = first
    val linePath = Path().apply { moveTo(first.x, first.y) }
    for (index in 1..lastIndex) {
        val current = this[index].scaled(width, height)
        if (smooth) {
            val controlX = previous.x + (current.x - previous.x) / 2f
            linePath.cubicTo(controlX, previous.y, controlX, current.y, current.x, current.y)
        } else {
            linePath.lineTo(current.x, current.y)
        }
        previous = current
    }
    val fillPath = if (includeFill) {
        Path().apply {
            addPath(linePath)
            lineTo(previous.x, height)
            lineTo(first.x, height)
            close()
        }
    } else null
    return RenderedLineChartSegment(linePath = linePath, fillPath = fillPath)
}

private fun LineChartPoint.scaled(width: Float, height: Float): Offset = Offset(x * width, y * height)
