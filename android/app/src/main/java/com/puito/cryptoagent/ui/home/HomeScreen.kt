package com.puito.cryptoagent.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puito.cryptoagent.data.Candle
import com.puito.cryptoagent.data.PrefsStore
import com.puito.cryptoagent.net.AgentApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun HomeScreen(api: AgentApi, prefs: PrefsStore) {
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(prefs.load()) }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var endOffset by remember { mutableFloatStateOf(0f) }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            try {
                api.updateBase(p.gatewayBaseUrl)
                candles = api.klines(p.symbol, p.interval, p.limit)
                scale = 1f
                endOffset = 0f
            } catch (e: Exception) {
                error = e.message
                candles = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(p.symbol, p.interval, p.gatewayBaseUrl) { reload() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Crypto Agent · 行情", style = MaterialTheme.typography.titleMedium)
        Text("对接 Gateway 微服务（与 crypto-app 无关）", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DropdownChip(p.symbol, listOf("BTCUSDT", "ETHUSDT")) {
                p = p.copy(symbol = it); prefs.save(p)
            }
            DropdownChip(p.interval, listOf("5m", "15m", "30m", "1h", "4h")) {
                p = p.copy(interval = it); prefs.save(p)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload() }, enabled = !loading) {
                Text(if (loading) "加载中" else "刷新")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12161C)),
        ) {
            if (candles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (loading) "加载 K 线…" else "无数据 · 检查 Gateway 地址", color = Color.Gray)
                }
            } else {
                AgentCandleChart(
                    candles = candles,
                    scale = scale,
                    endOffset = endOffset,
                    onScale = { scale = it },
                    onOffset = { endOffset = it },
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            }
        }
        Text("双指缩放 · 拖动平移 · 双击重置", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
    }
}

@Composable
private fun DropdownChip(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { exp = true }) { Text(value) }
        DropdownMenu(exp, { exp = false }) {
            options.forEach {
                DropdownMenuItem(text = { Text(it) }, onClick = { onSelect(it); exp = false })
            }
        }
    }
}

@Composable
fun AgentCandleChart(
    candles: List<Candle>,
    scale: Float,
    endOffset: Float,
    onScale: (Float) -> Unit,
    onOffset: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bull = Color(0xFF0ECB81)
    val bear = Color(0xFFF6465D)
    val axis = Color(0xFF848E9C)
    val grid = Color(0xFF1E2329)

    Box(
        modifier.pointerInput(candles.size) {
            detectTransformGestures { _, pan, zoom, _ ->
                val ns = (scale * zoom).coerceIn(0.4f, 8f)
                onScale(ns)
                val visible = (60 / ns).roundToInt().coerceIn(15, candles.size)
                val shift = -pan.x / (size.width / visible)
                onOffset((endOffset + shift).coerceIn(0f, max(0f, candles.size - visible.toFloat())))
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val left = 56f
            val bottom = 28f
            val top = 10f
            val plotW = size.width - left - 8f
            val plotH = size.height - top - bottom
            val visible = (60 / scale).roundToInt().coerceIn(15, candles.size)
            val end = (candles.size - endOffset.roundToInt()).coerceIn(visible, candles.size)
            val start = (end - visible).coerceAtLeast(0)
            val win = candles.subList(start, end)
            if (win.isEmpty()) return@Canvas
            val maxH = win.maxOf { it.high }
            val minL = win.minOf { it.low }
            val yMax = maxH + (maxH - minL) * 0.05
            val yMin = minL - (maxH - minL) * 0.05
            val yRange = (yMax - yMin).coerceAtLeast(1e-8)
            fun xAt(i: Int) = left + (i + 0.5f) * (plotW / win.size)
            fun yAt(p: Double) = top + ((yMax - p) / yRange * plotH).toFloat()

            val yPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#848E9C")
                textSize = 24f
                textAlign = android.graphics.Paint.Align.RIGHT
                isAntiAlias = true
            }
            for (t in 0..4) {
                val pr = yMax - (yMax - yMin) * t / 4
                val y = yAt(pr)
                drawLine(grid, Offset(left, y), Offset(left + plotW, y), 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(Locale.US, if (pr >= 1000) "%.1f" else "%.2f", pr),
                    left - 6f, y + 8f, yPaint,
                )
            }
            drawLine(axis, Offset(left, top), Offset(left, top + plotH), 2f)
            drawLine(axis, Offset(left, top + plotH), Offset(left + plotW, top + plotH), 2f)

            val bodyW = (plotW / win.size * 0.6f).coerceIn(2f, 24f)
            win.forEachIndexed { i, c ->
                val x = xAt(i)
                val col = if (c.close >= c.open) bull else bear
                drawLine(col, Offset(x, yAt(c.high)), Offset(x, yAt(c.low)), 2f)
                val topB = min(yAt(c.open), yAt(c.close))
                val botB = max(yAt(c.open), yAt(c.close))
                drawRect(col, Offset(x - bodyW / 2, topB), Size(bodyW, max(2f, botB - topB)))
            }

            val xPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#848E9C")
                textSize = 20f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            for (k in 0 until min(4, win.size)) {
                val i = if (win.size == 1) 0 else k * (win.size - 1) / 3
                val x = xAt(i.coerceIn(0, win.lastIndex))
                drawLine(grid, Offset(x, top), Offset(x, top + plotH), 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    fmt.format(Date(win[i.coerceIn(0, win.lastIndex)].openTime)),
                    x, top + plotH + 22f, xPaint,
                )
            }
            drawRect(axis.copy(alpha = 0.4f), Offset(left, top), Size(plotW, plotH), style = Stroke(1.5f))
        }
    }
}
