package com.puito.cryptoagent.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.puito.cryptoagent.domain.EventSim
import com.puito.cryptoagent.domain.StrategyEngine
import com.puito.cryptoagent.net.BinanceClient
import com.puito.cryptoagent.net.LlmClient
import java.util.UUID

class Repository(ctx: Context) {
    private val sp = ctx.getSharedPreferences("agent_local", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val binance = BinanceClient()
    private val llm = LlmClient()

    var candles: List<Candle> = emptyList(); private set
    var signals: List<SignalMark> = emptyList(); private set
    var trades: List<SimTrade> = emptyList(); private set
    var stats: Stats = Stats(); private set
    private val notified = mutableSetOf<String>()

    fun settings(): AppSettings {
        val j = sp.getString("settings", null) ?: return AppSettings()
        return runCatching { gson.fromJson(j, AppSettings::class.java) }.getOrDefault(AppSettings())
    }

    fun saveSettings(s: AppSettings) {
        sp.edit().putString("settings", gson.toJson(s)).apply()
        binance.updateBase(s.binanceBaseUrl)
    }

    fun strategies(): List<StrategyConfig> {
        val j = sp.getString("strategies", null)
        if (j == null) {
            val d = listOf(
                StrategyConfig(
                    UUID.randomUUID().toString(), "默认 RSI", true,
                    listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0)),
                    listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0)),
                )
            )
            saveStrategies(d)
            return d
        }
        val type = object : TypeToken<List<StrategyConfig>>() {}.type
        return runCatching { gson.fromJson<List<StrategyConfig>>(j, type) }.getOrDefault(emptyList())
    }

    fun saveStrategies(list: List<StrategyConfig>) {
        sp.edit().putString("strategies", gson.toJson(list)).apply()
    }

    fun enabledStrategy(): StrategyConfig? = strategies().find { it.enabled }

    suspend fun refreshMarket(): Result<Unit> {
        val s = settings()
        binance.updateBase(s.binanceBaseUrl)
        return try {
            candles = binance.fetch(s.symbol, Interval.from(s.interval), s.klineLimit)
            // 不落盘全量 tick，仅内存；可选缓存最近结果摘要
            if (s.strategyRunning) {
                val cfg = enabledStrategy()
                if (cfg != null) runStrategy(s.symbol, s.interval, cfg, notifyNew = false)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun runStrategy(symbol: String, interval: String, cfg: StrategyConfig, notifyNew: Boolean): List<SignalMark> {
        val marks = StrategyEngine.signals(candles, cfg)
        signals = marks
        val (t, st) = EventSim.backtest(candles, marks, symbol, interval)
        trades = t
        stats = st
        sp.edit()
            .putString("trades_$interval", gson.toJson(t))
            .putString("stats_$interval", gson.toJson(st))
            .apply()
        if (!notifyNew) {
            notified.clear()
            marks.forEach { notified.add(key(symbol, interval, it)) }
            return emptyList()
        }
        val recent = candles.takeLast(3).map { it.openTime }.toSet()
        val fresh = marks.filter { it.openTime in recent && key(symbol, interval, it) !in notified }
        fresh.forEach { notified.add(key(symbol, interval, it)) }
        return fresh
    }

    /** 后台轮询：刷新 + 检测新信号 */
    suspend fun poll(): List<SignalMark> {
        val s = settings()
        if (!s.strategyRunning) return emptyList()
        binance.updateBase(s.binanceBaseUrl)
        candles = binance.fetch(s.symbol, Interval.from(s.interval), s.klineLimit)
        val cfg = enabledStrategy() ?: return emptyList()
        return runStrategy(s.symbol, s.interval, cfg, notifyNew = true)
    }

    suspend fun chat(user: String): String {
        val s = settings()
        val sys = """
            你是手机端 Crypto Agent 助手。用户在本地运行事件合约模拟。
            当前: ${s.symbol} ${s.interval}, K线 ${candles.size} 根, 模拟成交 ${stats.trades} 笔, 胜率 ${"%.1f".format(stats.winRate * 100)}%.
            用简洁中文回答策略与行情问题。
        """.trimIndent()
        return llm.chat(s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, user)
    }

    private fun key(symbol: String, interval: String, m: SignalMark) =
        "$symbol|$interval|${m.openTime}|${m.side}"
}
