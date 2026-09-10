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
