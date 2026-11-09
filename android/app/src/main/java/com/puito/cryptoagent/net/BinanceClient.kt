package com.puito.cryptoagent.net

import com.puito.cryptoagent.data.Candle
import com.puito.cryptoagent.data.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
            val lim = limit.coerceIn(200, 1000)
            when (interval) {
                Interval.M10 -> aggregate10m(symbol, lim)
                else -> native(symbol, interval.code, lim)
            }
        }

    /**
     * 按精确时刻取价：
     * 1) 近 20s：强制 ticker / 最新 1s（实时成交价）
     * 2) 1s K
     * 3) aggTrades ±3s
     * 4) 最后才 1m（仅历史回测可接受；实时路径请用 priceAtLive）
     */
    suspend fun priceAt(
        symbol: String,
        timeMs: Long,
        preferClose: Boolean = false,
    ): Double? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timeMs) <= 20_000L) {
            lastPrice(sym)?.let { return@withContext it }
        }
        priceAt1s(sym, timeMs)?.let { return@withContext it }
        priceAtAggTrade(sym, timeMs)?.let { return@withContext it }
        priceAt1m(sym, timeMs, preferClose)
    }

    /**
     * 实时盘开/平仓专用：**禁止 1m/高周期 OHLC**。
     * - 目标时刻距现在 ≤45s：强制当前市价（ticker / bookTicker / 最近成交）
     * - 更早的到期时刻：1s K → aggTrades 窗口（真实成交），失败返回 null 由上层重试
     *   （绝不用「现在的 lastPrice」冒充历史到期价）
     */
    suspend fun priceAtLive(
        symbol: String,
        timeMs: Long,
    ): Double? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        val now = System.currentTimeMillis()
        val delta = kotlin.math.abs(now - timeMs)
        if (delta <= 45_000L) {
            priceNow(sym)?.let { return@withContext it }
        }
        priceAt1s(sym, timeMs)?.let { return@withContext it }
        priceAtAggTrade(sym, timeMs, windowMs = 3_000L)?.let { return@withContext it }
        priceAtAggTrade(sym, timeMs, windowMs = 10_000L)?.let { return@withContext it }
        priceAtAggTrade(sym, timeMs, windowMs = 30_000L)?.let { return@withContext it }
        // 到期已过较久仍无秒级数据：不瞎用当前价，交给上层暂缓平仓
        if (delta <= 120_000L) {
            priceNow(sym)?.let { return@withContext it }
        }
        null
    }

    /**
     * 开仓瞬间市价：ticker → 买卖中间价 → 最近 1s 收盘 → 近 2s 成交均价。
     * 专供实时开仓，不用任何已收盘高周期 K。
     */
    suspend fun priceNow(symbol: String): Double? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        tickerPrice(sym)
            ?: bookMidPrice(sym)
            ?: latest1sClose(sym)
            ?: priceAtAggTrade(sym, System.currentTimeMillis(), windowMs = 2_000L)
    }

    /** 最新成交价（图表刷新等） */
    suspend fun lastPrice(symbol: String): Double? = priceNow(symbol)

    private fun bookMidPrice(sym: String): Double? {
        val url = "$baseUrl/api/v3/ticker/bookTicker?symbol=$sym"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return try {
                val o = org.json.JSONObject(body)
                val bid = o.optString("bidPrice").toDoubleOrNull() ?: return null
                val ask = o.optString("askPrice").toDoubleOrNull() ?: return null
                if (bid > 0 && ask > 0) (bid + ask) / 2.0 else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 批量精确取价：先 1m 打底，再对每笔尝试 1s/aggTrades。
     * 用于回测重定价，提高开平仓准确率。
     */
    suspend fun pricesAtPrecise(
        symbol: String,
        times: List<Long>,
        preferClose: Boolean = false,
    ): Map<Long, Double> = withContext(Dispatchers.IO) {
        if (times.isEmpty()) return@withContext emptyMap()
        val sym = symbol.uppercase()
        val base = pricesAt1m(sym, times, preferClose)
        val out = linkedMapOf<Long, Double>()
        for (t in times) {
            val px = priceAt1s(sym, t)
                ?: priceAtAggTrade(sym, t)
                ?: base[t]
            if (px != null && px > 0) out[t] = px
        }
        out
    }

    private fun tickerPrice(sym: String): Double? {
        val url = "$baseUrl/api/v3/ticker/price?symbol=$sym"
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val o = org.json.JSONObject(resp.body?.string() ?: return null)
                o.getString("price").toDouble()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun latest1sClose(sym: String): Double? {
        val url = "$baseUrl/api/v3/klines?symbol=$sym&interval=1s&limit=1"
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return null
                arr.getJSONArray(0).getString(4).toDouble()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 批量用 1m K 线给一组时刻取价（历史回测加速），仍按分钟对齐 */
    suspend fun pricesAt1m(
        symbol: String,
        times: List<Long>,
        preferClose: Boolean,
    ): Map<Long, Double> = withContext(Dispatchers.IO) {
        if (times.isEmpty()) return@withContext emptyMap()
        val sym = symbol.uppercase()
        val minutes = times.map { it - (it % 60_000L) }.distinct().sorted()
        val start = (minutes.first() - 60_000L).coerceAtLeast(0L)
        val end = minutes.last() + 60_000L
        val bars = fetchKlinesRange(sym, "1m", start, end, limit = 1500)
        if (bars.isEmpty()) return@withContext emptyMap()
        val byOpen = bars.associateBy { it.openTime }
        val out = linkedMapOf<Long, Double>()
        for (t in times) {
            val minute = t - (t % 60_000L)
            val queryStart = when {
                preferClose && t > 0L && t % 60_000L == 0L -> (minute - 60_000L).coerceAtLeast(0L)
                else -> minute
            }
            val bar = byOpen[queryStart]
                ?: bars.lastOrNull { it.openTime <= queryStart }
            if (bar != null) {
                out[t] = if (preferClose) bar.close else bar.open
            }
        }
        out
    }

    private fun priceAt1s(sym: String, timeMs: Long): Double? {
        val sec = timeMs - (timeMs % 1_000L)
        val url =
            "$baseUrl/api/v3/klines?symbol=$sym&interval=1s&startTime=$sec&limit=1"
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return null
                val r = arr.getJSONArray(0)
                // 1s 用 close 更接近该秒成交中枢；开仓也可用 open
                r.getString(4).toDouble()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun priceAtAggTrade(sym: String, timeMs: Long, windowMs: Long = 1_500L): Double? {
        val w = windowMs.coerceIn(500L, 30_000L)
        val start = (timeMs - w).coerceAtLeast(0L)
        val end = timeMs + w
        val url =
            "$baseUrl/api/v3/aggTrades?symbol=$sym&startTime=$start&endTime=$end&limit=20"
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return null
                var bestPrice: Double? = null
                var bestDist = Long.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ts = o.getLong("T")
                    val px = o.getString("p").toDouble()
                    val d = abs(ts - timeMs)
                    if (d < bestDist) {
                        bestDist = d
                        bestPrice = px
                    }
                }
                bestPrice
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun priceAt1m(sym: String, timeMs: Long, preferClose: Boolean): Double? {
        val minute = timeMs - (timeMs % 60_000L)
        val queryStart = when {
            preferClose && timeMs > 0L && timeMs % 60_000L == 0L ->
                (minute - 60_000L).coerceAtLeast(0L)
            else -> minute
        }
        val url =
            "$baseUrl/api/v3/klines?symbol=$sym&interval=1m&startTime=$queryStart&limit=1"
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() == 0) return null
                val r = arr.getJSONArray(0)
                val open = r.getString(1).toDouble()
                val close = r.getString(4).toDouble()
                if (preferClose) close else open
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchKlinesRange(
        symbol: String,
        interval: String,
        startMs: Long,
        endMs: Long,
        limit: Int = 1000,
    ): List<Candle> {
        val url =
            "$baseUrl/api/v3/klines?symbol=$symbol&interval=$interval" +
                "&startTime=$startMs&endTime=$endMs&limit=${limit.coerceIn(1, 1500)}"
        val req = Request.Builder().url(url).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONArray(i)
                    Candle(
                        r.getLong(0),
                        r.getString(1).toDouble(),
                        r.getString(2).toDouble(),
                        r.getString(3).toDouble(),
                        r.getString(4).toDouble(),
                        r.getString(5).toDouble(),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
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
                Candle(
                    r.getLong(0),
                    r.getString(1).toDouble(),
                    r.getString(2).toDouble(),
                    r.getString(3).toDouble(),
                    r.getString(4).toDouble(),
                    r.getString(5).toDouble(),
                )
            }
        }
    }

    private fun aggregate10m(symbol: String, limit: Int): List<Candle> {
        val raw = native(symbol, "5m", 1000)
        val out = mutableListOf<Candle>()
        var i = 0
        while (i + 1 < raw.size) {
            val a = raw[i]
            if ((a.openTime / 60_000L) % 10 != 0L) {
                i++; continue
            }
            val b = raw[i + 1]
            out.add(
                Candle(
                    a.openTime,
                    a.open,
                    maxOf(a.high, b.high),
                    minOf(a.low, b.low),
                    b.close,
                    a.volume + b.volume,
                ),
            )
            i += 2
            if (out.size >= limit) break
        }
        return out.takeLast(limit)
    }
}
