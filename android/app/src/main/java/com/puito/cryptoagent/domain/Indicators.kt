package com.puito.cryptoagent.domain

import com.puito.cryptoagent.data.Candle

object Indicators {
    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        if (closes.size < period + 1) return List(closes.size) { null }
        val out = MutableList<Double?>(closes.size) { null }
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgG = gain / period; var avgL = loss / period
        out[period] = if (avgL == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgG / avgL)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            val g = if (d > 0) d else 0.0; val l = if (d < 0) -d else 0.0
            avgG = (avgG * (period - 1) + g) / period
            avgL = (avgL * (period - 1) + l) / period
            out[i] = if (avgL == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgG / avgL)
        }
        return out
    }

    fun macdHist(closes: List<Double>): List<Double?> {
        fun ema(v: List<Double>, p: Int): List<Double?> {
            val o = MutableList<Double?>(v.size) { null }
            if (v.size < p) return o
            val a = 2.0 / (p + 1)
            var prev = v.take(p).average(); o[p - 1] = prev
            for (i in p until v.size) { prev = a * v[i] + (1 - a) * prev; o[i] = prev }
            return o
        }
        val e12 = ema(closes, 12); val e26 = ema(closes, 26)
        val line = closes.indices.map { i ->
            val a = e12[i]; val b = e26[i]
            if (a == null || b == null) null else a - b
        }
        // signal ema on line
        val vals = line.map { it ?: Double.NaN }
        val sig = MutableList<Double?>(line.size) { null }
        val seed = line.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (seed.size >= 9) {
            var prev = seed.take(9).map { it.second }.average()
            val start = seed[8].first
            sig[start] = prev
            val a = 2.0 / 10
            for (i in start + 1 until line.size) {
                val v = line[i] ?: continue
                prev = a * v + (1 - a) * prev
                sig[i] = prev
            }
        }
        return line.indices.map { i ->
            val m = line[i]; val s = sig[i]
            if (m == null || s == null) null else m - s
        }
    }

    fun kdjJ(candles: List<Candle>, n: Int = 9): List<Double?> {
        if (candles.size < n) return List(candles.size) { null }
        val out = MutableList<Double?>(candles.size) { null }
        var k = 50.0; var d = 50.0
        for (i in n - 1 until candles.size) {
            val w = candles.subList(i - n + 1, i + 1)
            val low = w.minOf { it.low }; val high = w.maxOf { it.high }
            val rsv = if (high == low) 50.0 else (candles[i].close - low) / (high - low) * 100
            k = 2.0 / 3 * k + 1.0 / 3 * rsv
            d = 2.0 / 3 * d + 1.0 / 3 * k
            out[i] = 3 * k - 2 * d
        }
        return out
    }
}
