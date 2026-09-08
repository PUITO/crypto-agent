package com.puito.cryptoapp.data.binance

import com.puito.cryptoapp.data.model.Candle
import com.puito.cryptoapp.data.model.Interval
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
    fun updateBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    /**
     * Binance 原生支持 5m/30m/1h；10m 由 5m 聚合。
     */
    suspend fun fetchKlines(symbol: String, interval: Interval, limit: Int): List<Candle> =
        withContext(Dispatchers.IO) {
            when (interval) {
                Interval.M10 -> aggregate5mTo10m(symbol, limit)
                else -> fetchNative(symbol, interval.code, limit.coerceIn(1, 1000))
            }
        }

    private fun fetchNative(symbol: String, interval: String, limit: Int): List<Candle> {
        val url =
            "$baseUrl/api/v3/klines?symbol=${symbol.uppercase()}&interval=$interval&limit=$limit"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Binance HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty body")
            val arr = JSONArray(body)
            val out = ArrayList<Candle>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                out.add(
                    Candle(
                        openTime = row.getLong(0),
                        open = row.getString(1).toDouble(),
                        high = row.getString(2).toDouble(),
                        low = row.getString(3).toDouble(),
                        close = row.getString(4).toDouble(),
                        volume = row.getString(5).toDouble(),
                        closeTime = row.getLong(6),
                    )
                )
            }
            return out
        }
    }

    private fun aggregate5mTo10m(symbol: String, limit: Int): List<Candle> {
        val need = (limit * 2).coerceIn(2, 1000)
        val raw = fetchNative(symbol, "5m", need)
        val out = mutableListOf<Candle>()
        var i = 0
        while (i + 1 < raw.size) {
            val a = raw[i]
            val b = raw[i + 1]
            // 对齐到 10 分钟边界
            if ((a.openTime / 60_000L) % 10 != 0L) {
                i++
                continue
            }
            out.add(
                Candle(
                    openTime = a.openTime,
                    open = a.open,
                    high = maxOf(a.high, b.high),
                    low = minOf(a.low, b.low),
                    close = b.close,
                    volume = a.volume + b.volume,
                    closeTime = b.closeTime,
                )
            )
            i += 2
            if (out.size >= limit) break
        }
        return out.takeLast(limit)
    }
}
