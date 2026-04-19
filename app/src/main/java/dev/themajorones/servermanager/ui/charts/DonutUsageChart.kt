package dev.themajorones.servermanager.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun DonutUsageChart(
    usedPercent: Float,
    usedColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = 16f, cap = StrokeCap.Round)
        val clamped = usedPercent.coerceIn(0f, 100f)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = usedColor,
            startAngle = -90f,
            sweepAngle = 360f * (clamped / 100f),
            useCenter = false,
            style = stroke,
        )
    }
}
