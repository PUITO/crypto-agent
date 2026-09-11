package com.puito.cryptoagent.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.puito.cryptoagent.domain.EventSim
import com.puito.cryptoagent.domain.StrategyEngine
import com.puito.cryptoagent.net.BinanceClient
import com.puito.cryptoagent.net.HibtClient
import com.puito.cryptoagent.net.LlmClient
import java.util.UUID

class Repository(ctx: Context) {
    private val sp = ctx.getSharedPreferences("agent_local", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val binance = BinanceClient()
    private val llm = LlmClient()
    private val hibt = HibtClient()

    var candles: List<Candle> = emptyList(); private set
    var signals: List<SignalMark> = emptyList(); private set
    var trades: List<SimTrade> = emptyList(); private set
    var stats: Stats = Stats(); private set
    var overlays: List<ChartOverlay> = emptyList(); private set
    private val notified = linkedSetOf<String>() // 保序，便于裁剪
    /** symbol|interval|side -> 上次通知时间戳 */
    private val lastNotifyAt = mutableMapOf<String, Long>()
    private val maxNotifiedKeys = 800


    fun settings(): AppSettings {
        val j = sp.getString("settings", null) ?: return AppSettings()
        return runCatching { gson.fromJson(j, AppSettings::class.java) }.getOrDefault(AppSettings())
    }

    fun saveSettings(s: AppSettings) {
        sp.edit().putString("settings", gson.toJson(s)).apply()
        binance.updateBase(s.binanceBaseUrl)
    }

    fun strategies(): List<StrategyConfig> {
        val j = sp.getString("strategies", null)
        if (j == null) {
            val d = listOf(
                StrategyConfig(
                    UUID.randomUUID().toString(), "默认 RSI", true,
                    listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0, 14)),
                    listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14)),
                )
            )
            saveStrategies(d)
            return d
        }
        val type = object : TypeToken<List<StrategyConfig>>() {}.type
        return runCatching { gson.fromJson<List<StrategyConfig>>(j, type) }.getOrDefault(emptyList())
    }

    fun saveStrategies(list: List<StrategyConfig>) {
        sp.edit().putString("strategies", gson.toJson(list)).apply()
    }

    fun enabledStrategy(): StrategyConfig? = strategies().find { it.enabled }

    fun setOverlays(list: List<ChartOverlay>) {
        overlays = list
        sp.edit().putString("overlays", gson.toJson(list)).apply()
    }

    fun loadOverlays() {
        val j = sp.getString("overlays", null) ?: return
        val type = object : TypeToken<List<ChartOverlay>>() {}.type
        overlays = runCatching { gson.fromJson<List<ChartOverlay>>(j, type) }.getOrDefault(emptyList())
    }

    fun addFibOverlay(fromPrice: Double, toPrice: Double) {
        val hi = maxOf(fromPrice, toPrice)
        val lo = minOf(fromPrice, toPrice)
        val levels = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0).map { r ->
            hi - (hi - lo) * r
        }
        val o = ChartOverlay(
            UUID.randomUUID().toString(), "fib", levels,
            "Fib ${"%.1f".format(lo)}-${"%.1f".format(hi)}",
        )
        setOverlays(overlays + o)
    }

    fun clearOverlays() = setOverlays(emptyList())

    suspend fun refreshMarket(): Result<Unit> {
        val s = settings()
        binance.updateBase(s.binanceBaseUrl)
        return try {
            val bars = binance.fetch(
                s.symbol,
                Interval.from(s.interval),
                s.klineLimit.coerceIn(200, 1000),
            )
            loadOverlays()
            if (s.strategyRunning) {
                val cfg = enabledStrategy()
                if (cfg != null && bars.size >= minBarsForSignal(s.interval)) {
                    runStrategy(s.symbol, s.interval, cfg, bars, notifyNew = false, updateUiState = true)
                } else {
                    candles = bars
                    if (cfg != null && bars.size < minBarsForSignal(s.interval)) {
                        signals = emptyList()
                        trades = emptyList()
                        stats = Stats()
                    }
                }
            } else {
                candles = bars
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 对指定 K 线与周期跑策略。
     * @param updateUiState 是否写入当前界面用的 candles/signals/stats（仅选中周期为 true）
     */
    fun runStrategy(
        symbol: String,
        interval: String,
        cfg: StrategyConfig,
        barData: List<Candle>,
        notifyNew: Boolean,
        updateUiState: Boolean = false,
    ): Pair<List<SignalMark>, Stats> {
        val marks = StrategyEngine.signals(barData, cfg)
        val (tlist, st) = EventSim.backtest(barData, marks, symbol, interval)
        sp.edit()
            .putString("trades_$interval", gson.toJson(tlist))
            .putString("stats_$interval", gson.toJson(st))
            .apply()
        if (updateUiState) {
            candles = barData
            signals = marks
            trades = tlist
            stats = st
        }
        return marks to st
    }

    private fun intervalMs(code: String): Long = when (code) {
        "5m" -> 5 * 60_000L
        "10m" -> 10 * 60_000L
        "30m" -> 30 * 60_000L
        "1h" -> 60 * 60_000L
        else -> 10 * 60_000L
    }

    private fun trimNotified() {
        while (notified.size > maxNotifiedKeys) {
            val first = notified.firstOrNull() ?: break
            notified.remove(first)
        }
    }

    /** 通知关注的「近期已收盘」K 线数量：扩大窗口，保留足够近端历史，避免过窄失真 */
    private fun notifyWatchBars(interval: String): Int = when (interval) {
        "5m" -> 24   // ~2 小时
        "10m" -> 24  // ~4 小时
        "30m" -> 24  // ~12 小时
        "1h" -> 24   // ~1 天
        else -> 24
    }

    /** 策略/指标最少需要的历史根数 */
    private fun minBarsForSignal(interval: String): Int = when (interval) {
        "5m", "10m" -> 120
        "30m", "1h" -> 100
        else -> 100
    }

    /**
     * 多周期新信号过滤：
     * 1) 全量历史只用于计算/回测；通知只针对「启动后新出现」的信号
     * 2) 首次接触某周期：把当前全部历史信号记为已见，不发任何通知（避免一打开狂推）
     * 3) 去重 + 同周期同方向冷却
     * 4) 仅最近已收盘窗口内、且启动后新出现的信号才通知
     */
    private fun freshMarks(
        symbol: String,
        interval: String,
        marks: List<SignalMark>,
        barData: List<Candle>,
        notifyNew: Boolean,
    ): List<SignalMark> {
        if (barData.isEmpty()) return emptyList()
        val endExclusive = if (barData.size >= 2) barData.size - 1 else barData.size
        val watchCount = notifyWatchBars(interval).coerceAtMost(endExclusive.coerceAtLeast(1))
        val from = (endExclusive - watchCount).coerceAtLeast(0)
        val watchTimes = if (endExclusive > from) {
            barData.subList(from, endExclusive).map { it.openTime }.toSet()
        } else emptySet()

        val prefix = "$symbol|$interval|"
        val hadSeed = notified.any { it.startsWith(prefix) }
        // 首次（或策略刚启动后清空过）：种子全部历史，绝不通知旧信号
        if (!hadSeed) {
            marks.forEach { m -> notified.add(key(symbol, interval, m)) }
            trimNotified()
            return emptyList()
        }
        if (!notifyNew) {
            marks.forEach { mark -> notified.add(key(symbol, interval, mark)) }
            trimNotified()
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val cooldown = intervalMs(interval) / 2
        val sideCooldownKey = { side: String -> "$symbol|$interval|$side" }

        return marks
            .filter { it.openTime in watchTimes }
            .filter { key(symbol, interval, it) !in notified }
            .filter {
                val last = lastNotifyAt[sideCooldownKey(it.side)] ?: 0L
                now - last >= cooldown
            }
            .sortedBy { it.openTime }
            .onEach {
                notified.add(key(symbol, interval, it))
                lastNotifyAt[sideCooldownKey(it.side)] = now
            }
            .also { trimNotified() }
    }

    /** 策略启动时调用：清空通知状态并重新种子，避免历史回测信号弹通知 */
    fun resetNotificationState() {
        notified.clear()
        lastNotifyAt.clear()
    }

    /**
     * 同一轮 poll 内跨周期过滤：
     * - 全周期信号都保留通知（不漏周期）
     * - 若同方向、时间接近（≤5m），按该周期总胜率降序，胜率明显更低的标记在 payload 中仍发送但去抖：
     *   同向且 openTime 相差在 5 分钟内，只保留胜率最高的一条 + 与最高相差不超过 15% 的其它周期（共振）
     */
    private fun filterCrossInterval(batch: List<SignalNotifyPayload>): List<SignalNotifyPayload> {
        if (batch.size <= 1) return batch
        val result = mutableListOf<SignalNotifyPayload>()
        val bySide = batch.groupBy { it.mark.side }
        for ((_, list) in bySide) {
            val sorted = list.sortedWith(
                compareByDescending<SignalNotifyPayload> { it.intervalWinRatePct }
                    .thenBy { intervalMs(it.interval) }, // 同胜率优先更短周期（更及时）
            )
            val best = sorted.first()
            result.add(best)
            for (p in sorted.drop(1)) {
                val closeInTime = kotlin.math.abs(p.mark.openTime - best.mark.openTime) <= 5 * 60_000L
                if (!closeInTime) {
                    result.add(p)
                    continue
                }
                // 时间接近：保留胜率不低于最佳 15 个百分点的共振周期
                if (p.intervalWinRatePct >= best.intervalWinRatePct - 15.0) {
                    result.add(p)
                }
                // 否则视为噪声，丢弃通知（仍已写入 notified，避免反复尝试）
            }
        }
        return result.sortedWith(
            compareByDescending<SignalNotifyPayload> { it.intervalWinRatePct }
                .thenBy { it.mark.openTime },
        )
    }

    /**
     * 后台轮询：对全部周期 5m/10m/30m/1h 生成信号并通知。
     * 实盘仅对当前选中周期执行，避免四周期重复下单。
     */
    suspend fun poll(): List<SignalNotifyPayload> {
        val s = settings()
        if (!s.strategyRunning) return emptyList()
        binance.updateBase(s.binanceBaseUrl)
        val cfg = enabledStrategy() ?: return emptyList()
        val allIntervals = listOf("5m", "10m", "30m", "1h")
        val batch = mutableListOf<SignalNotifyPayload>()
        for (iv in allIntervals) {
            try {
                val bars = binance.fetch(s.symbol, Interval.from(iv), s.klineLimit.coerceIn(200, 1000))
                if (bars.size < minBarsForSignal(iv)) {
                    // 历史不足时不算信号，避免 RSI/MA 等在短样本上失真
                    continue
                }
                val (marks, st) = runStrategy(
                    s.symbol, iv, cfg, bars,
                    notifyNew = true,
                    updateUiState = (iv == s.interval),
                )
                val fresh = freshMarks(s.symbol, iv, marks, bars, notifyNew = true)
                for (m in fresh) {
                    val ai = if (s.hibt.aiEvaluate) {
                        evaluateSignal(s, m, iv, st.winRate * 100, st.trades)
                    } else null
                    batch.add(
                        SignalNotifyPayload(
                            mark = m,
                            interval = iv,
                            intervalWinRatePct = st.winRate * 100,
                            intervalTrades = st.trades,
                            ai = ai,
                        ),
                    )
                }
            } catch (_: Exception) {
            }
        }
        val out = filterCrossInterval(batch)
        for (p in out) {
            if (s.hibt.autoTrade && p.interval == s.interval) {
                maybeAutoOrder(s, p.mark, p.ai)
            }
        }
        return out
    }

    /** 仅做 AI 评估，不依赖 autoTrade；hist 为该时间段本地总胜率 */
    suspend fun evaluateSignal(
        s: AppSettings,
        m: SignalMark,
        interval: String = s.interval,
        hist: Double = stats.winRate * 100,
        tradeCount: Int = stats.trades,
    ): AiEvalResult {
        val h = s.hibt
        val dir = if (m.side == "B") "买入/看涨" else "卖出/看跌"
        if (s.llmApiKey.isBlank()) {
            return AiEvalResult(
                winRatePct = hist,
                summary = "未配置 LLM Key，使用该周期历史回测胜率 " + "%.1f".format(hist) + "% 作为参考",
                passThreshold = hist >= h.aiMinWinRate,
                thresholdPct = h.aiMinWinRate,
                error = "no_llm_key",
            )
        }
        return try {
            val sys =
                "你是事件合约信号评估助手。根据给定信息估计该方向在指定周期内的胜率(0-100)。" +
                    "先给一行：WINRATE:数字 再给一两句中文理由。不要编造未提供的数据。"
            val user =
                "品种: ${s.symbol} 周期: $interval 方向: $dir (${m.side}) " +
                    "信号价格: ${m.price} 该周期本地回测胜率: " + "%.1f".format(hist) +
                    "% 成交笔数: $tradeCount 阈值: ${h.aiMinWinRate}%"
            val ans = llm.chat(s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, user)
            val winEst = parseWinRate(ans) ?: hist
            val reason = ans.lines()
                .filter { !it.uppercase().contains("WINRATE") }
                .joinToString(" ")
                .trim()
                .ifBlank { ans.take(120) }
            AiEvalResult(
                winRatePct = winEst,
                summary = reason.take(160),
                passThreshold = winEst >= h.aiMinWinRate,
                thresholdPct = h.aiMinWinRate,
            )
        } catch (e: Exception) {
            AiEvalResult(
                winRatePct = hist,
                summary = "AI 调用失败，回退历史胜率 " + "%.1f".format(hist) + "%",
                passThreshold = hist >= h.aiMinWinRate,
                thresholdPct = h.aiMinWinRate,
                error = e.message,
            )
        }
    }

    private fun parseWinRate(text: String): Double? {
        val patterns = listOf(
            Regex("""WINRATE\s*[:=：]\s*(\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,3}(?:\.\d+)?)\s*%"""),
            Regex("""\b(\d{1,3}(?:\.\d+)?)\b"""),
        )
        for (p in patterns) {
            val v = p.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (v != null && v in 0.0..100.0) return v
        }
        return null
    }

    private suspend fun maybeAutoOrder(s: AppSettings, m: SignalMark, ai: AiEvalResult?) {
        val h = s.hibt
        if (!h.autoTrade) return
        val pass = when {
            h.aiEvaluate && ai != null -> ai.passThreshold == true
            h.aiEvaluate -> (stats.winRate * 100) >= h.aiMinWinRate
            else -> true
        }
        if (!pass) return
        val unit = Interval.from(s.interval).timeUnit
        hibt.placeEventOrder(h, s.symbol, m.side == "B", h.defaultAmount, unit)
    }

    suspend fun hibtTest() = hibt.testConnectivity(settings().hibt)

    suspend fun hibtPlace(up: Boolean, amount: Double? = null): HibtClient.OrderResult {
        val s = settings()
        val unit = Interval.from(s.interval).timeUnit
        return hibt.placeEventOrder(s.hibt, s.symbol, up, amount ?: s.hibt.defaultAmount, unit)
    }

    /** Chat 指令：策略/指标/斐波那契 */
    suspend fun handleChatCommand(user: String): String? {
        val t = user.trim()
        when {
            t.startsWith("/fib") || t.contains("斐波那契") -> {
                if (candles.size < 20) return "K 线不足，无法绘制斐波那契"
                val win = candles.takeLast(50)
                val hi = win.maxOf { it.high }
                val lo = win.minOf { it.low }
                addFibOverlay(lo, hi)
                return "已添加临时斐波那契（${"%.1f".format(lo)} ~ ${"%.1f".format(hi)}），见行情图。发送「清除绘图」可删除。"
            }
            t.contains("清除绘图") || t == "/clear_overlay" -> {
                clearOverlays()
                return "已清除临时绘图"
            }
            t.startsWith("/ma") || t.matches(Regex("""打开\s*MA\s*\d+""", RegexOption.IGNORE_CASE)) -> {
                val p = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: 20
                toggleChartIndicator("MA$p", p, true)
                return "已开启图表指标 MA$p（设置里可改周期）"
            }
            t.contains("列出策略") || t == "/strategies" -> {
                return strategies().joinToString("\n") { "${if (it.enabled) "●" else "○"} ${it.title} (${it.id.take(6)})" }
                    .ifBlank { "暂无策略" }
            }
            t.startsWith("启用策略") || t.startsWith("/enable ") -> {
                val name = t.removePrefix("启用策略").removePrefix("/enable").trim()
                val list = strategies().map { it.copy(enabled = it.title.contains(name) || it.id.startsWith(name)) }
                if (list.none { it.enabled }) return "未找到策略: $name"
                saveStrategies(list)
                return "已启用匹配「$name」的策略"
            }
            t.startsWith("添加策略") || t.startsWith("/add_strategy") -> {
                val title = t.substringAfter("：").substringAfter(":").ifBlank { "Chat策略${strategies().size + 1}" }
                val cfg = StrategyConfig(UUID.randomUUID().toString(), title.trim(), false)
                saveStrategies(strategies() + cfg)
                return "已添加策略「${cfg.title}」，请到策略页启用并编辑条件。"
            }
        }
        return null
    }

    private fun toggleChartIndicator(name: String, period: Int, enabled: Boolean) {
        val s = settings()
        val id = name.lowercase()
        val exists = s.chartIndicators.any { it.id == id || it.name.equals(name, true) }
        val list = if (exists) {
            s.chartIndicators.map {
                if (it.id == id || it.name.equals(name, true)) it.copy(enabled = enabled, period = period)
                else it
            }
        } else {
            s.chartIndicators + ChartIndicatorPref(id, name, enabled, period)
        }
        saveSettings(s.copy(chartIndicators = list))
    }

    /**
     * 将最近 [n] 根 K 线整理成文本，供 LLM 分析（控制长度避免超上下文）。
     */
    fun buildMarketContext(n: Int = 30): String {
        val s = settings()
        val count = n.coerceIn(5, 120)
        if (candles.isEmpty()) {
            return "当前无K线数据，请先在行情页刷新。"
        }
        val bars = candles.takeLast(count)
        val first = bars.first()
        val last = bars.last()
        val hi = bars.maxOf { it.high }
        val lo = bars.minOf { it.low }
        val chg = last.close - first.open
        val chgPct = if (first.open != 0.0) chg / first.open * 100 else 0.0
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("【行情摘要】")
        sb.appendLine("品种: ${s.symbol}  周期: ${s.interval}")
        sb.appendLine("样本: 最近 ${bars.size} 根K线（共缓存 ${candles.size}）")
        sb.appendLine("区间: ${fmt.format(java.util.Date(first.openTime))} ~ ${fmt.format(java.util.Date(last.openTime))}")
        sb.appendLine("开:${"%.4f".format(first.open)} 最新收:${"%.4f".format(last.close)}")
        sb.appendLine("区间高:${"%.4f".format(hi)} 区间低:${"%.4f".format(lo)}")
        sb.appendLine("区间涨跌: ${"%.4f".format(chg)} (${"%+.2f".format(chgPct)}%)")
        if (stats.trades > 0) {
            sb.appendLine("本地模拟: ${stats.trades}笔 胜率 ${"%.1f".format(stats.winRate * 100)}%")
        }
        sb.appendLine("【K线明细 time,O,H,L,C】")
        // 过长时抽样：头尾多、中间抽稀
        val lines = if (bars.size <= 40) {
            bars
        } else {
            val head = bars.take(12)
            val tail = bars.takeLast(12)
            val mid = bars.drop(12).dropLast(12)
            val step = (mid.size / 16).coerceAtLeast(1)
            head + mid.filterIndexed { i, _ -> i % step == 0 }.take(16) + tail
        }
        for (c in lines) {
            sb.appendLine(
                "${fmt.format(java.util.Date(c.openTime))}," +
                    "${"%.4f".format(c.open)},${"%.4f".format(c.high)}," +
                    "${"%.4f".format(c.low)},${"%.4f".format(c.close)}",
            )
        }
        if (lines.size < bars.size) {
            sb.appendLine("（中间已抽样，共输出 ${lines.size}/${bars.size} 根）")
        }
        return sb.toString()
    }

    /**
     * @param attachMarketBars 若 >0，将最近 N 根行情附到 user 消息供分析
     */
    suspend fun chat(user: String, attachMarketBars: Int = 0): String {
        handleChatCommand(user)?.let { return it }
        val s = settings()
        val market = if (attachMarketBars > 0) buildMarketContext(attachMarketBars) else ""
        val sys = """
            你是手机端 Crypto Agent 助手，擅长加密行情与事件合约思路分析。
            当前: ${s.symbol} ${s.interval}, 本地K线缓存 ${candles.size} 根, 模拟 ${stats.trades} 笔, 胜率 ${"%.1f".format(stats.winRate * 100)}%.
            若用户消息附带【行情摘要】与K线明细，请据此分析趋势、支撑阻力、波动与风险，用简洁中文；不要编造未给出的数据。
            本地命令仍可用: 斐波那契, 清除绘图, 打开MA20, 列出策略, 添加策略：名称
        """.trimIndent()
        val userPayload = if (market.isNotBlank()) {
            "$market

【用户问题】
$user"
        } else {
            user
        }
        return llm.chat(s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, userPayload)
    }


    private fun key(symbol: String, interval: String, m: SignalMark) =
        "$symbol|$interval|${m.openTime}|${m.side}"
}
