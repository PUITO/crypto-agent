package com.puito.cryptoagent.domain

import com.puito.cryptoagent.data.*
import java.util.UUID
import kotlin.math.sqrt

object StrategyEngine {
    fun signals(candles: List<Candle>, cfg: StrategyConfig): List<SignalMark> {
        val kind = try {
            cfg.kind
        } catch (_: Exception) {
            StrategyKind.RULES
        } ?: StrategyKind.RULES
        return when (kind) {
            StrategyKind.ALGO -> AlgoEngine.signals(candles, cfg)
            StrategyKind.MODEL -> ModelEngine.signals(candles, cfg)
            StrategyKind.RULES -> ruleSignals(candles, cfg)
        }
    }

    /**
     * 先按 (指标,周期) 只算一遍序列，再扫 K 线。
     */
    private fun ruleSignals(candles: List<Candle>, cfg: StrategyConfig): List<SignalMark> {
        if (candles.isEmpty()) return emptyList()
        val closes = candles.map { it.close }
        val rules = cfg.buyRules + cfg.sellRules
        val cache = HashMap<String, List<Double?>>()
        fun series(r: Rule): List<Double?> {
            val period = r.period.coerceIn(2, 200)
            val key = "${r.indicator.name}|$period"
            return cache.getOrPut(key) {
                when (r.indicator) {
                    IndicatorType.RSI -> Indicators.rsi(closes, period)
                    IndicatorType.MACD -> Indicators.macdHist(closes)
                    IndicatorType.KDJ_J -> Indicators.kdjJ(candles)
                    IndicatorType.CLOSE -> closes.map { it as Double? }
                    IndicatorType.MA -> Indicators.sma(closes, period)
                    IndicatorType.EMA -> Indicators.ema(closes, period)
                    IndicatorType.BOLL -> Indicators.boll(closes, period).second
                    IndicatorType.BOLL_PCT -> Indicators.bollPct(closes, period)
                    IndicatorType.MA_BIAS -> {
                        val ma = Indicators.sma(closes, period)
                        closes.indices.map { i ->
                            val m = ma.getOrNull(i) ?: return@map null
                            if (m == 0.0) null else (closes[i] / m - 1.0) * 100.0
                        }
                    }
                    IndicatorType.EMA_BIAS -> {
                        val ema = Indicators.ema(closes, period)
                        closes.indices.map { i ->
                            val m = ema.getOrNull(i) ?: return@map null
                            if (m == 0.0) null else (closes[i] / m - 1.0) * 100.0
                        }
                    }
                }
            }
        }
        rules.forEach { series(it) }
        val out = mutableListOf<SignalMark>()
        for (i in candles.indices) {
            fun side(rs: List<Rule>): Boolean = rs.any { r ->
                val v = series(r).getOrNull(i) ?: return@any false
                when (r.op) {
                    CompareOp.GT -> v > r.value
                    CompareOp.GTE -> v >= r.value
                    CompareOp.LT -> v < r.value
                    CompareOp.LTE -> v <= r.value
                }
            }
            val b = side(cfg.buyRules)
            val s = side(cfg.sellRules)
            when {
                b && !s -> out.add(SignalMark(candles[i].openTime, "B", candles[i].close))
                s && !b -> out.add(SignalMark(candles[i].openTime, "S", candles[i].close))
            }
        }
        return out
    }
}

/**
 * 参数化算法引擎（非任意代码执行，安全可控；LLM 负责选算法+调参+写说明）。
 */
object AlgoEngine {
    private fun p(params: Map<String, Double>, key: String, def: Double): Double =
        params[key] ?: def

    fun signals(candles: List<Candle>, cfg: StrategyConfig): List<SignalMark> {
        if (candles.size < 30) return emptyList()
        return when (cfg.algoId) {
            AlgoIds.PEARSON_TRIPLE -> pearsonTriple(candles, cfg.algoParams)
            AlgoIds.BREAKOUT -> breakout(candles, cfg.algoParams)
            else -> trendFollow(candles, cfg.algoParams)
        }
    }

