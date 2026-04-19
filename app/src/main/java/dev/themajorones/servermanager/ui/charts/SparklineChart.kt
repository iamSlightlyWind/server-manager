package dev.themajorones.servermanager.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Paint
import android.graphics.Typeface

@Composable
fun SparklineChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 88.dp,
    min: Float = 0f,
    max: Float = 100f,
    autoScale: Boolean = true,
    showStats: Boolean = false,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (values.size < 2) return@Canvas

        val dataMin = values.minOrNull() ?: min
        val dataMax = values.maxOrNull() ?: max
        val autoMin = when {
            dataMin.isNaN() -> min
            dataMax.isNaN() -> min
            dataMax <= dataMin -> dataMin - 1f
            else -> dataMin - ((dataMax - dataMin) * 0.15f)
        }
        val autoMax = when {
            dataMin.isNaN() -> max
            dataMax.isNaN() -> max
            dataMax <= dataMin -> dataMax + 1f
            else -> dataMax + ((dataMax - dataMin) * 0.15f)
        }
        val safeMin = if (autoScale) autoMin else min
        val safeMax = if (autoScale) autoMax else if (max <= min) min + 1f else max
        val widthStep = size.width / (values.lastIndex.coerceAtLeast(1))

        val points = values.mapIndexed { index, raw ->
            val clamped = raw.coerceIn(safeMin, safeMax)
            val ratio = (clamped - safeMin) / (safeMax - safeMin)
            val x = widthStep * index
            val y = size.height - (ratio * size.height)
            Offset(x, y)
        }

        val path = Path()
        path.moveTo(points.first().x, points.first().y)

        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
        } else {
            for (index in 1 until points.lastIndex) {
                val current = points[index]
                val next = points[index + 1]
                val midX = (current.x + next.x) / 2f
                val midY = (current.y + next.y) / 2f
                path.quadraticTo(current.x, current.y, midX, midY)
            }

            val last = points.last()
            path.lineTo(last.x, last.y)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 5f, cap = StrokeCap.Round),
        )

        val lastPoint = points.last()
        drawCircle(color = color, radius = 6f, center = lastPoint)

        if (showStats) {
            val statsPaint = Paint().apply {
                isAntiAlias = true
                textSize = 28f
                this.color = color.toArgb()
                typeface = Typeface.DEFAULT_BOLD
            }

            val currentValue = values.last()
            val maxIndex = values.indices.maxByOrNull { values[it] } ?: 0
            val minIndex = values.indices.minByOrNull { values[it] } ?: 0
            val maxPoint = points[maxIndex]
            val minPoint = points[minIndex]

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    String.format(java.util.Locale.US, "Current %.1f%%", currentValue),
                    lastPoint.x + 12f,
                    (lastPoint.y - 12f).coerceAtLeast(30f),
                    statsPaint,
                )
                canvas.nativeCanvas.drawText(
                    String.format(java.util.Locale.US, "Max %.1f%%", values[maxIndex]),
                    maxPoint.x + 12f,
                    (maxPoint.y - 12f).coerceAtLeast(30f),
                    statsPaint,
                )
                canvas.nativeCanvas.drawText(
                    String.format(java.util.Locale.US, "Min %.1f%%", values[minIndex]),
                    minPoint.x + 12f,
                    (minPoint.y - 12f).coerceAtLeast(30f),
                    statsPaint,
                )
            }
        }
    }
}
