package com.example.truenasapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** How many points the live charts keep (TrueNAS sends roughly one update every 2 seconds). */
const val CHART_POINTS = 60

data class ChartSeries(val values: List<Float>, val color: Color)

/**
 * A simple live line chart (like the TrueNAS dashboard widgets).
 * New points enter on the right and scroll left.
 * [maxValue] fixes the top of the chart (e.g. 100 for %); null = scale to the data.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    maxValue: Float? = null,
    capacity: Int = CHART_POINTS,
    fill: Boolean = true,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val peak = series.flatMap { it.values }.maxOrNull() ?: 0f
        val top = maxValue ?: (peak * 1.2f).coerceAtLeast(1f)

        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }

        val step = size.width / (capacity - 1).coerceAtLeast(1)
        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val startX = size.width - step * (s.values.size - 1)
            val line = Path()
            val fillPath = Path()
            s.values.forEachIndexed { i, v ->
                val x = startX + i * step
                val y = size.height - (v / top).coerceIn(0f, 1f) * size.height
                if (i == 0) {
                    line.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(size.width, size.height)
            fillPath.close()
            if (fill) drawPath(fillPath, s.color.copy(alpha = 0.15f))
            drawPath(line, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Ring chart, e.g. memory split into services / ZFS cache / free. */
@Composable
fun DonutChart(slices: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val stroke = 14.dp.toPx()
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))

        val total = slices.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        var start = -90f
        slices.forEach { (value, color) ->
            val sweep = 360f * value / total
            if (sweep > 0.5f) drawArc(color, start, sweep - 0.5f, false, topLeft, arcSize, style = Stroke(stroke))
            start += sweep
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Adds a point to a rolling chart history. */
fun MutableList<Float>.pushPoint(value: Double?, capacity: Int = CHART_POINTS) {
    add((value ?: lastOrNull()?.toDouble() ?: 0.0).toFloat())
    while (size > capacity) removeAt(0)
}

/** "Last 3 minutes" etc. TrueNAS sends an update about every 2 seconds. */
fun historyLabel(points: Int, sampleEvery: Int): String {
    val minutes = (points * 2 * sampleEvery) / 60
    return if (minutes <= 1) "Last minute" else "Last $minutes minutes"
}
