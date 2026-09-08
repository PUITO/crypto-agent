package com.puito.cryptoapp.domain.strategy

import com.puito.cryptoapp.data.model.*
import com.puito.cryptoapp.domain.indicator.Indicators

class StrategyEngine {
    fun generateSignals(candles: List<Candle>, config: StrategyConfig): List<SignalMark> {
        if (candles.isEmpty()) return emptyList()
        val closes = Indicators.closes(candles)
        val rsi = Indicators.rsi(closes)
        val macd = Indicators.macdHist(closes)
        val kdj = Indicators.kdjJ(candles)
        val marks = mutableListOf<SignalMark>()
        for (i in candles.indices) {
            val buy = evalSide(config.buyRules, i, closes, rsi, macd, kdj)
            val sell = evalSide(config.sellRules, i, closes, rsi, macd, kdj)
            when {
                buy && !sell -> marks.add(SignalMark(candles[i].openTime, "B", candles[i].close))
                sell && !buy -> marks.add(SignalMark(candles[i].openTime, "S", candles[i].close))
            }
        }
        return marks
    }

    private fun evalSide(
        rules: List<Rule>,
        i: Int,
        closes: List<Double>,
        rsi: List<Double?>,
        macd: List<Double?>,
        kdj: List<Double?>,
    ): Boolean {
        if (rules.isEmpty()) return false
        return rules.any { rule ->
            val v = when (rule.indicator) {
                IndicatorType.RSI -> rsi[i]
                IndicatorType.MACD -> macd[i]
                IndicatorType.KDJ_J -> kdj[i]
                IndicatorType.CLOSE -> closes[i]
            } ?: return@any false
            when (rule.op) {
                CompareOp.GT -> v > rule.value
                CompareOp.GTE -> v >= rule.value
                CompareOp.LT -> v < rule.value
                CompareOp.LTE -> v <= rule.value
            }
        }
    }
}