    /** 顺势/单边：快慢 EMA 同向 + 连续收盘确认，避免震荡反复开单 */
    private fun trendFollow(candles: List<Candle>, params: Map<String, Double>): List<SignalMark> {
        val fastN = p(params, "fast", 12.0).toInt().coerceIn(3, 80)
        val slowN = p(params, "slow", 26.0).toInt().coerceIn(fastN + 1, 120)
        val need = p(params, "consecutive", 3.0).toInt().coerceIn(1, 10)
        val cooldown = p(params, "cooldown", 3.0).toInt().coerceIn(0, 30)
        val closes = candles.map { it.close }
        val fast = Indicators.ema(closes, fastN)
        val slow = Indicators.ema(closes, slowN)
        val out = mutableListOf<SignalMark>()
        var lastSig = -999
        var bullStreak = 0
        var bearStreak = 0
        for (i in candles.indices) {
            val f = fast.getOrNull(i) ?: continue
            val s = slow.getOrNull(i) ?: continue
            val c = closes[i]
            if (f > s && c >= f) {
                bullStreak++; bearStreak = 0
            } else if (f < s && c <= f) {
                bearStreak++; bullStreak = 0
            } else {
                bullStreak = 0; bearStreak = 0
            }
            if (i - lastSig <= cooldown) continue
            when {
                bullStreak >= need -> {
                    out.add(SignalMark(candles[i].openTime, "B", c))
                    lastSig = i
                    bullStreak = 0
                }
                bearStreak >= need -> {
                    out.add(SignalMark(candles[i].openTime, "S", c))
                    lastSig = i
                    bearStreak = 0
                }
            }
        }
        return out
    }

