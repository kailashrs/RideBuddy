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
 * recomputed only when the data changes rather than on every layout pass. A single sample
 * is horizontally centred; its height still follows the zero-based scale.
 */
internal fun lineChartSegments(
    values: List<Double?>,
    width: Float,
    height: Float,
    timestampsMillis: List<Long>? = null,
): List<List<LineChartPoint>> {
    require(timestampsMillis == null || timestampsMillis.size == values.size)
    val validValues = values.mapNotNull { it?.takeIf(Double::isFinite) }
    if (validValues.isEmpty()) return emptyList()

    // Every consumer plots a nonnegative magnitude: speed, RPM or throttle.
    val maximum = validValues.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val valueToY: (Double) -> Float = { value ->
        height - (value.coerceAtLeast(0.0) / maximum * height).toFloat()
    }
    val dx = if (values.size == 1) 0f else width / values.lastIndex
    val timeStart = timestampsMillis?.firstOrNull()
    val timeSpan = if (timeStart != null) (requireNotNull(timestampsMillis).last() - timeStart).takeIf { it > 0 } else null
    val segments = mutableListOf<List<LineChartPoint>>()
    var segment = mutableListOf<LineChartPoint>()
    values.forEachIndexed { index, nullableValue ->
        val value = nullableValue?.takeIf(Double::isFinite)
        if (value == null) {
            if (segment.isNotEmpty()) segments += segment
            segment = mutableListOf()
        } else {
            segment += LineChartPoint(
                x = if (timeSpan != null && timeStart != null) {
                    ((requireNotNull(timestampsMillis)[index] - timeStart).toDouble() / timeSpan * width).toFloat().coerceIn(0f, width)
                } else if (values.size == 1) width / 2f else index * dx,
                y = valueToY(value),
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
    timestampsMillis: List<Long>? = null,
    height: Dp = 100.dp,
    topPadding: Dp = 0.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String = "Line chart",
    strokeWidth: Float = 3f,
    fillAlpha: Float? = 0.3f,
    drawBaseline: Boolean = false,
    baselineColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    // Geometry is normalized once per data change and shared with the unit-tested geometry path.
    // drawWithCache then scales it and builds Paths only when data, style, or size changes.
    val normalizedSegments = remember(values, timestampsMillis) {
        lineChartSegments(
            values = values,
            width = 1f,
            height = 1f,
            timestampsMillis = timestampsMillis,
        )
    }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(top = topPadding)
            .semantics { this.contentDescription = contentDescription }
            .drawWithCache {
                // Inset every extremum and endpoint so thick strokes and dots are never clipped.
                val inset = (strokeWidth.coerceAtLeast(3f) + 1f).coerceAtMost(minOf(size.width, size.height) / 2f)
                val renderedSegments = normalizedSegments.mapNotNull { points ->
                    points.takeIf { it.isNotEmpty() }?.map { point ->
                        LineChartPoint(
                            (inset + point.x * (size.width - 2 * inset)) / size.width.coerceAtLeast(1f),
                            (inset + point.y * (size.height - 2 * inset)) / size.height.coerceAtLeast(1f),
                        )
                    }?.toRenderedSegment(
                        width = size.width,
                        height = size.height,
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
                        if (fillBrush != null) {
                            segment.fillPath?.let { path -> drawPath(path, fillBrush) }
                        }
                        segment.linePath?.let { path -> drawPath(path, color, style = Stroke(strokeWidth)) }
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

/** Straight segments show only the changes measured between samples. */
private fun List<LineChartPoint>.toRenderedSegment(
    width: Float,
    height: Float,
    includeFill: Boolean,
): RenderedLineChartSegment {
    val first = first().scaled(width, height)
    if (size == 1) return RenderedLineChartSegment(point = first)

    var previous = first
    val linePath = Path().apply { moveTo(first.x, first.y) }
    for (index in 1..lastIndex) {
        val current = this[index].scaled(width, height)
        linePath.lineTo(current.x, current.y)
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
