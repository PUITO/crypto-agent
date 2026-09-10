package com.puito.cryptoagent.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puito.cryptoagent.data.*
import com.puito.cryptoagent.domain.Indicators
import com.puito.cryptoagent.service.MonitorService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: Repository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(repo.settings()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var endOff by remember { mutableFloatStateOf(0f) }
    var showInd by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true; err = null
            repo.refreshMarket().onFailure { err = it.message }.onSuccess { tick++ }
            s = repo.settings()
            loading = false
        }
    }
    LaunchedEffect(s.symbol, s.interval) { reload() }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Drop(s.symbol, listOf("BTCUSDT", "ETHUSDT")) {
                s = s.copy(symbol = it); repo.saveSettings(s); reload()
            }
            Drop(s.interval, listOf("5m", "10m", "30m", "1h")) {
                s = s.copy(interval = it); repo.saveSettings(s); reload()
            }
            IconButton({ showInd = true }) {
                Icon(Icons.Default.Analytics, contentDescription = "指标")
            }
            Spacer(Modifier.weight(1f))
            TextButton({ reload() }, enabled = !loading) { Text(if (loading) "…" else "刷新") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.strategyRunning) "策略运行 · 后台${if (s.backgroundEnabled) "开" else "关"}"
                else "策略停止",
                color = if (s.strategyRunning) Color(0xFF0ECB81) else MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = s.strategyRunning,
                onClick = {
                    val ns = s.copy(strategyRunning = !s.strategyRunning)
                    repo.saveSettings(ns); s = ns
                    if (ns.strategyRunning) {
                        scope.launch {
                            repo.refreshMarket(); tick++
                            if (ns.backgroundEnabled) MonitorService.start(ctx)
                        }
                    } else {
                        MonitorService.stop(ctx); tick++
                    }
                },
                label = { Text(if (s.strategyRunning) "停止" else "启动") },
            )
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

        key(tick, s.chartIndicators) {
            Chart(
                candles = repo.candles,
                signals = if (s.strategyRunning) repo.signals else emptyList(),
                indicators = s.chartIndicators.filter { it.enabled },
                overlays = repo.overlays,
                scale = scale,
                endOffset = endOff,
                onScale = { scale = it },
                onOffset = { endOff = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(top = 4.dp),
            )
        }
        Text("左滑内容左移 · 双指缩放 · 双击重置", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)

        val st = repo.stats
        Text(
            "统计（全部 ${st.trades} 笔）：胜 ${st.wins} 负 ${st.losses} 胜率 ${"%.1f".format(st.winRate * 100)}% 收益 ${"%.2f".format(st.totalReturnPct)}%",
            fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Text("模拟成交（最近 5 / 共 ${repo.trades.size}）", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        val rows = repo.trades.takeLast(5).reversed()
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(fmt.format(Date(t.entryTime)), Modifier.weight(1.2f), fontSize = 11.sp)
                    Text(t.side, Modifier.weight(0.4f), color = if (t.side == "B") Color(0xFF0ECB81) else Color(0xFFF6465D), fontSize = 11.sp)
                    Text("%.1f".format(t.entryPrice), Modifier.weight(0.9f), fontSize = 11.sp)
                    Text(if (t.win) "盈" else "亏", Modifier.weight(0.4f), color = if (t.win) Color(0xFF0ECB81) else Color(0xFFF6465D), fontSize = 11.sp)
                }
                HorizontalDivider()
            }
            if (rows.isEmpty()) item { Text("启动策略后生成模拟成交", color = MaterialTheme.colorScheme.secondary) }
        }
    }

    if (showInd) {
        ModalBottomSheet(onDismissRequest = { showInd = false }) {
            Text("图表指标（勾选即时生效，可改周期）", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            s.chartIndicators.forEachIndexed { idx, ind ->
                var periodText by remember(ind.id, ind.period) { mutableStateOf(ind.period.toString()) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(ind.enabled, {
                        val list = s.chartIndicators.toMutableList()
                        list[idx] = ind.copy(enabled = it)
                        s = s.copy(chartIndicators = list)
                        repo.saveSettings(s)
                        tick++
                    })
                    Text(ind.name, Modifier.weight(1f))
                    OutlinedTextField(
                        periodText,
                        {
                            periodText = it
                            it.toIntOrNull()?.let { p ->
                                if (p in 2..200) {
                                    val list = s.chartIndicators.toMutableList()
                                    list[idx] = ind.copy(period = p)
                                    s = s.copy(chartIndicators = list)
                                    repo.saveSettings(s)
                                    tick++
                                }
                            }
                        },
                        Modifier.width(72.dp),
                        label = { Text("周期") },
                        singleLine = true,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Drop(v: String, opts: List<String>, on: (String) -> Unit) {
    var e by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ e = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Text(v, fontSize = 12.sp)
        }
        DropdownMenu(e, { e = false }) {
            opts.forEach { DropdownMenuItem({ Text(it) }, { on(it); e = false }) }
        }
    }
}

@Composable
fun Chart(
    candles: List<Candle>,
    signals: List<SignalMark>,
    indicators: List<ChartIndicatorPref>,
    overlays: List<ChartOverlay>,
    scale: Float,
    endOffset: Float,
    onScale: (Float) -> Unit,
    onOffset: (Float) -> Unit,
    modifier: Modifier,
) {
    val bull = Color(0xFF0ECB81)
    val bear = Color(0xFFF6465D)
    val axis = Color(0xFF848E9C)
    val grid = Color(0xFF1E2329)

    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF12161C))) {
        if (candles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载 K 线…", color = Color.Gray)
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(candles.size) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val ns = (scale * zoom).coerceIn(0.4f, 8f)
                            onScale(ns)
                            val vis = (55 / ns).roundToInt().coerceIn(12, candles.size)
                            // 左滑(pan.x<0) → 内容左移 → 看更新的 K 线（endOffset 减小）
                            // 右滑(pan.x>0) → 内容右移 → 看更早历史（endOffset 增大）
                            onOffset(
                                (endOffset + pan.x / (size.width / vis))
                                    .coerceIn(0f, max(0f, candles.size - vis.toFloat())),
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onScale(1f); onOffset(0f) })
                    },
            ) {
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    // 加大边距，避免 XY 轴文字被裁切
                    val left = 72f
                    val right = 12f
                    val bot = 36f
                    val top = 16f
                    val pw = size.width - left - right
                    val ph = size.height - top - bot
                    if (pw <= 1f || ph <= 1f) return@Canvas

                    val vis = (55 / scale).roundToInt().coerceIn(12, candles.size)
                    val end = (candles.size - endOffset.roundToInt()).coerceIn(vis, candles.size)
                    val start = (end - vis).coerceAtLeast(0)
                    val win = candles.subList(start, end)
                    val closesAll = candles.map { it.close }

                    var maxH = win.maxOf { it.high }
                    var minL = win.minOf { it.low }
                    // 把叠加线纳入范围
                    indicators.forEach { ind ->
                        val series = when {
                            ind.name.startsWith("MA", true) || ind.id.startsWith("ma") ->
                                Indicators.sma(closesAll, ind.period)
                            ind.name.startsWith("EMA", true) || ind.id.startsWith("ema") ->
                                Indicators.ema(closesAll, ind.period)
                            ind.name.startsWith("BOLL", true) || ind.id.startsWith("boll") -> {
                                val (u, m, l) = Indicators.boll(closesAll, ind.period)
                                listOf(u, m, l).forEach { ser ->
                                    for (i in start until end) {
                                        ser.getOrNull(i)?.let { v ->
                                            maxH = max(maxH, v); minL = min(minL, v)
                                        }
                                    }
                                }
                                emptyList()
                            }
                            else -> emptyList()
                        }
                        for (i in start until end) {
                            series.getOrNull(i)?.let { v ->
                                maxH = max(maxH, v); minL = min(minL, v)
                            }
                        }
                    }
                    overlays.filter { it.type == "fib" }.forEach { o ->
                        o.values.forEach { v -> maxH = max(maxH, v); minL = min(minL, v) }
                    }

                    val yMax = maxH + (maxH - minL) * 0.05
                    val yMin = minL - (maxH - minL) * 0.05
                    val yr = (yMax - yMin).coerceAtLeast(1e-8)
                    fun xAt(i: Int) = left + (i + 0.5f) * (pw / win.size)
                    fun yAt(p: Double) = top + ((yMax - p) / yr * ph).toFloat()

                    val yp = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#848E9C")
                        textSize = 26f
                        textAlign = android.graphics.Paint.Align.RIGHT
                        isAntiAlias = true
                    }
                    for (t in 0..4) {
                        val pr = yMax - (yMax - yMin) * t / 4
                        val y = yAt(pr)
                        drawLine(grid, Offset(left, y), Offset(left + pw, y), 1f)
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.US, if (pr >= 1000) "%.1f" else "%.2f", pr),
                            left - 8f, y + 8f, yp,
                        )
                    }
                    drawLine(axis, Offset(left, top), Offset(left, top + ph), 2.5f)
                    drawLine(axis, Offset(left, top + ph), Offset(left + pw, top + ph), 2.5f)

                    val bw = (pw / win.size * 0.6f).coerceIn(2f, 22f)
                    win.forEachIndexed { i, c ->
                        val x = xAt(i)
                        val col = if (c.close >= c.open) bull else bear
                        drawLine(col, Offset(x, yAt(c.high)), Offset(x, yAt(c.low)), 2f)
                        val t = min(yAt(c.open), yAt(c.close))
                        val b = max(yAt(c.open), yAt(c.close))
                        drawRect(col, Offset(x - bw / 2, t), Size(bw, max(2f, b - t)))
                    }

                    // 指标线
                    fun drawSeries(ser: List<Double?>, color: Color) {
                        var prev: Offset? = null
                        win.forEachIndexed { i, _ ->
                            val gi = start + i
                            val v = ser.getOrNull(gi) ?: run { prev = null; return@forEachIndexed }
                            val pt = Offset(xAt(i), yAt(v))
                            prev?.let { drawLine(color, it, pt, 2.5f) }
                            prev = pt
                        }
                    }
                    indicators.forEach { ind ->
                        val col = Color(ind.colorArgb)
                        when {
                            ind.name.startsWith("BOLL", true) || ind.id.startsWith("boll") -> {
                                val (u, m, l) = Indicators.boll(closesAll, ind.period)
                                drawSeries(u, col.copy(alpha = 0.7f))
                                drawSeries(m, col)
                                drawSeries(l, col.copy(alpha = 0.7f))
                            }
                            ind.name.startsWith("EMA", true) || ind.id.startsWith("ema") ->
                                drawSeries(Indicators.ema(closesAll, ind.period), col)
                            ind.name.startsWith("RSI", true) -> { /* RSI 副图简化：跳过主图 */ }
                            else -> drawSeries(Indicators.sma(closesAll, ind.period), col)
                        }
                    }

                    // 斐波那契水平线
                    overlays.filter { it.type == "fib" }.forEach { o ->
                        val fibPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#F0B90B")
                            textSize = 20f
                            isAntiAlias = true
                        }
                        o.values.forEach { v ->
                            val y = yAt(v)
                            drawLine(Color(0xFFF0B90B).copy(alpha = 0.6f), Offset(left, y), Offset(left + pw, y), 1.5f)
                            drawContext.canvas.nativeCanvas.drawText("%.1f".format(v), left + 4f, y - 4f, fibPaint)
                        }
                    }

                    val xp = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#848E9C")
                        textSize = 22f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    for (k in 0 until min(4, win.size)) {
                        val i = if (win.size == 1) 0 else k * (win.size - 1) / 3
                        val x = xAt(i)
                        drawLine(grid, Offset(x, top), Offset(x, top + ph), 1f)
                        drawContext.canvas.nativeCanvas.drawText(
                            fmt.format(Date(win[i].openTime)), x, top + ph + 26f, xp,
                        )
                    }

                    val map = signals.groupBy { it.openTime }
                    val pb = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#0ECB81")
                        textSize = 26f; isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val ps = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#F6465D")
                        textSize = 26f; isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    win.forEachIndexed { i, c ->
                        map[c.openTime]?.forEach { m ->
                            val x = xAt(i)
                            if (m.side == "B") {
                                val y = yAt(c.low) + 2f
                                drawPath(Path().apply {
                                    moveTo(x, y); lineTo(x - 8f, y + 14f); lineTo(x + 8f, y + 14f); close()
                                }, bull)
                                drawContext.canvas.nativeCanvas.drawText("B", x, y + 34f, pb)
                            } else {
                                val y = yAt(c.high) - 2f
                                drawPath(Path().apply {
                                    moveTo(x, y); lineTo(x - 8f, y - 14f); lineTo(x + 8f, y - 14f); close()
                                }, bear)
                                drawContext.canvas.nativeCanvas.drawText("S", x, y - 18f, ps)
                            }
                        }
                    }
                    drawRect(axis.copy(0.35f), Offset(left, top), Size(pw, ph), style = Stroke(1.2f))
                }
            }
        }
    }
}
