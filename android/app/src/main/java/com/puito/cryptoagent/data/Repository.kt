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
    private val notified = mutableSetOf<String>()

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
            val bars = binance.fetch(s.symbol, Interval.from(s.interval), s.klineLimit)
            loadOverlays()
            if (s.strategyRunning) {
                val cfg = enabledStrategy()
                if (cfg != null) {
                    runStrategy(s.symbol, s.interval, cfg, bars, notifyNew = false, updateUiState = true)
                } else {
                    candles = bars
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

    private fun freshMarks(
        symbol: String,
        interval: String,
        marks: List<SignalMark>,
        barData: List<Candle>,
        notifyNew: Boolean,
    ): List<SignalMark> {
        if (!notifyNew) {
            marks.forEach { notified.add(key(symbol, interval, it)) }
            return emptyList()
        }
        val recent = barData.takeLast(6).map { it.openTime }.toSet()
        return marks.filter { it.openTime in recent && key(symbol, interval, it) !in notified }
            .onEach { notified.add(key(symbol, interval, it)) }
    }

    /**
     * 后台轮询：对全部周期 5m/10m/30m/1h 生成信号并通知，避免只盯当前选中周期漏信号。
     * AI 评估与真实下单解耦；实盘仅对「当前设置的默认周期」执行（避免四周期重复下单）。
     */
    suspend fun poll(): List<SignalNotifyPayload> {
        val s = settings()
        if (!s.strategyRunning) return emptyList()
        binance.updateBase(s.binanceBaseUrl)
        val cfg = enabledStrategy() ?: return emptyList()
        val allIntervals = listOf("5m", "10m", "30m", "1h")
        val out = mutableListOf<SignalNotifyPayload>()
        for (iv in allIntervals) {
            try {
                val bars = binance.fetch(s.symbol, Interval.from(iv), s.klineLimit)
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
                    out.add(
                        SignalNotifyPayload(
                            mark = m,
                            interval = iv,
                            intervalWinRatePct = st.winRate * 100,
                            intervalTrades = st.trades,
                            ai = ai,
                        ),
                    )
                    // 实盘只跟当前选中周期，防止同一信号在多周期重复下单
                    if (s.hibt.autoTrade && iv == s.interval) {
                        maybeAutoOrder(s, m, ai)
                    }
                }
            } catch (e: Exception) {
                // 单周期失败不阻断其它周期
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

    suspend fun chat(user: String): String {
        handleChatCommand(user)?.let { return it }
        val s = settings()
        val sys = """
            你是手机端 Crypto Agent 助手。本地事件合约模拟。
            当前: ${s.symbol} ${s.interval}, K线 ${candles.size}, 模拟 ${stats.trades} 笔, 胜率 ${"%.1f".format(stats.winRate * 100)}%.
            用户可用命令: 斐波那契, 清除绘图, 打开MA20, 列出策略, 启用策略名, 添加策略：名称
            用简洁中文回答。
        """.trimIndent()
        return llm.chat(s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, user)
    }

    private fun key(symbol: String, interval: String, m: SignalMark) =
        "$symbol|$interval|${m.openTime}|${m.side}"
}