    /**
     * 皮尔逊三曲线：三条 EMA 在窗口内两两相关且同向倾斜时开单。
     * 相关高 = 趋势一致；同向斜率 = 共振方向。
     */
    /**
     * 皮尔逊三曲线（可靠版）：
     * 1) 三 EMA 窗口相关高 → 结构同向（趋势/共振）
     * 2) 中长周期斜率定方向（主趋势）
     * 3) 防追涨杀跌：禁止在区间高位追多、低位追空（RSI + 位置 + 相对中轨回调）
     * 默认偏「趋势中的回调入场」，而不是三线齐拐就追价。
     *
     * 参数：
     * p1/p2/p3, window, minCorr, cooldown, minSlopePct
     * rsiBuyMax(默认58) rsiSellMin(默认42)  —— 超买不追多、超卖不追空
     * posBuyMax(默认0.72) posSellMin(默认0.28) —— 区间位置过滤
     * pullback(默认1) 1=要求价在中轨附近/下方才多，在中轨附近/上方才空
     * entryMode 0=齐斜率追势(旧) 1=中长定势+短线回调(推荐) 2=仅极端反转
     */
    private fun pearsonTriple(candles: List<Candle>, params: Map<String, Double>): List<SignalMark> {
        val p1 = p(params, "p1", 6.0).toInt().coerceIn(2, 50)
        val p2 = p(params, "p2", 14.0).toInt().coerceIn(p1 + 1, 80)
        val p3 = p(params, "p3", 28.0).toInt().coerceIn(p2 + 1, 120)
        val window = p(params, "window", 36.0).toInt().coerceIn(12, 120)
        val minCorr = p(params, "minCorr", 0.62).coerceIn(0.3, 0.99)
        val cooldown = p(params, "cooldown", 4.0).toInt().coerceIn(0, 40)
        val minSlopePct = p(params, "minSlopePct", 0.00003)
        val rsiBuyMax = p(params, "rsiBuyMax", 58.0).coerceIn(40.0, 80.0)
        val rsiSellMin = p(params, "rsiSellMin", 42.0).coerceIn(20.0, 60.0)
        val posBuyMax = p(params, "posBuyMax", 0.72).coerceIn(0.4, 0.95)
        val posSellMin = p(params, "posSellMin", 0.28).coerceIn(0.05, 0.6)
        val usePullback = p(params, "pullback", 1.0) >= 0.5
        val entryMode = p(params, "entryMode", 1.0).toInt().coerceIn(0, 2)

        val closes = candles.map { it.close }
        val c1 = Indicators.ema(closes, p1)
        val c2 = Indicators.ema(closes, p2)
        val c3 = Indicators.ema(closes, p3)
        val rsi = Indicators.rsi(closes, 14)
        val out = mutableListOf<SignalMark>()
        var lastSig = -999

        for (i in window until candles.size) {
            // 未收盘最后一根不发信号（与回测一致，避免用到未完成 K）
            if (i >= candles.size - 1) continue

            val a = DoubleArray(window)
            val b = DoubleArray(window)
            val c = DoubleArray(window)
            var ok = true
            for (k in 0 until window) {
                val ia = c1.getOrNull(i - window + 1 + k)
                val ib = c2.getOrNull(i - window + 1 + k)
                val ic = c3.getOrNull(i - window + 1 + k)
                if (ia == null || ib == null || ic == null) {
                    ok = false; break
                }
                a[k] = ia; b[k] = ib; c[k] = ic
            }
            if (!ok) continue
            val r12 = pearson(a, b)
            val r13 = pearson(a, c)
            val r23 = pearson(b, c)
            val avg = (r12 + r13 + r23) / 3.0
            if (avg < minCorr) continue

            val s1 = a.last() - a[a.size - 2]
            val s2 = b.last() - b[b.size - 2]
            val s3 = c.last() - c[c.size - 2]
            val ref = kotlin.math.abs(a.last()).coerceAtLeast(1e-9)
            val strong2 = kotlin.math.abs(s2) / ref >= minSlopePct * 0.5
            val strong3 = kotlin.math.abs(s3) / ref >= minSlopePct * 0.4
            if (!strong2 || !strong3) continue

            val price = closes[i]
            val e2 = b.last()
            val e1 = a.last()
            val rsiNow = rsi.getOrNull(i) ?: 50.0
            val winBars = candles.subList(i - window + 1, i + 1)
            val hi = winBars.maxOf { it.high }
            val lo = winBars.minOf { it.low }
            val pos = if (hi > lo) ((price - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.5

            // 主趋势：中长 EMA 同向
            val trendUp = s2 > 0 && s3 > 0
            val trendDn = s2 < 0 && s3 < 0
            if (!trendUp && !trendDn) continue

            // 短线：旧模式要求三线齐；推荐模式允许短线回撤后再顺势
            val shortUp = s1 > 0
            val shortDn = s1 < 0
            val shortStrong = kotlin.math.abs(s1) / ref >= minSlopePct * 0.3

            // 回调：价相对中轨
            val nearOrBelowMid = price <= e2 * 1.002
            val nearOrAboveMid = price >= e2 * 0.998
            val reclaimFast = price >= e1 * 0.999 // 短线止跌回抽

            val wantB = when (entryMode) {
                0 -> trendUp && shortUp && shortStrong // 旧：齐涨追多
                2 -> trendDn && rsiNow <= 32.0 && pos <= 0.25 // 极端反转抄底
                else -> { // 1 推荐：多头结构 + 未过热 + 回调区
                    trendUp && rsiNow <= rsiBuyMax && pos <= posBuyMax &&
                        (!usePullback || (nearOrBelowMid && (shortUp || reclaimFast)))
                }
            }
            val wantS = when (entryMode) {
                0 -> trendDn && shortDn && shortStrong
                2 -> trendUp && rsiNow >= 68.0 && pos >= 0.75
                else -> {
                    trendDn && rsiNow >= rsiSellMin && pos >= posSellMin &&
                        (!usePullback || (nearOrAboveMid && (shortDn || price <= e1 * 1.001)))
                }
            }

            // 硬性防追：再砍掉明显高位多、低位空
            val chaseB = pos >= 0.88 || rsiNow >= 72.0
            val chaseS = pos <= 0.12 || rsiNow <= 28.0
            if (i - lastSig <= cooldown) continue
            when {
                wantB && !chaseB && !wantS -> {
                    out.add(SignalMark(candles[i].openTime, "B", price))
                    lastSig = i
                }
                wantS && !chaseS && !wantB -> {
                    out.add(SignalMark(candles[i].openTime, "S", price))
                    lastSig = i
                }
            }
        }
        return out
    }

    /** 近 lookback 根突破前高/前低 */
    private fun breakout(candles: List<Candle>, params: Map<String, Double>): List<SignalMark> {
        val lookback = p(params, "lookback", 20.0).toInt().coerceIn(5, 100)
        val cooldown = p(params, "cooldown", 5.0).toInt().coerceIn(0, 40)
        val out = mutableListOf<SignalMark>()
        var lastSig = -999
        for (i in lookback until candles.size) {
            if (i - lastSig <= cooldown) continue
            val window = candles.subList(i - lookback, i)
            val hh = window.maxOf { it.high }
            val ll = window.minOf { it.low }
            val c = candles[i]
            when {
                c.close > hh -> {
                    out.add(SignalMark(c.openTime, "B", c.close))
                    lastSig = i
                }
                c.close < ll -> {
                    out.add(SignalMark(c.openTime, "S", c.close))
                    lastSig = i
                }
            }
        }
        return out
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        if (n < 3) return 0.0
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) {
            sx += x[i]; sy += y[i]
        }
        val mx = sx / n; val my = sy / n
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) {
            val a = x[i] - mx
            val b = y[i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }
        val den = sqrt(dx * dy)
        return if (den < 1e-12) 0.0 else (num / den).coerceIn(-1.0, 1.0)
    }
}

object EventSim {
    /** 周期毫秒：开仓约 1m 延迟，平仓持有一个合约周期 */
    private fun intervalMs(code: String): Long = when (code.trim().lowercase()) {
        "1m" -> 60_000L
        "3m" -> 180_000L
        "5m" -> 300_000L
        "10m" -> 600_000L
        "15m" -> 900_000L
        "30m" -> 1_800_000L
        "1h", "60m" -> 3_600_000L
        else -> 600_000L
    }

