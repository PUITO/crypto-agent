package com.puito.cryptoapp.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.puito.cryptoapp.data.binance.BinanceClient
import com.puito.cryptoapp.data.model.*
import com.puito.cryptoapp.domain.sim.EventSim
import com.puito.cryptoapp.domain.strategy.StrategyEngine
import java.util.UUID

class AppRepository(context: Context) {
    private val prefs = context.getSharedPreferences("crypto_app", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val binance = BinanceClient()
    private val engine = StrategyEngine()
    private val sim = EventSim()

    var candles: List<Candle> = emptyList()
        private set
    var signals: List<SignalMark> = emptyList()
        private set
    var trades: List<SimTrade> = emptyList()
        private set
    var stats: BacktestStats = BacktestStats()
        private set
    var strategyRunning: Boolean = false
        private set

    /** 已通知过的信号 openTime，避免重复通知 */
    private val notifiedSignalKeys = mutableSetOf<String>()

    fun loadSettings(): AppSettings {
        val json = prefs.getString("settings", null) ?: return AppSettings()
        return runCatching { gson.fromJson(json, AppSettings::class.java) }.getOrDefault(AppSettings())
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit().putString("settings", gson.toJson(s)).apply()
        binance.updateBaseUrl(s.binanceBaseUrl)
    }

    fun loadConfigs(): List<StrategyConfig> {
        val json = prefs.getString("configs", null) ?: return defaultConfigs()
        val type = object : TypeToken<List<StrategyConfig>>() {}.type
        return runCatching { gson.fromJson<List<StrategyConfig>>(json, type) }.getOrElse { defaultConfigs() }
    }

    fun saveConfigs(list: List<StrategyConfig>) {
        prefs.edit().putString("configs", gson.toJson(list)).apply()
    }

    fun enabledConfig(): StrategyConfig? = loadConfigs().find { it.enabled }

    private fun defaultConfigs(): List<StrategyConfig> {
        val c = StrategyConfig(
            id = UUID.randomUUID().toString(),
            title = "RSI 超买超卖",
            enabled = true,
            buyRules = listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0)),
            sellRules = listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0)),
        )
        saveConfigs(listOf(c))
        return listOf(c)
    }

    suspend fun refreshCandles(symbol: String, interval: Interval, limit: Int): List<Candle> {
        val settings = loadSettings()
        binance.updateBaseUrl(settings.binanceBaseUrl)
        candles = binance.fetchKlines(symbol, interval, limit)
        return candles
    }

    fun startStrategy(symbol: String, interval: Interval): Result<Unit> {
        val cfg = enabledConfig() ?: return Result.failure(IllegalStateException("请先启用一套配置"))
        if (candles.isEmpty()) return Result.failure(IllegalStateException("请先加载 K 线"))
        val marks = engine.generateSignals(candles, cfg)
        signals = marks
        // 启动时回测产生的历史信号不弹通知，只标记已见
        notifiedSignalKeys.clear()
        marks.forEach { notifiedSignalKeys.add(signalKey(symbol, interval.code, it)) }
        val (t, st) = sim.backtest(candles, marks, symbol, interval.code, holdBars = 1)
        trades = t
        stats = recomputeStatsFromTrades()
        strategyRunning = true
        prefs.edit()
            .putString("sim_${interval.code}", gson.toJson(t))
            .putString("stats_${interval.code}", gson.toJson(st))
            .putBoolean("running", true)
            .apply()
        return Result.success(Unit)
    }

    fun stopStrategy(interval: Interval) {
        strategyRunning = false
        signals = emptyList()
        trades = emptyList()
        stats = BacktestStats()
        notifiedSignalKeys.clear()
        prefs.edit()
            .remove("sim_${interval.code}")
            .remove("stats_${interval.code}")
            .putBoolean("running", false)
            .apply()
    }

    fun restoreSim(interval: Interval) {
        strategyRunning = prefs.getBoolean("running", false)
        if (!strategyRunning) return
        val tjson = prefs.getString("sim_${interval.code}", null)
        val sjson = prefs.getString("stats_${interval.code}", null)
        if (tjson != null) {
            val type = object : TypeToken<List<SimTrade>>() {}.type
            trades = runCatching { gson.fromJson<List<SimTrade>>(tjson, type) }.getOrDefault(emptyList())
        }
        // 以全部 trades 为准重算，避免与只展示 5 条的列表混淆
        if (trades.isNotEmpty()) {
            recomputeStatsFromTrades()
        } else if (sjson != null) {
            stats = runCatching { gson.fromJson(sjson, BacktestStats::class.java) }.getOrDefault(BacktestStats())
        }
    }

    /**
     * 后台轮询：刷新 K 线，重算信号，返回「新出现」的信号（用于通知）。
     */
    suspend fun pollAndDetectNewSignals(symbol: String, interval: Interval): List<SignalMark> {
        if (!strategyRunning) return emptyList()
        val cfg = enabledConfig() ?: return emptyList()
        val limit = loadSettings().defaultLimit
        refreshCandles(symbol, interval, limit)
        val marks = engine.generateSignals(candles, cfg)
        signals = marks
        val (t, st) = sim.backtest(candles, marks, symbol, interval.code, holdBars = 1)
        trades = t
        stats = recomputeStatsFromTrades()
        prefs.edit()
            .putString("sim_${interval.code}", gson.toJson(t))
            .putString("stats_${interval.code}", gson.toJson(stats))
            .apply()

        val fresh = mutableListOf<SignalMark>()
        // 只通知最近几根上的新信号，避免刷屏
        val recentTimes = candles.takeLast(5).map { it.openTime }.toSet()
        for (m in marks) {
            if (m.openTime !in recentTimes) continue
            val key = signalKey(symbol, interval.code, m)
            if (key !in notifiedSignalKeys) {
                notifiedSignalKeys.add(key)
                fresh.add(m)
            }
        }
        return fresh
    }


    /** 统计始终基于全部模拟成交，与列表是否只显示 5 条无关 */
    fun recomputeStatsFromTrades(): BacktestStats {
        val all = trades
        val wins = all.count { it.win }
        val losses = all.size - wins
        val st = BacktestStats(
            signals = all.size,
            wins = wins,
            losses = losses,
            winRate = if (all.isEmpty()) 0.0 else wins.toDouble() / all.size,
            totalReturnPct = all.sumOf { it.pnlPct },
        )
        stats = st
        return st
    }

    private fun signalKey(symbol: String, interval: String, m: SignalMark) =
        "$symbol|$interval|${m.openTime}|${m.side}"
}
