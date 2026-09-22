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
    private fun pearsonTriple(candles: List<Candle>, params: Map<String, Double>): List<SignalMark> {
        val p1 = p(params, "p1", 5.0).toInt().coerceIn(2, 50)
        val p2 = p(params, "p2", 10.0).toInt().coerceIn(p1 + 1, 80)
        val p3 = p(params, "p3", 20.0).toInt().coerceIn(p2 + 1, 120)
        val window = p(params, "window", 30.0).toInt().coerceIn(10, 120)
        val minCorr = p(params, "minCorr", 0.55)
        val cooldown = p(params, "cooldown", 2.0).toInt().coerceIn(0, 30)
        val closes = candles.map { it.close }
        val c1 = Indicators.ema(closes, p1)
        val c2 = Indicators.ema(closes, p2)
        val c3 = Indicators.ema(closes, p3)
        val out = mutableListOf<SignalMark>()
        var lastSig = -999
        for (i in window until candles.size) {
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
            if (i - lastSig <= cooldown) continue
            when {
                s1 > 0 && s2 > 0 && s3 > 0 -> {
                    out.add(SignalMark(candles[i].openTime, "B", closes[i]))
                    lastSig = i
                }
                s1 < 0 && s2 < 0 && s3 < 0 -> {
                    out.add(SignalMark(candles[i].openTime, "S", closes[i]))
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
    fun backtest(
        candles: List<Candle>,
        signals: List<SignalMark>,
        symbol: String,
        interval: String,
    ): Pair<List<SimTrade>, Stats> {
        if (candles.size < 2) return emptyList<SimTrade>() to Stats()
        val idx = candles.withIndex().associate { (i, c) -> c.openTime to i }
        val trades = mutableListOf<SimTrade>()
        var i = 0
        while (i < signals.size) {
            val sig = signals[i]
            val si = idx[sig.openTime]
            if (si == null) {
                i++; continue
            }
            val entryI = si + 1
            val exitI = entryI
            if (entryI >= candles.size || exitI >= candles.size) {
                i++; continue
            }
            val entry = candles[entryI]
            val exit = candles[exitI]
            val long = sig.side == "B"
            val pnl = if (long) (exit.close - entry.open) / entry.open * 100
            else (entry.open - exit.close) / entry.open * 100
            trades.add(
                SimTrade(
                    UUID.randomUUID().toString(), symbol, interval, sig.side,
                    entry.openTime, entry.open, exit.openTime, exit.close, pnl, pnl > 0,
                ),
            )
            val et = exit.openTime
            while (i < signals.size && signals[i].openTime <= et) i++
        }
        val wins = trades.count { it.win }
        return trades to Stats(
            trades.size, wins, trades.size - wins,
            if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size,
            trades.sumOf { it.pnlPct },
        )
    }
}