    /**
     * 事件合约回测（与实盘对齐，消除前视偏差）：
     * - 信号用到信号棒收盘价 → 仅在该棒【收盘后】才可确认
     * - 开仓时刻 = 信号棒收盘时刻（openTime + 周期），开仓价 = 该棒收盘价
     *   （旧逻辑在棒内按 open→close 插值开仓 = 偷看未来收盘，会把回测胜率虚高到 70%+）
     * - 不再等下一根同周期 K；收盘即可开，缩短延误
     * - 平仓 = 开仓 + 1 个合约周期；到期价可用历史真实路径（决策未用到未来信息）
     * - 未收盘的最后一根 K 上的信号不参与回测
     */
    fun backtest(
        candles: List<Candle>,
        signals: List<SignalMark>,
        symbol: String,
        interval: String,
    ): Pair<List<SimTrade>, Stats> {
        if (candles.size < 2) return emptyList<SimTrade>() to Stats()
        val ivMs = intervalMs(interval)
        // 极小执行延迟（模拟下单耗时），默认 2s，远小于 1m
        val execLagMs = 2_000L
        val idx = candles.withIndex().associate { (i, c) -> c.openTime to i }
        val lastClosedIdx = candles.size - 2 // 最后一根视为可能未收盘
        val trades = mutableListOf<SimTrade>()
        var i = 0
        while (i < signals.size) {
            val sig = signals[i]
            val si = idx[sig.openTime] ?: run {
                var best = -1
                for (j in candles.indices) {
                    if (candles[j].openTime <= sig.openTime) best = j
                    else break
                }
                if (best < 0) null else best
            }
            if (si == null || si > lastClosedIdx) {
                i++; continue
            }
            val sigBar = candles[si]
            // 确认时刻 = 信号棒收盘；开仓 = 确认后立刻（+execLag）
            val confirmTime = sigBar.openTime + ivMs
            val entryTime = confirmTime + execLagMs
            val entryPrice = sigBar.close
            if (entryPrice <= 0) {
                i++; continue
            }
            val exitTime = entryTime + ivMs
            // 数据未覆盖到期则跳过
            if (candles.last().openTime + ivMs < exitTime) {
                i++; continue
            }
            val exitPrice = priceAt(candles, exitTime, ivMs) ?: entryPrice
            val long = sig.side == "B"
            val pnl = if (long) (exitPrice - entryPrice) / entryPrice * 100
            else (entryPrice - exitPrice) / entryPrice * 100
            trades.add(
                SimTrade(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    interval = interval,
                    side = sig.side,
                    entryTime = entryTime,
                    entryPrice = entryPrice,
                    exitTime = exitTime,
                    exitPrice = exitPrice,
                    pnlPct = pnl,
                    win = pnl > 0,
                ),
            )
            // 持仓期间不再叠单（与实盘防追单一致）
            while (i < signals.size && signals[i].openTime < exitTime) i++
        }
        val wins = trades.count { it.win }
        return trades to Stats(
            trades.size, wins, trades.size - wins,
            if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size,
            trades.sumOf { it.pnlPct },
        )
    }

    /** 历史路径上某时刻价格：落在某根已走完的 K 内用开收插值；否则最近收盘 */
    private fun priceAt(candles: List<Candle>, timeMs: Long, ivMs: Long): Double? {
        if (candles.isEmpty()) return null
        val eb = candles.firstOrNull { it.openTime <= timeMs && timeMs < it.openTime + ivMs }
            ?: candles.lastOrNull { it.openTime <= timeMs }
            ?: return candles.last().close
        return when {
            timeMs >= eb.openTime + ivMs - 1 -> eb.close
            timeMs <= eb.openTime -> eb.open
            else -> {
                // 仅用于已收盘历史棒的到期结算；不用于开仓决策
                val f = ((timeMs - eb.openTime).toDouble() / ivMs).coerceIn(0.0, 1.0)
                eb.open + (eb.close - eb.open) * f
            }
        }
    }
}
