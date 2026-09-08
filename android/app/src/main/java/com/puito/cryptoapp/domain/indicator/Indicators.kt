package com.puito.cryptoapp.domain.indicator

import com.puito.cryptoapp.data.model.Candle
import kotlin.math.abs

object Indicators {
    fun closes(candles: List<Candle>) = candles.map { it.close }

    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        if (closes.size < period + 1) return List(closes.size) { null }
        val out = MutableList<Double?>(closes.size) { null }
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        out[period] = rsToRsi(avgGain, avgLoss)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = rsToRsi(avgGain, avgLoss)
        }
        return out
    }

    private fun rsToRsi(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /** MACD histogram (12,26,9) */
    fun macdHist(closes: List<Double>): List<Double?> {
        val ema12 = ema(closes, 12)
        val ema26 = ema(closes, 26)
        val macdLine = closes.indices.map { i ->
            val a = ema12[i]
            val b = ema26[i]
            if (a == null || b == null) null else a - b
        }
        val signal = emaNullable(macdLine, 9)
        return macdLine.indices.map { i ->
            val m = macdLine[i]
            val s = signal[i]
            if (m == null || s == null) null else m - s
        }
    }

    fun kdjJ(candles: List<Candle>, n: Int = 9): List<Double?> {
        if (candles.size < n) return List(candles.size) { null }
        val out = MutableList<Double?>(candles.size) { null }
        var k = 50.0
        var d = 50.0
        for (i in n - 1 until candles.size) {
            val window = candles.subList(i - n + 1, i + 1)
            val low = window.minOf { it.low }
            val high = window.maxOf { it.high }
            val rsv = if (high == low) 50.0 else (candles[i].close - low) / (high - low) * 100.0
            k = 2.0 / 3.0 * k + 1.0 / 3.0 * rsv
            d = 2.0 / 3.0 * d + 1.0 / 3.0 * k
            val j = 3 * k - 2 * d
            out[i] = j
        }
        return out
    }

    private fun ema(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        val alpha = 2.0 / (period + 1)
        var prev = values.take(period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = alpha * values[i] + (1 - alpha) * prev
            out[i] = prev
        }
        return out
    }

    private fun emaNullable(values: List<Double?>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        val seed = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (seed.size < period) return out
        val firstIdx = seed[period - 1].first
        var prev = seed.take(period).map { it.second }.average()
        out[firstIdx] = prev
        val alpha = 2.0 / (period + 1)
        for (i in firstIdx + 1 until values.size) {
            val v = values[i] ?: continue
            prev = alpha * v + (1 - alpha) * prev
            out[i] = prev
        }
        return out
    }
}
