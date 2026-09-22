package com.puito.cryptoagent.net

import com.puito.cryptoagent.data.Candle
import com.puito.cryptoagent.data.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class BinanceClient(
    private var baseUrl: String = "https://data-api.binance.vision",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun updateBase(url: String) { baseUrl = url.trimEnd('/') }

    suspend fun fetch(symbol: String, interval: Interval, limit: Int): List<Candle> =
        withContext(Dispatchers.IO) {
            val lim = limit.coerceIn(200, 1000) // 至少 200 根，避免指标/胜率失真
            when (interval) {
                Interval.M10 -> aggregate10m(symbol, lim)
                else -> native(symbol, interval.code, lim)
            }
        }

    /**
     * 取某一时刻附近的成交价（1m K 线）。
     * 用于模拟开仓/到期平仓，避免直接用高周期 OHLC 失真。
     * @param preferClose true=偏收盘价（到期），false=偏开盘价（开仓）
     */
    suspend fun priceAt(
        symbol: String,
        timeMs: Long,
        preferClose: Boolean = false,
    ): Double? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        val start = (timeMs - 120_000L).coerceAtLeast(0L)
        val end = timeMs + 120_000L
        val url =
            "$baseUrl/api/v3/klines?symbol=$sym&interval=1m&startTime=$start&endTime=$end&limit=5"
        val req = Request.Builder().url(url).get().build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return@withContext null
                var best: Candle? = null
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONArray(i)
                    val c = Candle(
                        r.getLong(0),
                        r.getString(1).toDouble(),
                        r.getString(2).toDouble(),
                        r.getString(3).toDouble(),
                        r.getString(4).toDouble(),
                        r.getString(5).toDouble(),
                    )
                    if (c.openTime <= timeMs) best = c
                }
                val c = best ?: return@withContext null
                // 时刻落在 1m 棒内：开仓偏 open，到期偏 close；过半分钟则用 close
                val elapsed = timeMs - c.openTime
                when {
                    preferClose -> c.close
                    elapsed >= 45_000L -> c.close
                    else -> c.open
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun native(symbol: String, interval: String, limit: Int): List<Candle> {
        val url = "$baseUrl/api/v3/klines?symbol=${symbol.uppercase()}&interval=$interval&limit=$limit"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Binance ${resp.code}")
            val arr = JSONArray(resp.body?.string() ?: "[]")
            return (0 until arr.length()).map { i ->
                val r = arr.getJSONArray(i)
                Candle(r.getLong(0), r.getString(1).toDouble(), r.getString(2).toDouble(),
                    r.getString(3).toDouble(), r.getString(4).toDouble(), r.getString(5).toDouble())
            }
        }
    }

    private fun aggregate10m(symbol: String, limit: Int): List<Candle> {
        val raw = native(symbol, "5m", 1000) // 尽量用满 5m 历史再聚合成 10m
        val out = mutableListOf<Candle>()
        var i = 0
        while (i + 1 < raw.size) {
            val a = raw[i]
            if ((a.openTime / 60_000L) % 10 != 0L) { i++; continue }
            val b = raw[i + 1]
            out.add(Candle(a.openTime, a.open, maxOf(a.high, b.high), minOf(a.low, b.low), b.close, a.volume + b.volume))
            i += 2
            if (out.size >= limit) break
        }
        return out.takeLast(limit)
    }
}
