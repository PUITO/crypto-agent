package com.puito.cryptoagent.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.puito.cryptoagent.domain.EventSim
import com.puito.cryptoagent.domain.StrategyEngine
import com.puito.cryptoagent.net.BinanceClient
import com.puito.cryptoagent.net.HibtClient
import com.puito.cryptoagent.net.HibtWebSession
import com.puito.cryptoagent.notify.Notify
import com.puito.cryptoagent.net.LlmClient
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Repository(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val sp = ctx.getSharedPreferences("agent_local", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val binance = BinanceClient()
    private val llm = LlmClient()
    /** 已实盘提交过的信号键，防止同一信号重复下单 */
    private val placedOrderKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val placeLock = Any()

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
        "1m" -> 60_000L
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

    /** 仅盯最近已收盘 1～2 根，避免「半小时前的信号」被当成新信号推送 */
    private fun notifyWatchBars(interval: String): Int = when (interval) {
        "1m" -> 2
        else -> 2
    }

    /** 收盘后允许的最大延迟；1m 主源更严，避免过期推送 */
    private fun maxLateMs(interval: String): Long = when (interval) {
        "1m" -> 50_000L
        "5m" -> 90_000L
        "10m" -> 120_000L
        "30m" -> 180_000L
        "1h" -> 240_000L
        else -> 120_000L
    }

    private fun isSignalExpired(interval: String, openTime: Long, now: Long = System.currentTimeMillis()): Boolean {
        val closedAt = openTime + intervalMs(interval)
        return now - closedAt > maxLateMs(interval)
    }

    /** 策略/指标最少需要的历史根数 */
    private fun minBarsForSignal(interval: String): Int = when (interval) {
        "1m" -> 120
        "5m", "10m" -> 120
        "30m", "1h" -> 100
        else -> 100
    }

    /**
     * 高周期确认：1m 出信号后，用目标交易周期的近端结构做同向过滤，减少噪声。
     * 计算仍用完整 HT 历史；此处只做方向偏置，不替代 1m 触发。
     */
    private fun higherTfAgrees(barsHt: List<Candle>, side: String): Boolean {
        if (barsHt.size < 25) return true
        val closed = if (barsHt.size >= 2) barsHt.dropLast(1) else barsHt
        val window = closed.takeLast(20)
        val ma = window.map { it.close }.average()
        val last = window.last().close
        // 软确认：不与近端均线强烈逆向即可
        return when (side) {
            "B" -> last >= ma * 0.997
            "S" -> last <= ma * 1.003
            else -> true
        }
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
            .filter { !isSignalExpired(interval, it.openTime, now) }
            .filter { key(symbol, interval, it) !in notified }
            .filter {
                val last = lastNotifyAt[sideCooldownKey(it.side)] ?: 0L
                // 冷却缩短为周期的 1/4，避免漏掉相邻新信号
                now - last >= (cooldown / 2).coerceAtLeast(30_000L)
            }
            .sortedByDescending { it.openTime } // 最新优先
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
     * 后台轮询（低延迟）：
     * - 以 1m K 线为信号触发主源（约每分钟可出新信号，不再等 5m 收盘）
     * - 对每个事件合约周期 5m/10m/30m/1h：用该周期完整历史做回测统计 + 同向软确认
     * - 通知打在对应交易周期上，便于 HiBT 时间单位与胜率展示
     */
    suspend fun poll(): List<SignalNotifyPayload> = withContext(Dispatchers.IO) {
        val s = settings()
        if (!s.strategyRunning) return@withContext emptyList()
        binance.updateBase(s.binanceBaseUrl)
        val cfg = enabledStrategy() ?: return@withContext emptyList()
        val tradeIntervals = listOf("5m", "10m", "30m", "1h")
        val limit1m = s.klineLimit.coerceIn(200, 500)
        val limitHt = s.klineLimit.coerceIn(200, 500)

        // 1) 主源：1m
        val bars1m = try {
            binance.fetch(s.symbol, Interval.M1, limit1m)
        } catch (_: Exception) {
            emptyList()
        }
        if (bars1m.size < minBarsForSignal("1m")) return@withContext emptyList()

        val (marks1m, _) = runStrategy(
            s.symbol, "1m", cfg, bars1m,
            notifyNew = true,
            updateUiState = false,
        )
        val fresh1m = freshMarks(s.symbol, "1m", marks1m, bars1m, notifyNew = true)
        if (fresh1m.isEmpty()) return@withContext emptyList()

        // 2) 并行拉高周期，确认 + 统计
        val batch = coroutineScope {
            tradeIntervals.map { iv ->
                async {
                    try {
                        val barsHt = binance.fetch(s.symbol, Interval.from(iv), limitHt)
                        if (barsHt.size < minBarsForSignal(iv)) return@async emptyList()
                        val (_, st) = runStrategy(
                            s.symbol, iv, cfg, barsHt,
                            notifyNew = false,
                            updateUiState = (iv == s.interval),
                        )
                        fresh1m.mapNotNull { m ->
                            if (!higherTfAgrees(barsHt, m.side)) return@mapNotNull null
                            if (isSignalExpired("1m", m.openTime)) return@mapNotNull null
                            val ai = if (s.hibt.aiEvaluate) {
                                val toMs = s.llmTimeoutSec.coerceIn(10, 300) * 1000L
                                withTimeoutOrNull(toMs) {
                                    evaluateSignal(s, m, iv, barsHt, st.winRate * 100, st.trades)
                                }
                            } else null
                            SignalNotifyPayload(
                                mark = m,
                                interval = iv,
                                intervalWinRatePct = st.winRate * 100,
                                intervalTrades = st.trades,
                                ai = ai,
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val timely = batch.filter { !isSignalExpired("1m", it.mark.openTime) }
        // 跨周期去重：同一 1m 信号可能打到多个 HT，保留当前选中周期优先，否则胜率高的
        val dedup = linkedMapOf<String, SignalNotifyPayload>()
        for (p in timely.sortedWith(
            compareByDescending<SignalNotifyPayload> { it.interval == s.interval }
                .thenByDescending { it.intervalWinRatePct },
        )) {
            val k = "${p.mark.openTime}|${p.mark.side}"
            if (k !in dedup) dedup[k] = p
        }
        val out = filterCrossInterval(dedup.values.toList())

        // 自动下单：按配置多选周期（与行情全局 interval 解耦）；用未去重的 timely 以便多周期同时下
        if (s.hibt.autoTrade) {
            val selected = s.hibt.autoIntervals
                .map { it.trim().lowercase() }
                .filter { it in setOf("5m", "10m", "30m", "1h") }
                .ifEmpty { listOf(s.interval) }
            // 1m 主源 bars 供防追单
            val barsForChase = bars1m
            val orderList = timely.filter { it.interval.lowercase() in selected }
            for (p in orderList) {
                if (s.hibt.antiChaseEnabled && isOneSidedChase(barsForChase, p.mark.side, s.hibt.antiChaseBars)) {
                    continue
                }
                maybeAutoOrder(s, p.mark, p.ai, p.interval)
            }
        }
        out
    }

    /**
     * AI 评估：根据该周期行情 K 线估计「本信号方向在对应交易时间段」的胜率。
     * 与本地回测总胜率解耦；无 Key / 调用失败时不把总胜率当作 AI 结果。
     */
    suspend fun evaluateSignal(
        s: AppSettings,
        m: SignalMark,
        interval: String,
        barData: List<Candle>,
        hist: Double = stats.winRate * 100,
        tradeCount: Int = stats.trades,
    ): AiEvalResult {
        val h = s.hibt
        val dir = if (m.side == "B") "买入/看涨" else "卖出/看跌"
        val threshold = h.aiMinWinRate
        if (s.llmApiKey.isBlank()) {
            return AiEvalResult(
                winRatePct = null,
                summary = "未配置 LLM Key，无法做行情 AI 评估（不会用回测总胜率冒充）",
                passThreshold = false,
                thresholdPct = threshold,
                error = "no_llm_key",
            )
        }
        if (barData.size < 10) {
            return AiEvalResult(
                winRatePct = null,
                summary = "该周期 K 线不足，无法评估",
                passThreshold = false,
                thresholdPct = threshold,
                error = "insufficient_bars",
            )
        }
        return try {
            val market = buildSignalMarketBrief(s.symbol, interval, barData, m)
            val sys = (
                "你是加密事件合约信号评估助手。" +
                    "仅根据提供的行情K线与信号，估计该信号方向在本交易周期内获胜的概率(0-100)。" +
                    "必须基于K线结构独立判断，禁止把本地回测总胜率直接当答案。" +
                    "第一行严格输出 WINRATE:数字 ，随后1-3句中文理由。"
            )
            val user = (
                market +
                    "\n【信号】方向: " + dir + " (" + m.side + ") 信号价: " + m.price +
                    "\n【交易周期】" + interval +
                    "\n【参考-本地回测总胜率】" + "%.1f".format(hist) + "% / " + tradeCount + "笔（勿直接照抄）" +
                    "\n【程序阈值】" + "%.1f".format(threshold) + "%（你只需输出WINRATE，是否达阈值由程序判断）"
            )
            val ans = llm.chat(s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, user, s.llmTimeoutSec)
            val winEst = parseWinRate(ans, avoidEcho = hist)
            if (winEst == null) {
                return@evaluateSignal AiEvalResult(
                    winRatePct = null,
                    summary = "AI 未返回可解析的 WINRATE: " + ans.take(100),
                    passThreshold = false,
                    thresholdPct = threshold,
                    error = "parse_fail",
                )
            }
            val reason = ans.lines()
                .filter { !it.uppercase().contains("WINRATE") }
                .joinToString(" ")
                .trim()
                .ifBlank { ans.take(120) }
            val pass = winEst >= threshold
            AiEvalResult(
                winRatePct = winEst,
                summary = reason.take(180),
                passThreshold = pass,
                thresholdPct = threshold,
            )
        } catch (e: Exception) {
            AiEvalResult(
                winRatePct = null,
                summary = "AI 调用失败: " + (e.message ?: "unknown"),
                passThreshold = false,
                thresholdPct = threshold,
                error = e.message,
            )
        }
    }

    private fun buildSignalMarketBrief(
        symbol: String,
        interval: String,
        barData: List<Candle>,
        m: SignalMark,
    ): String {
        val n = minOf(40, barData.size)
        val bars = barData.takeLast(n)
        val last = bars.last()
        val hi = bars.maxOf { it.high }
        val lo = bars.minOf { it.low }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("【行情】" + symbol + " 周期=" + interval + " 近" + bars.size + "根")
        sb.appendLine(
            "最新: " + fmt.format(java.util.Date(last.openTime)) +
                " O=" + "%.4f".format(last.open) +
                " H=" + "%.4f".format(last.high) +
                " L=" + "%.4f".format(last.low) +
                " C=" + "%.4f".format(last.close),
        )
        sb.appendLine("近端区间高=" + "%.4f".format(hi) + " 低=" + "%.4f".format(lo))
        sb.appendLine("信号时间: " + fmt.format(java.util.Date(m.openTime)))
        sb.appendLine("【K线 time,O,H,L,C】")
        val step = if (bars.size <= 24) 1 else (bars.size + 23) / 24
        var i = 0
        while (i < bars.size) {
            val c = bars[i]
            sb.appendLine(
                fmt.format(java.util.Date(c.openTime)) + "," +
                    "%.4f".format(c.open) + "," + "%.4f".format(c.high) + "," +
                    "%.4f".format(c.low) + "," + "%.4f".format(c.close),
            )
            i += step
        }
        return sb.toString()
    }

    private fun parseWinRate(text: String, avoidEcho: Double? = null): Double? {
        val primary = Regex("WINRATE\\s*[:=：]\\s*(\\d{1,3}(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        primary.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { v ->
            if (v in 0.0..100.0) return v
        }
        val pct = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*%")
        val candidates = pct.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in 0.0..100.0 }
            .toList()
        if (candidates.isEmpty()) return null
        if (avoidEcho != null) {
            val filtered = candidates.filter { kotlin.math.abs(it - avoidEcho) > 0.15 }
            if (filtered.isNotEmpty()) return filtered.first()
        }
        return candidates.firstOrNull()
    }

    /**
     * 行情页选中周期 → 事件合约 timeUnit（分钟）。
     * 只认 5m/10m/30m/1h；与官网档位 5/10/30/60 对齐（另有 15 可选）。
     */
    private fun eventTimeUnitMinutes(intervalCode: String): Int {
        return when (intervalCode.trim().lowercase()) {
            "5m" -> 5
            "10m" -> 10
            "15m" -> 15
            "30m" -> 30
            "1h", "60m" -> 60
            "1m" -> 5 // 信号用 1m K 线时，合约仍按下单页/默认 5m，避免误开 1 分钟不存在的档
            else -> Interval.from(intervalCode).timeUnit.let { u ->
                if (u in setOf(5, 10, 15, 30, 60)) u else 5
            }
        }
    }

    /**
     * 大单边行情防追单：近 N 根 1m K 几乎同向且涨跌占窗口波幅主导时，
     * 禁止继续同向自动下单（避免死硬追涨杀跌）。
     */
    private fun isOneSidedChase(bars: List<Candle>, side: String, barCount: Int): Boolean {
        val n = barCount.coerceIn(4, 20)
        if (bars.size < n) return false
        val win = bars.takeLast(n)
        val ups = win.count { it.close > it.open }
        val downs = win.count { it.close < it.open }
        val first = win.first().open
        val last = win.last().close
        val move = last - first
        val range = (win.maxOf { it.high } - win.minOf { it.low }).coerceAtLeast(1e-12)
        val dominance = kotlin.math.abs(move) / range
        // 至少约 80% K 线同向，且净位移占窗口高低波幅 >= 55%
        val strongUp = ups >= (n * 4 / 5) && move > 0 && dominance >= 0.55
        val strongDown = downs >= (n * 4 / 5) && move < 0 && dominance >= 0.55
        return when (side) {
            "B" -> strongUp   // 已大涨还买涨 = 追多
            "S" -> strongDown // 已大跌还买跌 = 追空
            else -> false
        }
    }

    private suspend fun maybeAutoOrder(
        s: AppSettings,
        m: SignalMark,
        ai: AiEvalResult?,
        intervalCode: String,
    ) {
        val h = s.hibt
        if (!h.autoTrade) return
        if (h.aiEvaluate) {
            if (ai?.winRatePct == null || ai.passThreshold != true) return
        }
        val unit = eventTimeUnitMinutes(intervalCode)
        val key = "${s.symbol}|${m.side}|${m.openTime}|${unit}"
        synchronized(placeLock) {
            if (!placedOrderKeys.add(key)) {
                return
            }
        }
        HibtWebSession.appendLog("自动下单触发 ${s.symbol} ${m.side} tu=${unit}m iv=$intervalCode")
        val result = placePreferWeb(
            directionUp = m.side == "B",
            amount = h.defaultAmount,
            symbol = s.symbol,
            timeUnit = unit,
            cfg = h,
        )
        HibtWebSession.appendLog("自动下单结果 ok=${result.ok} dry=${result.dryRun} ${result.message.take(120)}")
        if (result.dryRun || !result.ok) {
            placedOrderKeys.remove(key)
        }
    }

    /**
     * 统一只走 WebView 下单，禁止原生时间戳 v / 直连接口下单（避免登录失效与错误 v）。
     */
    private suspend fun placePreferWeb(
        directionUp: Boolean,
        amount: Double,
        symbol: String,
        timeUnit: Int,
        cfg: HibtSettings,
    ): HibtClient.OrderResult {
        if (HibtWebSession.peek() == null) {
            val msg = "【必须 WebView】请先打开 WebView 登录并「隐藏保活」。已禁用原生接口下单（不再使用时间戳 v）。"
            Notify.orderResult(
                appCtx, ok = false, dryRun = true, message = msg,
                sideLabel = if (directionUp) "买涨" else "买跌",
                amount = amount, timeUnit = timeUnit,
            )
            return HibtClient.OrderResult(false, msg, dryRun = true, raw = msg)
        }
        val outcome = HibtWebSession.placeOrder(
            directionUp = directionUp,
            amount = amount,
            symbol = symbol,
            timeUnit = timeUnit,
            dryRun = cfg.dryRun,
            timeoutSec = cfg.placeTimeoutSec,
        ) ?: HibtWebSession.PlaceOutcome(
            false,
            "WebView 无响应，请重新打开 WebView 并进入合约/订单页",
            cfg.dryRun,
        )
        val result = HibtClient.OrderResult(
            ok = outcome.ok,
            message = "[WebView] ${outcome.message}",
            dryRun = outcome.dryRun,
            raw = outcome.message,
        )
        HibtWebSession.appendLog(
            "placePreferWeb ok=${result.ok} dry=${result.dryRun} ${result.message.take(160)}"
        )
        // 真实下单或失败都通知；Dry-Run 也通知一条便于确认走的是 WebView
        Notify.orderResult(
            appCtx,
            ok = result.ok,
            dryRun = result.dryRun,
            message = result.message,
            sideLabel = if (directionUp) "买涨 B" else "买跌 S",
            amount = amount,
            timeUnit = timeUnit,
        )
        return result
    }

    suspend fun hibtTest() = hibt.testConnectivity(settings().hibt)

    suspend fun hibtPlace(up: Boolean, amount: Double? = null, intervalCode: String? = null): HibtClient.OrderResult {
        val s = settings()
        val iv = intervalCode
            ?: s.hibt.autoIntervals.firstOrNull()
            ?: s.interval
        val unit = eventTimeUnitMinutes(iv)
        val amt = amount ?: s.hibt.defaultAmount
        return placePreferWeb(up, amt, s.symbol, unit, s.hibt)
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
            market + "\n\n" + "【用户问题】" + "\n" + user
        } else {
            user
        }
        return llm.chat(s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, userPayload, s.llmTimeoutSec)
    }


    private fun key(symbol: String, interval: String, m: SignalMark) =
        "$symbol|$interval|${m.openTime}|${m.side}"
}
