package com.puito.cryptoagent.domain

import com.puito.cryptoagent.data.*
import java.util.UUID

object StrategyEngine {
    fun signals(candles: List<Candle>, cfg: StrategyConfig): List<SignalMark> {
        if (candles.isEmpty()) return emptyList()
        val closes = candles.map { it.close }
        val out = mutableListOf<SignalMark>()
        for (i in candles.indices) {
            fun side(rules: List<Rule>): Boolean = rules.any { r ->
                val period = r.period.coerceIn(2, 200)
                val v = when (r.indicator) {
                    IndicatorType.RSI -> Indicators.rsi(closes, period).getOrNull(i)
                    IndicatorType.MACD -> Indicators.macdHist(closes).getOrNull(i)
                    IndicatorType.KDJ_J -> Indicators.kdjJ(candles).getOrNull(i)
                    IndicatorType.CLOSE -> closes[i]
                    IndicatorType.MA -> Indicators.sma(closes, period).getOrNull(i)
                    IndicatorType.EMA -> Indicators.ema(closes, period).getOrNull(i)
                    IndicatorType.BOLL -> Indicators.boll(closes, period).second.getOrNull(i)
                    IndicatorType.BOLL_PCT -> Indicators.bollPct(closes, period).getOrNull(i)
                    IndicatorType.MA_BIAS -> {
                        val ma = Indicators.sma(closes, period).getOrNull(i) ?: return@any false
                        if (ma == 0.0) return@any false
                        (closes[i] / ma - 1.0) * 100.0
                    }
                    IndicatorType.EMA_BIAS -> {
                        val ema = Indicators.ema(closes, period).getOrNull(i) ?: return@any false
                        if (ema == 0.0) return@any false
                        (closes[i] / ema - 1.0) * 100.0
                    }
                } ?: return@any false
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
