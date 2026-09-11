package com.puito.cryptoagent.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clipToBounds
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
    var startIndex by remember { mutableFloatStateOf(-1f) } // <0 = stick to latest
    var showInd by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true; err = null
            repo.refreshMarket().onFailure { err = it.message }.onSuccess { tick++; startIndex = -1f; scale = 1f }
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
            val candleCount = repo.candles.size
            Chart(
                candles = repo.candles,
                signals = if (s.strategyRunning) repo.signals else emptyList(),
                indicators = s.chartIndicators.filter { it.enabled },
                overlays = repo.overlays,
                scale = scale,
                startIndex = startIndex,
                onScale = { scale = it },
                onPanBars = pan@{ deltaBars ->
                    if (candleCount <= 0) return@pan
                    val vis = (40f / scale).roundToInt().coerceIn(12, candleCount.coerceAtLeast(12))
                    val maxS = (candleCount - vis).coerceAtLeast(0).toFloat()
                    val cur = if (startIndex < 0f) maxS else startIndex.coerceIn(0f, maxS)
                    startIndex = (cur + deltaBars).coerceIn(0f, maxS)
                },
                onResetView = {
                    scale = 1f
                    startIndex = -1f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .padding(top = 4.dp),
            )
        }
        Text("按住图表左右拖动浏览 · 左拖看更新、右拖看历史 · 双指缩放 · 双击回最新", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)

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
    startIndex: Float,
    onScale: (Float) -> Unit,
    onPanBars: (Float) -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier,
) {
    val bull = Color(0xFF0ECB81)
    val bear = Color(0xFFF6465D)
    val axis = Color(0xFF848E9C)
    val grid = Color(0xFF1E2329)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161C)),
    ) {
        if (candles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载 K 线…", color = Color.Gray)
            }
            return@Card
        }

        val baseVisible = 40f
        val visibleCount = (baseVisible / scale).roundToInt().coerceIn(12, candles.size.coerceAtLeast(12))
        val maxStart = (candles.size - visibleCount).coerceAtLeast(0).toFloat()
        val startF = if (startIndex < 0f) maxStart else startIndex.coerceIn(0f, maxStart)
        val startI = startF.toInt().coerceIn(0, (candles.size - 1).coerceAtLeast(0))
        val endI = (startI + visibleCount).coerceAtMost(candles.size)
        val win = if (startI < endI) candles.subList(startI, endI) else emptyList()
        if (win.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无可见K线", color = Color.Gray)
            }
            return@Card
        }

        val closesAll = candles.map { it.close }
        var maxH = win.maxOf { it.high }
        var minL = win.minOf { it.low }
        indicators.forEach { ind ->
            val seriesList = when {
                ind.name.startsWith("BOLL", true) || ind.id.startsWith("boll") -> {
                    val (u, m, l) = Indicators.boll(closesAll, ind.period)
                    listOf(u, m, l)
                }
                ind.name.startsWith("EMA", true) || ind.id.startsWith("ema") ->
                    listOf(Indicators.ema(closesAll, ind.period))
                ind.name.startsWith("RSI", true) -> emptyList()
                else -> listOf(Indicators.sma(closesAll, ind.period))
            }
            seriesList.forEach { ser ->
                for (i in startI until endI) {
                    ser.getOrNull(i)?.let { v ->
                        maxH = max(maxH, v)
                        minL = min(minL, v)
                    }
                }
            }
        }
        overlays.filter { it.type == "fib" }.forEach { o ->
            o.values.forEach { v ->
                maxH = max(maxH, v)
                minL = min(minL, v)
            }
        }
        val yMax = maxH + (maxH - minL) * 0.05
        val yMin = minL - (maxH - minL) * 0.05
        val yr = (yMax - yMin).coerceAtLeast(1e-8)
        val yLabels = (0..4).map { t -> yMax - (yMax - yMin) * t / 4.0 }

        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // 主图：左侧价格 label（Compose Text，永不裁切）+ 右侧 Canvas
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .width(64.dp)
                        .fillMaxHeight()
                        .padding(end = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    yLabels.forEach { pr ->
                        Text(
                            if (pr >= 1000) String.format(Locale.US, "%.1f", pr)
                            else String.format(Locale.US, "%.2f", pr),
                            color = Color(0xFFB0B8C4),
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }

                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds(),
                ) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val plotW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                    val plotH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                    val barW = plotW / visibleCount
                    val pixelShift = (startF - startI) * barW

                    Box(
                        Modifier
                            .fillMaxSize()
                            // 单指水平拖动：可靠、不跳动
                            .pointerInput(barW, maxStart, visibleCount) {
                                detectHorizontalDragGestures { _, dragAmount ->
                                    // 手指右滑 dragAmount>0 → 内容右移 → 看更早 → startIndex 减小
                                    // 手指左滑 dragAmount<0 → 内容左移 → 看更新 → startIndex 增大
                                    if (barW > 0f) {
                                        onPanBars(-dragAmount / barW)
                                    }
                                }
                            }
                            // 双指缩放
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    if (zoom != 1f && zoom > 0f) {
                                        onScale((scale * zoom).coerceIn(0.4f, 5f))
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { onResetView() })
                            },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            fun xAt(iInWin: Int) = (iInWin + 0.5f) * barW - pixelShift
                            fun yAt(p: Double) = ((yMax - p) / yr * plotH).toFloat()

                            // 网格
                            for (tIdx in 0..4) {
                                val y = yAt(yLabels[tIdx])
                                drawLine(grid, Offset(0f, y), Offset(plotW, y), 1f)
                            }
                            // 竖线
                            val labelCount = min(4, win.size)
                            for (k in 0 until labelCount) {
                                val i = if (win.size <= 1) 0 else k * (win.size - 1) / (labelCount - 1).coerceAtLeast(1)
                                val x = xAt(i)
                                if (x in 0f..plotW) {
                                    drawLine(grid, Offset(x, 0f), Offset(x, plotH), 1f)
                                }
                            }

                            val bodyW = (barW * 0.6f).coerceIn(2f, 26f)
                            win.forEachIndexed { i, c ->
                                val x = xAt(i)
                                if (x < -bodyW || x > plotW + bodyW) return@forEachIndexed
                                val col = if (c.close >= c.open) bull else bear
                                drawLine(col, Offset(x, yAt(c.high)), Offset(x, yAt(c.low)), 2f)
                                val topB = min(yAt(c.open), yAt(c.close))
                                val botB = max(yAt(c.open), yAt(c.close))
                                drawRect(col, Offset(x - bodyW / 2f, topB), Size(bodyW, max(2f, botB - topB)))
                            }

                            fun drawSeries(ser: List<Double?>, color: Color) {
                                var prev: Offset? = null
                                win.forEachIndexed { i, _ ->
                                    val v = ser.getOrNull(startI + i) ?: run { prev = null; return@forEachIndexed }
                                    val pt = Offset(xAt(i), yAt(v))
                                    if (pt.x in -2f..(plotW + 2f)) {
                                        prev?.let { drawLine(color, it, pt, 2.5f) }
                                        prev = pt
                                    } else prev = null
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
                                    ind.name.startsWith("RSI", true) -> Unit
                                    else -> drawSeries(Indicators.sma(closesAll, ind.period), col)
                                }
                            }

                            overlays.filter { it.type == "fib" }.forEach { o ->
                                o.values.forEach { v ->
                                    val y = yAt(v)
                                    drawLine(Color(0xFFF0B90B).copy(alpha = 0.55f), Offset(0f, y), Offset(plotW, y), 1.5f)
                                }
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
                                    if (x !in 0f..plotW) return@forEach
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
                            drawRect(axis.copy(alpha = 0.45f), Offset(0f, 0f), Size(plotW, plotH), style = Stroke(1.5f))
                        }
                    }
                }
            }

            // X 轴时间：独立一行，不画进 Canvas，避免遮挡
            Row(Modifier.fillMaxWidth().padding(start = 64.dp, top = 4.dp)) {
                val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                val labelCount = min(4, win.size)
                for (k in 0 until labelCount) {
                    val i = if (win.size <= 1) 0 else k * (win.size - 1) / (labelCount - 1).coerceAtLeast(1)
                    Text(
                        fmt.format(Date(win[i].openTime)),
                        Modifier.weight(1f),
                        color = Color(0xFFB0B8C4),
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
