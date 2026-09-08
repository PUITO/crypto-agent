package com.puito.cryptoapp.domain.sim

import com.puito.cryptoapp.data.model.*
import java.util.UUID

/**
 * 事件合约模拟：信号在 i 根出现，以下一根开盘入场，持有 holdBars 根后按收盘结算。
 */
class EventSim {
    fun backtest(
        candles: List<Candle>,
        signals: List<SignalMark>,
        symbol: String,
        interval: String,
        holdBars: Int = 1,
    ): Pair<List<SimTrade>, BacktestStats> {
        if (candles.size < 2) return emptyList<SimTrade>() to BacktestStats()
        val byTime = candles.withIndex().associate { (idx, c) -> c.openTime to idx }
        val trades = mutableListOf<SimTrade>()
        var i = 0
        while (i < signals.size) {
            val sig = signals[i]
            val idx = byTime[sig.openTime]
            if (idx == null) {
                i++
                continue
            }
            val entryIdx = idx + 1
            val exitIdx = entryIdx + holdBars - 1
            if (entryIdx >= candles.size || exitIdx >= candles.size) {
                i++
                continue
            }
            val entry = candles[entryIdx]
            val exit = candles[exitIdx]
            val long = sig.side == "B"
            val pnl = if (long) {
                (exit.close - entry.open) / entry.open * 100.0
            } else {
                (entry.open - exit.close) / entry.open * 100.0
            }
            trades.add(
                SimTrade(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    interval = interval,
                    side = sig.side,
                    entryTime = entry.openTime,
                    entryPrice = entry.open,
                    exitTime = exit.openTime,
                    exitPrice = exit.close,
                    pnlPct = pnl,
                    win = pnl > 0,
                )
            )
            val exitTime = exit.openTime
            while (i < signals.size && signals[i].openTime <= exitTime) i++
        }
        val wins = trades.count { it.win }
        val losses = trades.size - wins
        val total = trades.sumOf { it.pnlPct }
        val stats = BacktestStats(
            signals = trades.size,
            wins = wins,
            losses = losses,
            winRate = if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size,
            totalReturnPct = total,
        )
        return trades to stats
    }
}
