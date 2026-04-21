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
import android.graphics.RectF
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
    valueFormatter: (Float) -> String = { value -> String.format(java.util.Locale.US, "%.1f%%", value) },
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
            val placedBounds = mutableListOf<RectF>()

            fun overlapsExisting(candidate: RectF): Boolean =
                placedBounds.any { RectF.intersects(it, candidate) }

            fun drawLabel(canvas: android.graphics.Canvas, text: String, anchor: Offset, preferredOffsets: List<Offset>) {
                val padding = 8f
                val textWidth = statsPaint.measureText(text)
                val textHeight = statsPaint.textSize

                val offsets = preferredOffsets + Offset(12f, -12f)
                for (offset in offsets) {
                    val desiredX = anchor.x + offset.x
                    val desiredY = anchor.y + offset.y
                    val clampedX = desiredX.coerceIn(padding, size.width - textWidth - padding)
                    val clampedY = desiredY.coerceIn(textHeight + padding, size.height - padding)
                    val bounds = RectF(clampedX, clampedY - textHeight, clampedX + textWidth, clampedY)
                    if (!overlapsExisting(bounds)) {
                        canvas.drawText(text, clampedX, clampedY, statsPaint)
                        placedBounds += bounds
                        return
                    }
                }

                val fallbackX = (anchor.x + 12f).coerceIn(padding, size.width - textWidth - padding)
                val fallbackY = (anchor.y - 12f).coerceIn(textHeight + padding, size.height - padding)
                val fallbackBounds = RectF(fallbackX, fallbackY - textHeight, fallbackX + textWidth, fallbackY)
                canvas.drawText(text, fallbackX, fallbackY, statsPaint)
                placedBounds += fallbackBounds
            }

            drawIntoCanvas { canvas ->
                drawLabel(
                    canvas.nativeCanvas,
                    "Current ${valueFormatter(currentValue)}",
                    lastPoint,
                    preferredOffsets = listOf(
                        Offset(12f, -12f),
                        Offset(-140f, -12f),
                        Offset(-140f, 28f),
                        Offset(12f, 28f),
                    ),
                )
                drawLabel(
                    canvas.nativeCanvas,
                    "Max ${valueFormatter(values[maxIndex])}",
                    maxPoint,
                    preferredOffsets = listOf(
                        Offset(12f, -22f),
                        Offset(-140f, -22f),
                        Offset(12f, 26f),
                        Offset(-140f, 26f),
                    ),
                )
                drawLabel(
                    canvas.nativeCanvas,
                    "Min ${valueFormatter(values[minIndex])}",
                    minPoint,
                    preferredOffsets = listOf(
                        Offset(12f, -22f),
                        Offset(-140f, -22f),
                        Offset(12f, 26f),
                        Offset(-140f, 26f),
                    ),
                )
            }
        }
    }
}
