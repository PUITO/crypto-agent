package com.puito.cryptoagent.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
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
    // 策略运行中定期刷新图表上的 1m 确认叠加信号（poll 写入 repo.signals）
    LaunchedEffect(s.strategyRunning) {
        while (s.strategyRunning) {
            kotlinx.coroutines.delay(8_000)
            tick++
        }
    }

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
                        // 启动时种子历史信号，禁止回测历史弹通知
                        repo.resetNotificationState()
                        scope.launch {
                            repo.refreshMarket(); tick++
                            // 通知/AI评估依赖前台服务 poll；策略启动即拉起（与「后台开关」解耦）
                            MonitorService.start(ctx)
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
            // 图例：B/S=周期原生  1B/1S=1m触发+本周期确认叠加
            Text(
                "标记: B/S 周期策略 · 1B/1S 为 1m 触发并经本周期确认",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
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
                    val vis = (48f / scale).roundToInt().coerceIn(15, candleCount.coerceAtLeast(15))
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
        Text("B/S=周期信号 · b/s=1m映射 · 单击Tips · 拖动平移 · 双指缩放 · 双击回最新", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)

        // 统一模拟仓：1m确认 + 周期原生信号，行情页展示
        val simTick by repo.liveSimTick.collectAsState()
        val pending = remember(simTick) { repo.pendingLivePositions() }
        val closed = remember(simTick) { repo.allClosedSimTrades() }
        val st = remember(simTick) { repo.unifiedStats() }
        val consec = remember(simTick) { repo.consecutiveLosses() }
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        fun srcLabel(src: String?) = when (src) {
            "1m_confirm" -> "实时·1m确认"
            "ht_native" -> "实时·周期"
            "history_1m" -> "回测·1m"
            "history_ht" -> "回测·周期"
            "model" -> "模型"
            else -> src?.ifBlank { "-" } ?: "-"
        }
        Text(
            "综合模拟 持仓${pending.size} · 已平${st.trades} 胜${st.wins}负${st.losses} " +
                "胜率${"%.1f".format(st.winRate * 100)}% 收益${"%.2f".format(st.totalReturnPct)}% 连亏$consec",
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Text(
            "含：历史回测 + 实时1m确认 + 实时周期 · 自动调优看本综合胜率",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
        LazyColumn(Modifier.weight(1f)) {
            if (pending.isNotEmpty()) {
                item {
                    Text("持仓中", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                items(pending, key = { it.key }) { p ->
                    val ivMs = when (p.interval.lowercase()) {
                        "1m" -> 60_000L
                        "5m" -> 300_000L
                        "10m" -> 600_000L
                        "30m" -> 1_800_000L
                        "1h" -> 3_600_000L
                        else -> 600_000L
                    }
                    val exp = p.entryTime + ivMs
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            "● ${srcLabel(p.source)} ${p.side} ${p.interval} @${"%.2f".format(p.entryPrice)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "开${fmt.format(Date(p.entryTime))} → 到期${fmt.format(Date(exp))}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    HorizontalDivider()
                }
            }
            item {
                Text(
                    "已平仓（最近）",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(closed.takeLast(40).asReversed(), key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        "${if (t.win) "✓" else "✗"} ${srcLabel(t.source)} ${t.side} ${t.interval} " +
                            "pnl ${"%.2f".format(t.pnlPct)}%",
                        fontSize = 11.sp,
                        color = if (t.win) Color(0xFF0ECB81) else Color(0xFFF6465D),
                    )
                    Text(
                        "开${fmt.format(Date(t.entryTime))} @${"%.2f".format(t.entryPrice)} → " +
                            "平${fmt.format(Date(t.exitTime))} @${"%.2f".format(t.exitPrice)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                HorizontalDivider()
            }
            if (pending.isEmpty() && closed.isEmpty()) {
                item {
                    Text(
                        "启动策略后生成历史回测；实时信号开仓后合并进综合胜率",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp,
                    )
                }
            }
            item {
                TextButton(
                    onClick = { repo.clearLiveSim() },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("清空模拟仓", fontSize = 12.sp) }
            }
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
    var tipIdx by remember { mutableStateOf<Int?>(null) } // 全局 candles 下标
    val tipFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161C)),
    ) {
        if (candles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载 K 线…", color = Color.Gray)
            }
        } else {
            // 用 BoxWithConstraints 拿稳定宽度，手势与绘制同一坐标系，减少跳动
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }

                // 指标只算一次（K线/开关变化才重算），避免每次重绘/滑动重算布林
                val closesAll = remember(candles) { candles.map { it.close } }
                val indCache = remember(candles, indicators) {
                    val map = LinkedHashMap<String, Any>()
                    for (ind in indicators) {
                        if (!ind.enabled) continue
                        val key = ind.id + "|" + ind.period
                        when {
                            ind.name.startsWith("BOLL", true) || ind.id.startsWith("boll") -> {
                                map[key] = Indicators.boll(closesAll, ind.period)
                            }
                            ind.name.startsWith("EMA", true) || ind.id.startsWith("ema") -> {
                                map[key] = Indicators.ema(closesAll, ind.period)
                            }
                            else -> {
                                map[key] = Indicators.sma(closesAll, ind.period)
                            }
                        }
                    }
                    map
                }

                // 边距：给 Y 轴文字、X 轴时间留足空间，避免被裁切
                val padL = 80f
                val padR = 16f
                val padT = 20f
                val padB = 44f
                val plotW = (widthPx - padL - padR).coerceAtLeast(1f)
                val plotH = (heightPx - padT - padB).coerceAtLeast(1f)

                val baseVisible = 48f
                val visibleCount = (baseVisible / scale).roundToInt().coerceIn(15, candles.size.coerceAtLeast(15))
                val maxStart = (candles.size - visibleCount).coerceAtLeast(0).toFloat()
                // startIndex < 0 表示贴最新（在 Canvas 内解析）
                val barW = plotW / visibleCount

                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(candles.size, barW, maxStart) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom != 1f && zoom > 0f) {
                                    onScale((scale * zoom).coerceIn(0.35f, 6f))
                                }
                                // 增量平移，避免闭包读到过期 startIndex 造成跳动
                                if (pan.x != 0f && barW > 0f) {
                                    onPanBars(-pan.x / barW)
                                }
                            }
                        }
                        .pointerInput(candles.size, barW, maxStart, visibleCount, startIndex) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val startF = if (startIndex < 0f) maxStart else startIndex.coerceIn(0f, maxStart)
                                    val startI = startF.toInt().coerceIn(0, (candles.size - 1).coerceAtLeast(0))
                                    val pixelShift = (startF - startI) * barW
                                    val localX = offset.x - padL + pixelShift
                                    if (localX < 0f || localX > plotW) {
                                        tipIdx = null
                                    } else {
                                        val iWin = (localX / barW).toInt().coerceIn(0, visibleCount - 1)
                                        val gi = (startI + iWin).coerceIn(0, candles.lastIndex)
                                        tipIdx = gi
                                    }
                                },
                                onDoubleTap = {
                                    tipIdx = null
                                    onResetView()
                                },
                                onLongPress = { offset ->
                                    val startF = if (startIndex < 0f) maxStart else startIndex.coerceIn(0f, maxStart)
                                    val startI = startF.toInt().coerceIn(0, (candles.size - 1).coerceAtLeast(0))
                                    val pixelShift = (startF - startI) * barW
                                    val localX = offset.x - padL + pixelShift
                                    if (localX >= 0f && localX <= plotW) {
                                        val iWin = (localX / barW).toInt().coerceIn(0, visibleCount - 1)
                                        tipIdx = (startI + iWin).coerceIn(0, candles.lastIndex)
                                    }
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val startF = if (startIndex < 0f) maxStart else startIndex.coerceIn(0f, maxStart)
                        val startI = startF.toInt().coerceIn(0, (candles.size - 1).coerceAtLeast(0))
                        val endI = (startI + visibleCount).coerceAtMost(candles.size)
                        if (startI >= endI) return@Canvas
                        val win = candles.subList(startI, endI)
                        // 亚像素偏移：平滑滑动不跳动
                        val pixelShift = (startF - startI) * barW

                        var maxH = win.maxOf { it.high }
                        var minL = win.minOf { it.low }
                        indicators.forEach { ind ->
                            val key = ind.id + "|" + ind.period
                            when (val cached = indCache[key]) {
                                is Triple<*, *, *> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val t = cached as Triple<List<Double?>, List<Double?>, List<Double?>>
                                    listOf(t.first, t.second, t.third).forEach { ser ->
                                        for (i in startI until endI) {
                                            ser.getOrNull(i)?.let { v ->
                                                maxH = max(maxH, v); minL = min(minL, v)
                                            }
                                        }
                                    }
                                }
                                is List<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val series = cached as List<Double?>
                                    for (i in startI until endI) {
                                        series.getOrNull(i)?.let { v ->
                                            maxH = max(maxH, v); minL = min(minL, v)
                                        }
                                    }
                                }
                            }
                        }
                        overlays.filter { it.type == "fib" }.forEach { o ->
                            o.values.forEach { v -> maxH = max(maxH, v); minL = min(minL, v) }
                        }

                        val yMax = maxH + (maxH - minL) * 0.06
                        val yMin = minL - (maxH - minL) * 0.06
                        val yr = (yMax - yMin).coerceAtLeast(1e-8)
                        fun xAt(iInWin: Int) = padL + (iInWin + 0.5f) * barW - pixelShift
                        fun yAt(p: Double) = padT + ((yMax - p) / yr * plotH).toFloat()

                        // 背景
                        drawRect(Color(0xFF12161C), Offset.Zero, Size(size.width, size.height))

                        val yp = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#B0B8C4")
                            textSize = 28f
                            textAlign = android.graphics.Paint.Align.RIGHT
                            isAntiAlias = true
                        }
                        for (tIdx in 0..4) {
                            val pr = yMax - (yMax - yMin) * tIdx / 4.0
                            val y = yAt(pr)
                            drawLine(grid, Offset(padL, y), Offset(padL + plotW, y), 1f)
                            val label = if (pr >= 1000) String.format(Locale.US, "%.1f", pr)
                            else String.format(Locale.US, "%.2f", pr)
                            // 画在左侧边距内，完整可见
                            drawContext.canvas.nativeCanvas.drawText(label, padL - 10f, y + 10f, yp)
                        }
                        drawLine(axis, Offset(padL, padT), Offset(padL, padT + plotH), 2.5f)
                        drawLine(axis, Offset(padL, padT + plotH), Offset(padL + plotW, padT + plotH), 2.5f)

                        // 裁剪绘图区，避免蜡烛画出轴外
                        val bodyW = (barW * 0.62f).coerceIn(2f, 28f)
                        win.forEachIndexed { i, c ->
                            val x = xAt(i)
                            if (x < padL - bodyW || x > padL + plotW + bodyW) return@forEachIndexed
                            val col = if (c.close >= c.open) bull else bear
                            drawLine(col, Offset(x, yAt(c.high)), Offset(x, yAt(c.low)), 2f)
                            val topB = min(yAt(c.open), yAt(c.close))
                            val botB = max(yAt(c.open), yAt(c.close))
                            drawRect(col, Offset(x - bodyW / 2f, topB), Size(bodyW, max(2f, botB - topB)))
                        }

                        fun drawSeries(ser: List<Double?>, color: Color) {
                            var prev: Offset? = null
                            win.forEachIndexed { i, _ ->
                                val gi = startI + i
                                val v = ser.getOrNull(gi) ?: run { prev = null; return@forEachIndexed }
                                val pt = Offset(xAt(i), yAt(v))
                                if (pt.x in (padL - 2f)..(padL + plotW + 2f)) {
                                    prev?.let { drawLine(color, it, pt, 2.5f) }
                                    prev = pt
                                } else prev = null
                            }
                        }
                        indicators.forEach { ind ->
                            val col = Color(ind.colorArgb)
                            val key = ind.id + "|" + ind.period
                            when (val cached = indCache[key]) {
                                is Triple<*, *, *> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val t = cached as Triple<List<Double?>, List<Double?>, List<Double?>>
                                    drawSeries(t.first, col.copy(alpha = 0.7f))
                                    drawSeries(t.second, col)
                                    drawSeries(t.third, col.copy(alpha = 0.7f))
                                }
                                is List<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    drawSeries(cached as List<Double?>, col)
                                }
                            }
                        }

                        overlays.filter { it.type == "fib" }.forEach { o ->
                            val fibPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#F0B90B")
                                textSize = 22f
                                isAntiAlias = true
                            }
                            o.values.forEach { v ->
                                val y = yAt(v)
                                drawLine(
                                    Color(0xFFF0B90B).copy(alpha = 0.55f),
                                    Offset(padL, y),
                                    Offset(padL + plotW, y),
                                    1.5f,
                                )
                                drawContext.canvas.nativeCanvas.drawText(
                                    "%.1f".format(v),
                                    padL + 6f,
                                    y - 4f,
                                    fibPaint,
                                )
                            }
                        }

                        val xp = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#B0B8C4")
                            textSize = 24f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        val labelCount = min(4, win.size)
                        for (k in 0 until labelCount) {
                            val i = if (win.size <= 1) 0 else k * (win.size - 1) / (labelCount - 1).coerceAtLeast(1)
                            val x = xAt(i)
                            if (x < padL || x > padL + plotW) continue
                            drawLine(grid, Offset(x, padT), Offset(x, padT + plotH), 1f)
                            drawContext.canvas.nativeCanvas.drawText(
                                fmt.format(Date(win[i].openTime)),
                                x,
                                padT + plotH + 30f,
                                xp,
                            )
                        }

                        // 按 K 线 openTime 对齐；若 1m 映射偏差则落入该棒时间窗也显示
                        val byExact = signals.groupBy { it.openTime }
                        fun marksOnBar(c: Candle): List<SignalMark> {
                            byExact[c.openTime]?.let { return it }
                            // 兼容：信号时间落在本 K 周期内（已由 map1m 对齐时通常走 exact）
                            return signals.filter { it.openTime == c.openTime }
                        }
                        val pb = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#0ECB81")
                            textSize = 26f
                            isFakeBoldText = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        val ps = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#F6465D")
                            textSize = 26f
                            isFakeBoldText = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        win.forEachIndexed { i, c ->
                            marksOnBar(c).forEach { m ->
                                val x = xAt(i)
                                if (x < padL || x > padL + plotW) return@forEach
                                val from1m = m.tag == "1m"
                                val sz = if (from1m) 6f else 8f
                                val label = when {
                                    from1m && m.side == "B" -> "1B"
                                    from1m && m.side == "S" -> "1S"
                                    m.side == "B" -> "B"
                                    else -> "S"
                                }
                                if (m.side == "B") {
                                    val y = yAt(c.low) + 2f
                                    drawPath(
                                        Path().apply {
                                            moveTo(x, y); lineTo(x - sz, y + sz * 1.75f); lineTo(x + sz, y + sz * 1.75f); close()
                                        },
                                        if (from1m) bull.copy(alpha = 0.75f) else bull,
                                    )
                                    pb.textSize = if (from1m) 20f else 26f
                                    drawContext.canvas.nativeCanvas.drawText(label, x, y + 34f, pb)
                                } else {
                                    val y = yAt(c.high) - 2f
                                    drawPath(
                                        Path().apply {
                                            moveTo(x, y); lineTo(x - sz, y - sz * 1.75f); lineTo(x + sz, y - sz * 1.75f); close()
                                        },
                                        if (from1m) bear.copy(alpha = 0.75f) else bear,
                                    )
                                    ps.textSize = if (from1m) 20f else 26f
                                    drawContext.canvas.nativeCanvas.drawText(label, x, y - 18f, ps)
                                }
                            }
                        }
                        drawRect(
                            axis.copy(alpha = 0.4f),
                            Offset(padL, padT),
                            Size(plotW, plotH),
                            style = Stroke(1.5f),
                        )

                        // Tips 十字线
                        val ti = tipIdx
                        if (ti != null && ti in startI until endI) {
                            val iWin = ti - startI
                            val c = candles[ti]
                            val x = xAt(iWin)
                            val y = yAt(c.close)
                            drawLine(
                                Color(0xFFF0B90B).copy(alpha = 0.85f),
                                Offset(x, padT),
                                Offset(x, padT + plotH),
                                1.5f,
                            )
                            drawLine(
                                Color(0xFFF0B90B).copy(alpha = 0.85f),
                                Offset(padL, y),
                                Offset(padL + plotW, y),
                                1.5f,
                            )
                            drawCircle(Color(0xFFF0B90B), 5f, Offset(x, y))
                        }
                    }

                    // Tips 浮层：时间 + OHLC
                    val ti = tipIdx
                    if (ti != null && ti in candles.indices) {
                        val c = candles[ti]
                        val up = c.close >= c.open
                        Card(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp, start = 72.dp, end = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xE612161C)),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    tipFmt.format(Date(c.openTime)),
                                    color = Color(0xFFF0B90B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "O ${"%.2f".format(c.open)}  H ${"%.2f".format(c.high)}",
                                    color = Color(0xFFB0B8C4),
                                    fontSize = 11.sp,
                                )
                                Text(
                                    "L ${"%.2f".format(c.low)}  C ${"%.2f".format(c.close)}",
                                    color = if (up) bull else bear,
                                    fontSize = 11.sp,
                                )
                                val chg = c.close - c.open
                                val chgPct = if (c.open != 0.0) chg / c.open * 100 else 0.0
                                Text(
                                    "涨跌 ${"%.2f".format(chg)} (${"%+.2f".format(chgPct)}%)",
                                    color = if (up) bull else bear,
                                    fontSize = 11.sp,
                                )
                                Text("点空白处关闭", color = Color(0xFF6B7280), fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
