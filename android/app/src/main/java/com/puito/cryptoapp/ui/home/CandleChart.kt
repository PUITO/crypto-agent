package com.puito.cryptoapp.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puito.cryptoapp.data.model.Candle
import com.puito.cryptoapp.data.model.SignalMark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val ChartBg = Color(0xFF0E1218)
private val GridColor = Color(0xFF1E2329)
private val AxisColor = Color(0xFF848E9C)
private val Bull = Color(0xFF0ECB81)
private val Bear = Color(0xFFF6465D)

@Composable
fun ScrollableCandleChart(
    candles: List<Candle>,
    signals: List<SignalMark>,
    modifier: Modifier = Modifier,
) {
    var scale by remember(candles.size) { mutableFloatStateOf(1f) }
    var endOffset by remember(candles.size) { mutableFloatStateOf(0f) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = ChartBg),
    ) {
        if (candles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无 K 线", color = AxisColor, fontSize = 13.sp)
            }
            return@Card
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(candles.size) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.4f, 10f)
                        val visible = visibleCount(candles.size, scale)
                        val shift = -pan.x / (size.width / visible.coerceAtLeast(1).toFloat())
                        endOffset = (endOffset + shift).coerceIn(
                            0f,
                            max(0f, candles.size - visible.toFloat()),
                        )
                    }
                }
                .pointerInput(candles.size) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            endOffset = 0f
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val leftPad = 58f
                val rightPad = 10f
                val topPad = 14f
                val bottomPad = 30f
                val plotW = size.width - leftPad - rightPad
                val plotH = size.height - topPad - bottomPad
                if (plotW <= 1f || plotH <= 1f) return@Canvas

                val n = candles.size
                val visible = visibleCount(n, scale)
                val end = (n - endOffset.roundToInt()).coerceIn(visible, n)
                val start = (end - visible).coerceAtLeast(0)
                val window = candles.subList(start, end)
                if (window.isEmpty()) return@Canvas

                val maxH = window.maxOf { it.high }
                val minL = window.minOf { it.low }
                val range = (maxH - minL).coerceAtLeast(1e-8)
                val yMax = maxH + range * 0.06
                val yMin = minL - range * 0.06
                val yRange = (yMax - yMin).coerceAtLeast(1e-8)

                fun xAt(i: Int): Float {
                    val slot = plotW / window.size
                    return leftPad + i * slot + slot / 2
                }

                fun yAt(price: Double): Float =
                    topPad + ((yMax - price) / yRange * plotH).toFloat()

                // 背景绘图区
                drawRect(
                    Color(0xFF12161C),
                    Offset(leftPad, topPad),
                    Size(plotW, plotH),
                )

                // Y 网格 + 刻度
                val yTicks = 5
                val yPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#848E9C")
                    textSize = 26f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
                for (t in 0..yTicks) {
                    val p = yMax - (yMax - yMin) * t / yTicks
                    val y = yAt(p)
                    drawLine(GridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText(
                        formatPrice(p),
                        leftPad - 8f,
                        y + 9f,
                        yPaint,
                    )
                }

                // 坐标轴
                drawLine(AxisColor, Offset(leftPad, topPad), Offset(leftPad, topPad + plotH), strokeWidth = 2f)
                drawLine(
                    AxisColor,
                    Offset(leftPad, topPad + plotH),
                    Offset(leftPad + plotW, topPad + plotH),
                    strokeWidth = 2f,
                )

                // K 线
                val slot = plotW / window.size
                val bodyW = (slot * 0.62f).coerceIn(2f, 28f)
                window.forEachIndexed { i, c ->
                    val x = xAt(i)
                    val col = if (c.close >= c.open) Bull else Bear
                    drawLine(col, Offset(x, yAt(c.high)), Offset(x, yAt(c.low)), strokeWidth = 2.5f)
                    val yO = yAt(c.open)
                    val yC = yAt(c.close)
                    val top = min(yO, yC)
                    val bot = max(yO, yC)
                    drawRect(col, Offset(x - bodyW / 2, top), Size(bodyW, max(2f, bot - top)))
                }

                // X 轴时间
                val xPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#848E9C")
                    textSize = 22f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val labelN = min(5, window.size)
                if (labelN > 0) {
                    for (k in 0 until labelN) {
                        val i = if (labelN == 1) 0
                        else (k * (window.size - 1).toFloat() / (labelN - 1)).roundToInt()
                            .coerceIn(0, window.lastIndex)
                        val x = xAt(i)
                        drawLine(
                            GridColor.copy(alpha = 0.6f),
                            Offset(x, topPad),
                            Offset(x, topPad + plotH),
                            strokeWidth = 1f,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            fmt.format(Date(window[i].openTime)),
                            x,
                            topPad + plotH + 24f,
                            xPaint,
                        )
                    }
                }

                // B/S
                val sigMap = signals.groupBy { it.openTime }
                val paintB = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#0ECB81")
                    textSize = 28f
                    isFakeBoldText = true
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val paintS = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#F6465D")
                    textSize = 28f
                    isFakeBoldText = true
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                window.forEachIndexed { i, c ->
                    val marks = sigMap[c.openTime] ?: return@forEachIndexed
                    val x = xAt(i)
                    marks.forEach { s ->
                        if (s.side == "B") {
                            val y = yAt(c.low) + 2f
                            val path = Path().apply {
                                moveTo(x, y)
                                lineTo(x - 10f, y + 18f)
                                lineTo(x + 10f, y + 18f)
                                close()
                            }
                            drawPath(path, Bull)
                            drawContext.canvas.nativeCanvas.drawText("B", x, y + 40f, paintB)
                        } else {
                            val y = yAt(c.high) - 2f
                            val path = Path().apply {
                                moveTo(x, y)
                                lineTo(x - 10f, y - 18f)
                                lineTo(x + 10f, y - 18f)
                                close()
                            }
                            drawPath(path, Bear)
                            drawContext.canvas.nativeCanvas.drawText("S", x, y - 24f, paintS)
                        }
                    }
                }

                drawRect(
                    AxisColor.copy(alpha = 0.4f),
                    Offset(leftPad, topPad),
                    Size(plotW, plotH),
                    style = Stroke(width = 1.5f),
                )
            }

            Text(
                "双指缩放 · 拖动平移 · 双击重置",
                color = AxisColor,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
    }
}

private fun visibleCount(total: Int, scale: Float): Int {
    val base = 60
    return (base / scale).roundToInt().coerceIn(10, max(10, total))
}

private fun formatPrice(p: Double): String = when {
    p >= 1000 -> String.format(Locale.US, "%.1f", p)
    p >= 1 -> String.format(Locale.US, "%.2f", p)
    else -> String.format(Locale.US, "%.4f", p)
}
