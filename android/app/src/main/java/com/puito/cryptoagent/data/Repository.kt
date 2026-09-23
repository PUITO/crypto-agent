package com.puito.cryptoagent.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.puito.cryptoagent.domain.EventSim
import com.puito.cryptoagent.domain.ModelEngine
import com.puito.cryptoagent.domain.StrategyEngine
import com.puito.cryptoagent.net.BinanceClient
import com.puito.cryptoagent.net.HibtClient
import com.puito.cryptoagent.net.HibtWebSession
import com.puito.cryptoagent.notify.Notify
import com.puito.cryptoagent.net.LlmClient
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 实时模拟挂单：信号确认后在下一根 K 开盘开仓，该根收盘后平仓 */
data class PendingLiveSim(
    val key: String,
    val symbol: String,
    val interval: String,
    val side: String,
    val signalTime: Long,
    val entryTime: Long,
    val entryPrice: Double,
)

class Repository(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val sp = ctx.getSharedPreferences("agent_local", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val binance = BinanceClient()
    private val llm = LlmClient()
    /** 已实盘提交过的信号键，防止同一信号重复下单 */
    private val placedOrderKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val placeLock = Any()

    private val hibt = HibtClient()

    var candles: List<Candle> = emptyList(); private set
    var signals: List<SignalMark> = emptyList(); private set
    var trades: List<SimTrade> = emptyList(); private set
    var stats: Stats = Stats(); private set
    /** 策略面板展示的实时模拟（过 AI 阈值 / AI 关则全信号） */
    var liveSimTrades: List<SimTrade> = emptyList(); private set
    var liveSimStats: Stats = Stats(); private set
    private val liveSimKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 已开仓未平仓的模拟单：key = symbol|interval|entryTime|side */
    private val pendingLiveSims = java.util.concurrent.ConcurrentHashMap<String, PendingLiveSim>()
    private val _liveSimTick = MutableStateFlow(0)
    /** UI 收集此 tick 以刷新持仓/平仓列表 */
    val liveSimTick: StateFlow<Int> = _liveSimTick.asStateFlow()
    @Volatile private var retuneInProgress = false
    @Volatile private var lastAutoRetuneAt = 0L

    init {
        loadLiveSimFromDisk()
    }
    var overlays: List<ChartOverlay> = emptyList(); private set
    private val notified = linkedSetOf<String>() // 保序，便于裁剪
    /** symbol|interval|side -> 上次通知时间戳 */
    private val lastNotifyAt = mutableMapOf<String, Long>()
    private val maxNotifiedKeys = 800


    fun settings(): AppSettings {
        val j = sp.getString("settings", null) ?: return AppSettings()
        return runCatching { gson.fromJson(j, AppSettings::class.java) }.getOrDefault(AppSettings())
    }

    private fun loadLiveSimFromDisk() {
        val j = sp.getString("live_sim_trades", null)
        if (j != null) {
            val type = object : TypeToken<List<SimTrade>>() {}.type
            val list = runCatching { gson.fromJson<List<SimTrade>>(j, type) }.getOrDefault(emptyList())
            liveSimTrades = list.takeLast(200)
            liveSimStats = statsOf(liveSimTrades)
            liveSimKeys.clear()
            liveSimTrades.forEach { liveSimKeys.add("${it.symbol}|${it.interval}|${it.entryTime}|${it.side}") }
        }
        loadPendingFromDisk()
        bumpLiveSim()
    }

    private fun saveLiveSimToDisk() {
        sp.edit().putString("live_sim_trades", gson.toJson(liveSimTrades.takeLast(200))).apply()
    }

    private fun statsOf(list: List<SimTrade>): Stats {
        if (list.isEmpty()) return Stats()
        val wins = list.count { it.win }
        return Stats(
            trades = list.size,
            wins = wins,
            losses = list.size - wins,
            winRate = wins.toDouble() / list.size,
            totalReturnPct = list.sumOf { it.pnlPct },
        )
    }

    fun consecutiveLosses(list: List<SimTrade> = liveSimTrades): Int {
        var n = 0
        for (t in list.asReversed()) {
            if (!t.win) n++ else break
        }
        return n
    }

    fun clearLiveSim() {
        liveSimTrades = emptyList()
        liveSimStats = Stats()
        liveSimKeys.clear()
        pendingLiveSims.clear()
        sp.edit().remove("live_sim_trades").apply()
        sp.edit().remove("live_sim_pending").apply()
        bumpLiveSim()
    }

    /** 当前未平仓模拟持仓（开仓即可见） */
    fun pendingLivePositions(): List<PendingLiveSim> =
        pendingLiveSims.values.sortedByDescending { it.entryTime }

    private fun bumpLiveSim() {
        _liveSimTick.value = _liveSimTick.value + 1
    }

    private fun savePendingToDisk() {
        sp.edit().putString(
            "live_sim_pending",
            gson.toJson(pendingLiveSims.values.toList()),
        ).apply()
    }

    private fun loadPendingFromDisk() {
        val j = sp.getString("live_sim_pending", null) ?: return
        val type = object : TypeToken<List<PendingLiveSim>>() {}.type
        val list = runCatching { gson.fromJson<List<PendingLiveSim>>(j, type) }.getOrDefault(emptyList())
        pendingLiveSims.clear()
        list.forEach { pendingLiveSims[it.key] = it }
    }

    fun saveSettings(s: AppSettings) {
        sp.edit().putString("settings", gson.toJson(s)).apply()
        binance.updateBase(s.binanceBaseUrl)
    }

    /**
     * 清除普通运行数据缓存，**不删配置**：
     * 保留 settings / strategies / overlays。
     * 清除：模拟成交与统计、内存行情/信号、通知去重、已下单键、应用 cacheDir。
     * @return 人类可读结果
     */
    fun clearDataCache(): String {
        val ed = sp.edit()
        var removedKeys = 0
        for (k in sp.all.keys) {
            if (k.startsWith("trades_") || k.startsWith("stats_")) {
                ed.remove(k)
                removedKeys++
            }
        }
        ed.apply()
        candles = emptyList()
        signals = emptyList()
        trades = emptyList()
        stats = Stats()
        notified.clear()
        lastNotifyAt.clear()
        placedOrderKeys.clear()
        liveSimKeys.clear()
        liveSimTrades = emptyList()
        liveSimStats = Stats()
        pendingLiveSims.clear()
        sp.edit().remove("live_sim_trades").apply()
        val bytes = deleteDirContents(appCtx.cacheDir) +
            deleteDirContents(appCtx.codeCacheDir)
        val mb = bytes / (1024.0 * 1024.0)
        return "已清普通缓存：临时键 ${removedKeys} 个，文件约 %.2f MB（配置/策略已保留）".format(mb)
    }

    private fun deleteDirContents(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += sizeOf(f)
            runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
        }
        return total
    }

    private fun sizeOf(f: java.io.File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    fun strategies(): List<StrategyConfig> {
        val j = sp.getString("strategies", null)
        if (j == null) {
            val d = defaultStrategies()
            saveStrategies(d)
            return d
        }
        // 旧版 JSON 无 kind 字段时 Gson 会把 kind 置 null，访问 ordinal 即崩溃；手工兜底
        val list = runCatching { parseStrategiesJson(j) }.getOrElse {
            runCatching {
                val type = object : TypeToken<List<StrategyConfig>>() {}.type
                gson.fromJson<List<StrategyConfig>>(j, type)?.map { normalizeStrategy(it) } ?: emptyList()
            }.getOrDefault(emptyList())
        }
        return list.ifEmpty { defaultStrategies() }
    }

    private fun defaultStrategies(): List<StrategyConfig> = listOf(
        StrategyConfig(
            id = UUID.randomUUID().toString(),
            title = "默认 RSI",
            enabled = true,
            kind = StrategyKind.RULES,
            buyRules = listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0, 14)),
            sellRules = listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14)),
        ),
        StrategyConfig(
            id = UUID.randomUUID().toString(),
            title = "顺势单边",
            enabled = false,
            kind = StrategyKind.ALGO,
            algoId = AlgoIds.TREND_FOLLOW,
            algoParams = mapOf(
                "fast" to 12.0, "slow" to 26.0, "consecutive" to 3.0, "cooldown" to 3.0,
            ),
            algoNote = "EMA快慢线顺势，连续确认后开单",
        ),
        StrategyConfig(
            id = UUID.randomUUID().toString(),
            title = "皮尔逊三曲线",
            enabled = false,
            kind = StrategyKind.ALGO,
            algoId = AlgoIds.PEARSON_TRIPLE,
            algoParams = mapOf(
                "p1" to 5.0, "p2" to 10.0, "p3" to 20.0,
                "window" to 30.0, "minCorr" to 0.55, "cooldown" to 2.0,
            ),
            algoNote = "三EMA皮尔逊相关+同向斜率共振",
        ),
        StrategyConfig(
            id = UUID.randomUUID().toString(),
            title = "逻辑回归模型",
            enabled = false,
            kind = StrategyKind.MODEL,
            modelId = ModelIds.LOGREG_V1,
            modelParams = mapOf(
                "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
                "confirmBars" to 1.0, "lookback" to 5.0,
            ),
            modelNote = "少而准：训练后自动校准阈值/冷却；勿追求每根K信号",
            modelTrainReport = "未训练",
        ),
    )

    /** 反射读取可能被 Gson 置空的枚举，避免 NPE */
    private fun normalizeStrategy(c: StrategyConfig): StrategyConfig {
        val kind = safeKind(c)
        val algoId = try {
            c.algoId.ifBlank { AlgoIds.TREND_FOLLOW }
        } catch (_: Exception) {
            AlgoIds.TREND_FOLLOW
        }
        val algoParams = try {
            c.algoParams ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val algoNote = try {
            c.algoNote ?: ""
        } catch (_: Exception) {
            ""
        }
        val buy = try {
            c.buyRules ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val sell = try {
            c.sellRules ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val modelId = try {
            c.modelId.ifBlank { ModelIds.LOGREG_V1 }
        } catch (_: Exception) {
            ModelIds.LOGREG_V1
        }
        val modelParams = try {
            c.modelParams ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val modelWeights = try {
            c.modelWeights ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val modelNote = try {
            c.modelNote ?: ""
        } catch (_: Exception) {
            ""
        }
        val modelTrainReport = try {
            c.modelTrainReport ?: ""
        } catch (_: Exception) {
            ""
        }
        return StrategyConfig(
            id = c.id,
            title = c.title,
            enabled = c.enabled,
            kind = kind,
            buyRules = buy.ifEmpty { listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0, 14)) },
            sellRules = sell.ifEmpty { listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14)) },
            algoId = algoId,
            algoParams = algoParams,
            algoNote = algoNote,
            modelId = modelId,
            modelParams = modelParams,
            modelWeights = modelWeights,
            modelNote = modelNote,
            modelTrainReport = modelTrainReport,
        )
    }

    private fun safeKind(c: StrategyConfig): StrategyKind {
        return try {
            val f = StrategyConfig::class.java.getDeclaredField("kind")
            f.isAccessible = true
            (f.get(c) as? StrategyKind) ?: StrategyKind.RULES
        } catch (_: Exception) {
            StrategyKind.RULES
        }
    }

    private fun parseStrategiesJson(j: String): List<StrategyConfig> {
        val root = JsonParser.parseString(j)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val id = o.get("id")?.asString ?: UUID.randomUUID().toString()
            val title = o.get("title")?.asString ?: "策略"
            val enabled = o.get("enabled")?.asBoolean ?: false
            val kindRaw = o.get("kind")?.asString?.uppercase()
            val kind = when {
                kindRaw == null || kindRaw.isBlank() -> StrategyKind.RULES
                kindRaw.contains("MODEL") || kindRaw.contains("模型") -> StrategyKind.MODEL
                kindRaw.contains("ALGO") || kindRaw.contains("算法") -> StrategyKind.ALGO
                else -> StrategyKind.RULES
            }
            val algoIdRaw = o.get("algoId")?.asString?.uppercase()?.replace("-", "_") ?: AlgoIds.TREND_FOLLOW
            val algoId = when {
                algoIdRaw.contains("PEARSON") -> AlgoIds.PEARSON_TRIPLE
                algoIdRaw.contains("BREAK") -> AlgoIds.BREAKOUT
                algoIdRaw in AlgoIds.all -> algoIdRaw
                else -> AlgoIds.TREND_FOLLOW
            }
            val algoParams = linkedMapOf<String, Double>()
            o.getAsJsonObject("algoParams")?.entrySet()?.forEach { e ->
                runCatching { e.value.asDouble }.getOrNull()?.let { algoParams[e.key] = it }
            }
            val algoNote = o.get("algoNote")?.asString ?: ""
            val modelId = o.get("modelId")?.asString?.ifBlank { ModelIds.LOGREG_V1 } ?: ModelIds.LOGREG_V1
            val modelParams = linkedMapOf<String, Double>()
            o.getAsJsonObject("modelParams")?.entrySet()?.forEach { e ->
                runCatching { e.value.asDouble }.getOrNull()?.let { modelParams[e.key] = it }
            }
            val modelWeights = linkedMapOf<String, Double>()
            o.getAsJsonObject("modelWeights")?.entrySet()?.forEach { e ->
                runCatching { e.value.asDouble }.getOrNull()?.let { modelWeights[e.key] = it }
            }
            val modelNote = o.get("modelNote")?.asString ?: ""
            val modelTrainReport = o.get("modelTrainReport")?.asString ?: ""
            fun rules(key: String): List<Rule> {
                val arr = o.getAsJsonArray(key) ?: return emptyList()
                return arr.mapNotNull { item ->
                    val r = item.asJsonObject
                    val indName = r.get("indicator")?.asString?.uppercase()?.replace("-", "_") ?: return@mapNotNull null
                    val indicator = IndicatorType.entries.find { it.name == indName } ?: when {
                        indName.contains("RSI") -> IndicatorType.RSI
                        indName.contains("MACD") -> IndicatorType.MACD
                        indName.contains("KDJ") -> IndicatorType.KDJ_J
                        indName.contains("BOLL_PCT") || indName.contains("BOLL") -> IndicatorType.BOLL_PCT
                        indName.contains("MA_BIAS") -> IndicatorType.MA_BIAS
                        indName.contains("EMA_BIAS") -> IndicatorType.EMA_BIAS
                        indName.contains("EMA") -> IndicatorType.EMA
                        indName.contains("MA") -> IndicatorType.MA
                        else -> IndicatorType.RSI
                    }
                    val opName = r.get("op")?.asString?.uppercase() ?: "LT"
                    val op = CompareOp.entries.find { it.name == opName } ?: CompareOp.LT
                    val value = r.get("value")?.asDouble ?: return@mapNotNull null
                    val period = r.get("period")?.asInt?.coerceIn(2, 200) ?: 14
                    Rule(indicator, op, value, period)
                }
            }
            StrategyConfig(
                id = id,
                title = title,
                enabled = enabled,
                kind = kind,
                buyRules = rules("buyRules").ifEmpty {
                    listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0, 14))
                },
                sellRules = rules("sellRules").ifEmpty {
                    listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14))
                },
                algoId = algoId,
                algoParams = algoParams,
                algoNote = algoNote,
                modelId = modelId,
                modelParams = modelParams,
                modelWeights = modelWeights,
                modelNote = modelNote,
                modelTrainReport = modelTrainReport,
            )
        }
    }

    fun saveStrategies(list: List<StrategyConfig>) {
        sp.edit().putString("strategies", gson.toJson(list)).apply()
    }

    /**
     * 手动训练模型策略：拉取当前标的/周期历史 K 线，SGD 逻辑回归，写回权重。
     */
    suspend fun trainModelStrategy(
        strategyId: String,
        epochs: Int = 40,
    ): Result<String> = withContext(Dispatchers.IO) {
        val list = strategies().toMutableList()
        val idx = list.indexOfFirst { it.id == strategyId }
        if (idx < 0) return@withContext Result.failure(IllegalStateException("策略不存在"))
        var cfg = list[idx]
        if (cfg.kind != StrategyKind.MODEL) {
            cfg = cfg.copy(kind = StrategyKind.MODEL)
        }
        val s = settings()
        binance.updateBase(s.binanceBaseUrl)
        val bars = try {
            binance.fetch(
                s.symbol,
                Interval.from(s.interval),
                s.klineLimit.coerceIn(200, 1000),
            )
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val result = ModelEngine.train(bars, cfg.modelParams, epochs = epochs)
        if (result.weights.isEmpty()) {
            return@withContext Result.failure(IllegalStateException(result.report))
        }
        var updated = cfg.copy(
            kind = StrategyKind.MODEL,
            modelId = cfg.modelId.ifBlank { ModelIds.LOGREG_V1 },
            modelWeights = result.weights,
            modelTrainReport = result.report + " · ${s.symbol} ${s.interval}",
        )
        // 训练后自动校准 threshold/cooldown/minEdge，抑制「每根K都信号」
        val cal = ModelEngine.calibrate(
            bars, updated,
            minTrades = 6,
            maxTradesRatio = 0.10,
            targetWinRate = 0.55,
        )
        updated = updated.copy(
            modelParams = cal.params,
            modelTrainReport = result.report + "\n" + cal.report + " · ${s.symbol} ${s.interval}",
        )
        list[idx] = updated
        saveStrategies(list)
        Result.success(updated.modelTrainReport)
    }

    /**
     * 仅校准参数（不重训权重）：适合已有权重但信号过密/过稀时手动调用。
     */
    suspend fun calibrateModelStrategy(strategyId: String): Result<String> = withContext(Dispatchers.IO) {
        val list = strategies().toMutableList()
        val idx = list.indexOfFirst { it.id == strategyId }
        if (idx < 0) return@withContext Result.failure(IllegalStateException("策略不存在"))
        val cfg = list[idx]
        if (cfg.kind != StrategyKind.MODEL) {
            return@withContext Result.failure(IllegalStateException("非模型策略"))
        }
        if (cfg.modelWeights.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("请先训练权重"))
        }
        val s = settings()
        binance.updateBase(s.binanceBaseUrl)
        val bars = try {
            binance.fetch(s.symbol, Interval.from(s.interval), s.klineLimit.coerceIn(200, 1000))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val cal = ModelEngine.calibrate(bars, cfg, minTrades = 6, maxTradesRatio = 0.10, targetWinRate = 0.55)
        list[idx] = cfg.copy(modelParams = cal.params, modelTrainReport = cal.report + " · ${s.symbol} ${s.interval}")
        saveStrategies(list)
        Result.success(cal.report)
    }

    /** 清除模型权重（回退默认弱权重） */
    fun clearModelWeights(strategyId: String): Boolean {
        val list = strategies().toMutableList()
        val idx = list.indexOfFirst { it.id == strategyId }
        if (idx < 0) return false
        list[idx] = list[idx].copy(modelWeights = emptyMap(), modelTrainReport = "权重已清除")
        saveStrategies(list)
        return true
    }

    fun enabledStrategy(): StrategyConfig? = strategies().find { it.enabled }

    fun setOverlays(list: List<ChartOverlay>) {
        overlays = list
        sp.edit().putString("overlays", gson.toJson(list)).apply()
    }

    fun loadOverlays() {
        val j = sp.getString("overlays", null) ?: return
        val type = object : TypeToken<List<ChartOverlay>>() {}.type
        overlays = runCatching { gson.fromJson<List<ChartOverlay>>(j, type) }.getOrDefault(emptyList())
    }

    fun addFibOverlay(fromPrice: Double, toPrice: Double) {
        val hi = maxOf(fromPrice, toPrice)
        val lo = minOf(fromPrice, toPrice)
        val levels = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0).map { r ->
            hi - (hi - lo) * r
        }
        val o = ChartOverlay(
            UUID.randomUUID().toString(), "fib", levels,
            "Fib ${"%.1f".format(lo)}-${"%.1f".format(hi)}",
        )
        setOverlays(overlays + o)
    }

    fun clearOverlays() = setOverlays(emptyList())

    suspend fun refreshMarket(): Result<Unit> {
        val s = settings()
        binance.updateBase(s.binanceBaseUrl)
        return try {
            val bars = binance.fetch(
                s.symbol,
                Interval.from(s.interval),
                s.klineLimit.coerceIn(200, 1000),
            )
            loadOverlays()
            if (s.strategyRunning) {
                val cfg = enabledStrategy()
                if (cfg != null && bars.size >= minBarsForSignal(s.interval)) {
                    runStrategy(
                        s.symbol, s.interval, cfg, bars,
                        notifyNew = false, updateUiState = false,
                    )
                    val htMarks = StrategyEngine.signals(bars, cfg).map { it.copy(tag = "ht") }
                    var chartMarks = htMarks
                    // 1m 信号映射到当前行情周期，画在同一张图上
                    val want1m = s.signalMode1mConfirm || (!s.signalMode1mConfirm && !s.signalModeHtNative)
                    if (want1m) {
                        try {
                            val bars1m = binance.fetch(s.symbol, Interval.M1, s.klineLimit.coerceIn(200, 500))
                            if (bars1m.size >= minBarsForSignal("1m")) {
                                val m1 = StrategyEngine.signals(bars1m, cfg)
                                val mapped = map1mMarksToInterval(
                                    m1, bars, s.interval, onlyHtConfirmed = true,
                                )
                                chartMarks = mergeChartSignals(mapped, htMarks)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    candles = bars
                    signals = chartMarks
                    val (tlist, st) = EventSim.backtest(bars, chartMarks, s.symbol, s.interval)
                    trades = tlist
                    stats = st
                } else {
                    candles = bars
                    if (cfg != null && bars.size < minBarsForSignal(s.interval)) {
                        signals = emptyList()
                        trades = emptyList()
                        stats = Stats()
                    }
                }
            } else {
                candles = bars
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 对指定 K 线与周期跑策略。
     * @param updateUiState 是否写入当前界面用的 candles/signals/stats（仅选中周期为 true）
     */
    fun runStrategy(
        symbol: String,
        interval: String,
        cfg: StrategyConfig,
        barData: List<Candle>,
        notifyNew: Boolean,
        updateUiState: Boolean = false,
    ): Pair<List<SignalMark>, Stats> {
        val rawMarks = StrategyEngine.signals(barData, cfg)
        val marks = rawMarks.map { m ->
            m.copy(tag = if (interval == "1m") "1m" else "ht")
        }
        val (tlist, st) = EventSim.backtest(barData, marks, symbol, interval)
        sp.edit()
            .putString("trades_$interval", gson.toJson(tlist))
            .putString("stats_$interval", gson.toJson(st))
            .apply()
        if (updateUiState) {
            candles = barData
            signals = marks
            trades = tlist
            stats = st
        }
        return marks to st
    }

    private fun intervalMs(code: String): Long = when (code) {
        "1m" -> 60_000L
        "5m" -> 5 * 60_000L
        "10m" -> 10 * 60_000L
        "30m" -> 30 * 60_000L
        "1h" -> 60 * 60_000L
        else -> 10 * 60_000L
    }

    private fun trimNotified() {
        while (notified.size > maxNotifiedKeys) {
            val first = notified.firstOrNull() ?: break
            notified.remove(first)
        }
    }

    /** 仅盯最近已收盘 1～2 根，避免「半小时前的信号」被当成新信号推送 */
    private fun notifyWatchBars(interval: String): Int = when (interval) {
        "1m" -> 2
        else -> 2
    }

    /** 收盘后允许的最大延迟；1m 主源更严，避免过期推送 */
    private fun maxLateMs(interval: String): Long = when (interval) {
        "1m" -> 120_000L
        "5m" -> 90_000L
        "10m" -> 120_000L
        "30m" -> 180_000L
        "1h" -> 240_000L
        else -> 120_000L
    }

    private fun isSignalExpired(interval: String, openTime: Long, now: Long = System.currentTimeMillis()): Boolean {
        val closedAt = openTime + intervalMs(interval)
        return now - closedAt > maxLateMs(interval)
    }

    /** 策略/指标最少需要的历史根数 */
    private fun minBarsForSignal(interval: String): Int = when (interval) {
        "1m" -> 120
        "5m", "10m" -> 120
        "30m", "1h" -> 100
        else -> 100
    }

    /**
     * 高周期确认：1m 出信号后，用目标交易周期的近端结构做同向过滤，减少噪声。
     * 计算仍用完整 HT 历史；此处只做方向偏置，不替代 1m 触发。
     */
    private fun higherTfAgrees(barsHt: List<Candle>, side: String): Boolean {
        if (barsHt.size < 25) return true
        val closed = if (barsHt.size >= 2) barsHt.dropLast(1) else barsHt
        val window = closed.takeLast(20)
        val ma = window.map { it.close }.average()
        val last = window.last().close
        // 软确认：不与近端均线强烈逆向即可
        return when (side) {
            "B" -> last >= ma * 0.992
            "S" -> last <= ma * 1.008
            else -> true
        }
    }

    /** 将 1m 信号对齐到目标周期 K 线 openTime，供回测/图表展示 */
    /** 图表用：1m 映射信号 + 周期原生信号合并，便于在对应周期图上观测 */
    private fun mergeChartSignals(primary: List<SignalMark>, extra: List<SignalMark>): List<SignalMark> {
        val out = LinkedHashMap<String, SignalMark>()
        for (m in primary + extra) {
            val k = "${m.openTime}|${m.side}|${m.tag.ifBlank { "x" }}"
            out[k] = m
        }
        return out.values.sortedBy { it.openTime }
    }

    /**
     * 将 1m 策略信号叠加到目标周期 K 线：
     * openTime → 该 1m 时刻所落在的目标周期棒的 openTime
     * price → 该棒收盘价（便于画在图上）
     * tag=1m 与周期原生 ht 区分
     * @param onlyHtConfirmed 为 true 时仅保留高周期软确认通过的信号（与通知逻辑一致）
     */
    private fun map1mMarksToInterval(
        marks1m: List<SignalMark>,
        barsHt: List<Candle>,
        interval: String,
        onlyHtConfirmed: Boolean = false,
    ): List<SignalMark> {
        if (marks1m.isEmpty() || barsHt.isEmpty()) return emptyList()
        val ivMs = intervalMs(interval)
        val out = LinkedHashMap<String, SignalMark>()
        for (m in marks1m) {
            if (onlyHtConfirmed && !higherTfAgrees(barsHt, m.side)) continue
            val bar = barsHt.lastOrNull {
                it.openTime <= m.openTime && m.openTime < it.openTime + ivMs
            } ?: barsHt.minByOrNull { kotlin.math.abs(it.openTime - m.openTime) }
            if (bar == null) continue
            val mark = SignalMark(
                openTime = bar.openTime,
                side = m.side,
                price = bar.close,
                tag = "1m",
            )
            // 同一根目标 K 同向只保留一根（后出现的覆盖）
            out["${mark.openTime}|${mark.side}"] = mark
        }
        return out.values.sortedBy { it.openTime }
    }

    /**
     * 多周期新信号过滤：
     * 1) 全量历史只用于计算/回测；通知只针对「启动后新出现」的信号
     * 2) 首次接触某周期：把当前全部历史信号记为已见，不发任何通知（避免一打开狂推）
     * 3) 去重 + 同周期同方向冷却
     * 4) 仅最近已收盘窗口内、且启动后新出现的信号才通知
     */
    private fun freshMarks(
        symbol: String,
        interval: String,
        marks: List<SignalMark>,
        barData: List<Candle>,
        notifyNew: Boolean,
    ): List<SignalMark> {
        if (barData.isEmpty()) return emptyList()
        val endExclusive = if (barData.size >= 2) barData.size - 1 else barData.size
        val watchCount = notifyWatchBars(interval).coerceAtMost(endExclusive.coerceAtLeast(1))
        val from = (endExclusive - watchCount).coerceAtLeast(0)
        val watchTimes = if (endExclusive > from) {
            barData.subList(from, endExclusive).map { it.openTime }.toSet()
        } else emptySet()

        val prefix = "$symbol|$interval|"
        val hadSeed = notified.any { it.startsWith(prefix) }
        // 首次（或策略刚启动后清空过）：种子全部历史，绝不通知旧信号
        if (!hadSeed) {
            marks.forEach { m -> notified.add(key(symbol, interval, m)) }
            trimNotified()
            return emptyList()
        }
        if (!notifyNew) {
            marks.forEach { mark -> notified.add(key(symbol, interval, mark)) }
            trimNotified()
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val cooldown = intervalMs(interval) / 2
        val sideCooldownKey = { side: String -> "$symbol|$interval|$side" }

        return marks
            .filter { it.openTime in watchTimes }
            .filter { !isSignalExpired(interval, it.openTime, now) }
            .filter { key(symbol, interval, it) !in notified }
            .filter {
                val last = lastNotifyAt[sideCooldownKey(it.side)] ?: 0L
                // 冷却缩短为周期的 1/4，避免漏掉相邻新信号
                now - last >= (cooldown / 2).coerceAtLeast(30_000L)
            }
            .sortedByDescending { it.openTime } // 最新优先
            .onEach {
                notified.add(key(symbol, interval, it))
                lastNotifyAt[sideCooldownKey(it.side)] = now
            }
            .also { trimNotified() }
    }

    /** 策略启动时调用：清空通知状态并重新种子，避免历史回测信号弹通知 */
    fun resetNotificationState() {
        notified.clear()
        lastNotifyAt.clear()
    }

    /**
     * 同一轮 poll 内跨周期过滤：
     * - 全周期信号都保留通知（不漏周期）
     * - 若同方向、时间接近（≤5m），按该周期总胜率降序，胜率明显更低的标记在 payload 中仍发送但去抖：
     *   同向且 openTime 相差在 5 分钟内，只保留胜率最高的一条 + 与最高相差不超过 15% 的其它周期（共振）
     */
    private fun filterCrossInterval(batch: List<SignalNotifyPayload>): List<SignalNotifyPayload> {
        if (batch.size <= 1) return batch
        val result = mutableListOf<SignalNotifyPayload>()
        val bySide = batch.groupBy { it.mark.side }
        for ((_, list) in bySide) {
            val sorted = list.sortedWith(
                compareByDescending<SignalNotifyPayload> { it.intervalWinRatePct }
                    .thenBy { intervalMs(it.interval) }, // 同胜率优先更短周期（更及时）
            )
            val best = sorted.first()
            result.add(best)
            for (p in sorted.drop(1)) {
                val closeInTime = kotlin.math.abs(p.mark.openTime - best.mark.openTime) <= 5 * 60_000L
                if (!closeInTime) {
                    result.add(p)
                    continue
                }
                // 时间接近：保留胜率不低于最佳 15 个百分点的共振周期
                if (p.intervalWinRatePct >= best.intervalWinRatePct - 15.0) {
                    result.add(p)
                }
                // 否则视为噪声，丢弃通知（仍已写入 notified，避免反复尝试）
            }
        }
        return result.sortedWith(
            compareByDescending<SignalNotifyPayload> { it.intervalWinRatePct }
                .thenBy { it.mark.openTime },
        )
    }

    /**
     * 后台轮询（低延迟）：
     * - 以 1m K 线为信号触发主源（约每分钟可出新信号，不再等 5m 收盘）
     * - 对每个事件合约周期 5m/10m/30m/1h：用该周期完整历史做回测统计 + 同向软确认
     * - 通知打在对应交易周期上，便于 HiBT 时间单位与胜率展示
     */
    suspend fun poll(): List<SignalNotifyPayload> = withContext(Dispatchers.IO) {
        val s = settings()
        if (!s.strategyRunning) return@withContext emptyList()
        binance.updateBase(s.binanceBaseUrl)
        val cfg = enabledStrategy() ?: return@withContext emptyList()
        val tradeIntervals = listOf("5m", "10m", "30m", "1h")
        val limit1m = s.klineLimit.coerceIn(200, 500)
        val limitHt = s.klineLimit.coerceIn(200, 500)
        val mode1m = s.signalMode1mConfirm
        val modeHt = s.signalModeHtNative
        // 至少一个模式开启；都关则退回双开
        val use1m = mode1m || (!mode1m && !modeHt)
        val useHt = modeHt || (!mode1m && !modeHt)

        val batch = mutableListOf<SignalNotifyPayload>()
        var bars1m: List<Candle> = emptyList()

        // —— 模式一：1m 触发 + 高周期软确认 ——
        if (use1m) {
            bars1m = try {
                binance.fetch(s.symbol, Interval.M1, limit1m)
            } catch (_: Exception) {
                emptyList()
            }
            if (bars1m.size >= minBarsForSignal("1m")) {
                val (marks1m, _) = runStrategy(
                    s.symbol, "1m", cfg, bars1m,
                    notifyNew = true,
                    updateUiState = false,
                )
                val fresh1m = freshMarks(s.symbol, "1m", marks1m, bars1m, notifyNew = true)
                if (fresh1m.isNotEmpty()) {
                    val from1m = coroutineScope {
                        tradeIntervals.map { iv ->
                            async {
                                try {
                                    val barsHt = binance.fetch(s.symbol, Interval.from(iv), limitHt)
                                    if (barsHt.size < minBarsForSignal(iv)) return@async emptyList()
                                    val mapped = map1mMarksToInterval(marks1m, barsHt, iv)
                                    val (tlist, st) = EventSim.backtest(barsHt, mapped, s.symbol, iv)
                                    sp.edit()
                                        .putString("trades_$iv", gson.toJson(tlist))
                                        .putString("stats_$iv", gson.toJson(st))
                                        .apply()
                                    // 图表信号统一在 poll 末尾 merge，避免此处覆盖丢 1m 叠加
                                    if (iv == s.interval) {
                                        trades = tlist
                                        stats = st
                                    }
                                    fresh1m.mapNotNull { m ->
                                        if (!higherTfAgrees(barsHt, m.side)) return@mapNotNull null
                                        if (isSignalExpired("1m", m.openTime)) return@mapNotNull null
                                        val ai = if (s.hibt.aiEvaluate) {
                                            val toMs = s.llmTimeoutSec.coerceIn(10, 300) * 1000L
                                            withTimeoutOrNull(toMs) {
                                                evaluateSignal(s, m, iv, barsHt, st.winRate * 100, st.trades)
                                            }
                                        } else null
                                        SignalNotifyPayload(
                                            mark = m,
                                            interval = iv,
                                            intervalWinRatePct = st.winRate * 100,
                                            intervalTrades = st.trades,
                                            ai = ai,
                                            source = "1m_confirm",
                                        )
                                    }
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }
                    batch += from1m
                }
            }
        }

        // —— 模式二：各交易周期原生策略信号 ——
        if (useHt) {
            val fromHt = coroutineScope {
                tradeIntervals.map { iv ->
                    async {
                        try {
                            val barsHt = binance.fetch(s.symbol, Interval.from(iv), limitHt)
                            if (barsHt.size < minBarsForSignal(iv)) return@async emptyList()
                            val (marksHt, st) = runStrategy(
                                s.symbol, iv, cfg, barsHt,
                                notifyNew = true,
                                updateUiState = (iv == s.interval && !use1m),
                            )
                            val fresh = freshMarks(s.symbol, iv, marksHt, barsHt, notifyNew = true)
                            fresh.mapNotNull { m ->
                                if (isSignalExpired(iv, m.openTime)) return@mapNotNull null
                                val ai = if (s.hibt.aiEvaluate) {
                                    val toMs = s.llmTimeoutSec.coerceIn(10, 300) * 1000L
                                    withTimeoutOrNull(toMs) {
                                        evaluateSignal(s, m, iv, barsHt, st.winRate * 100, st.trades)
                                    }
                                } else null
                                SignalNotifyPayload(
                                    mark = m,
                                    interval = iv,
                                    intervalWinRatePct = st.winRate * 100,
                                    intervalTrades = st.trades,
                                    ai = ai,
                                    source = "ht_native",
                                )
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            batch += fromHt
        }


        // —— 行情图：当前选中周期合并 1m 映射信号 + 周期原生信号 ——
        run {
            try {
                val barsChart = binance.fetch(
                    s.symbol,
                    Interval.from(s.interval),
                    limitHt,
                )
                if (barsChart.size >= minBarsForSignal(s.interval)) {
                    val htMarks = if (useHt) {
                        StrategyEngine.signals(barsChart, cfg).map { it.copy(tag = "ht") }
                    } else emptyList()
                    val mapped1m = if (use1m && bars1m.size >= minBarsForSignal("1m")) {
                        val m1 = StrategyEngine.signals(bars1m, cfg)
                        // 叠加到当前行情周期图：1m 信号 + 高周期确认
                        map1mMarksToInterval(m1, barsChart, s.interval, onlyHtConfirmed = true)
                    } else emptyList()
                    candles = barsChart
                    signals = mergeChartSignals(mapped1m, htMarks)
                    val (tlist, st) = EventSim.backtest(barsChart, signals, s.symbol, s.interval)
                    trades = tlist
                    stats = st
                }
            } catch (_: Exception) {
            }
        }

        val timely = batch.filter {
            val baseIv = if (it.source == "1m_confirm") "1m" else it.interval
            !isSignalExpired(baseIv, it.mark.openTime)
        }
        // 去重：同方向 + 同周期 + 同 openTime 只留一条（优先 1m_confirm）
        val dedup = linkedMapOf<String, SignalNotifyPayload>()
        for (p in timely.sortedWith(
            compareByDescending<SignalNotifyPayload> { it.source == "1m_confirm" }
                .thenByDescending { it.interval == s.interval }
                .thenByDescending { it.intervalWinRatePct },
        )) {
            val k = "${p.mark.openTime}|${p.mark.side}|${p.interval}"
            if (k !in dedup) dedup[k] = p
        }
        val out = filterCrossInterval(dedup.values.toList())

        // 自动下单：遍历本轮所有及时信号（含 AI 结果），按配置过滤
        run {
            val h = s.hibt
            val selected = h.autoIntervals
                .map { it.trim().lowercase() }
                .filter { it in setOf("5m", "10m", "30m", "1h") }
                .ifEmpty { listOf("5m", "10m", "30m", "1h") }
            val passedAi = timely.filter { it.ai?.passThreshold == true }
            if (!h.autoTrade) {
                if (passedAi.isNotEmpty()) {
                    HibtWebSession.appendLog(
                        "有${passedAi.size}条AI达阈值但【自动化下单未开启】，仅通知不落单",
                    )
                }
                return@run
            }
            val barsForChase = bars1m.ifEmpty {
                try {
                    binance.fetch(s.symbol, Interval.M1, 80)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val orderList = timely.filter { it.interval.lowercase() in selected }
            if (orderList.isEmpty() && timely.isNotEmpty()) {
                HibtWebSession.appendLog(
                    "自动下单：本轮信号周期${timely.map { it.interval }.distinct()} 不在勾选周期$selected 内，跳过",
                )
            }
            if (h.dryRun && passedAi.any { it.interval.lowercase() in selected }) {
                HibtWebSession.appendLog(
                    "注意：Dry-Run 仍开启 → 达阈值也只会模拟，不会真实下单。请在下单页关闭 Dry-Run",
                )
            }
            for (p in orderList) {
                if (h.antiChaseEnabled && barsForChase.isNotEmpty() &&
                    isOneSidedChase(barsForChase, p.mark.side, h.antiChaseBars)
                ) {
                    HibtWebSession.appendLog(
                        "防追单跳过 ${p.mark.side} ${p.interval} ai=${p.ai?.winRatePct}",
                    )
                    continue
                }
                maybeAutoOrder(s, p.mark, p.ai, p.interval)
            }
        }

        // —— 实时模拟（策略面板）——
        // 规则：只用信号标注的周期 K 线；1m 扇出时只模拟当前选中周期，避免一次信号开满全周期
        if (s.liveSimEnabled) {
            val simTargets = timely.filter { p ->
                when {
                    p.source == "ht_native" -> true
                    p.source == "1m_confirm" -> p.interval.equals(s.interval, ignoreCase = true)
                    else -> p.interval.equals(s.interval, ignoreCase = true)
                }
            }
            val barsCache = mutableMapOf<String, List<Candle>>()
            suspend fun barsOf(iv: String): List<Candle> {
                barsCache[iv]?.let { return it }
                val b = try {
                    binance.fetch(s.symbol, Interval.from(iv), s.klineLimit.coerceIn(100, 500))
                } catch (_: Exception) {
                    emptyList()
                }
                barsCache[iv] = b
                return b
            }
            for (p in simTargets) {
                val bars = barsOf(p.interval)
                maybeLiveSim(s, p.mark, p.ai, p.interval, bars)
            }
            // 对有挂单的周期统一尝试平仓（即使本轮无新信号）
            val pendingIvs = pendingLiveSims.values.map { it.interval }.distinct()
            for (iv in pendingIvs) {
                settlePendingLiveSims(s, iv)
            }
            try {
                maybeAutoRetune(s)
            } catch (e: Exception) {
                HibtWebSession.appendLog("自动调优异常: ${e.message}")
            }
        }
        out
    }

    /**
     * AI 评估：根据该周期行情 K 线估计「本信号方向在对应交易时间段」的胜率。
     * 与本地回测总胜率解耦；无 Key / 调用失败时不把总胜率当作 AI 结果。
     */
    suspend fun evaluateSignal(
        s: AppSettings,
        m: SignalMark,
        interval: String,
        barData: List<Candle>,
        hist: Double = stats.winRate * 100,
        tradeCount: Int = stats.trades,
    ): AiEvalResult {
        val h = s.hibt
        val dir = if (m.side == "B") "买入/看涨" else "卖出/看跌"
        val threshold = h.aiMinWinRate
        if (s.llmApiKey.isBlank()) {
            return AiEvalResult(
                winRatePct = null,
                summary = "未配置 LLM Key，无法做行情 AI 评估（不会用回测总胜率冒充）",
                passThreshold = false,
                thresholdPct = threshold,
                error = "no_llm_key",
            )
        }
        if (barData.size < 10) {
            return AiEvalResult(
                winRatePct = null,
                summary = "该周期 K 线不足，无法评估",
                passThreshold = false,
                thresholdPct = threshold,
                error = "insufficient_bars",
            )
        }
        return try {
            val maxBars = s.llmEvalMaxBars.coerceIn(6, 48)
            val market = buildSignalMarketBrief(s.symbol, interval, barData, m, maxBars = maxBars)
            val sys = (
                "你是加密事件合约信号评估助手。" +
                    "仅根据提供的行情K线与信号，估计该信号方向在本交易周期内获胜的概率(0-100)。" +
                    "必须基于K线结构（趋势、动量、区间位置、最近收阳/收阴）独立判断，" +
                    "禁止把本地回测总胜率直接当答案，禁止总是输出接近50或固定数字。" +
                    "第一行严格输出 WINRATE:数字 ，随后1-3句中文理由。"
            )
            val user = (
                market +
                    "\n【信号】方向: " + dir + " (" + m.side + ") 信号价: " + m.price +
                    "\n【交易周期】" + interval +
                    "\n【参考-本地回测总胜率】" + "%.1f".format(hist) + "% / " + tradeCount + "笔（勿直接照抄）" +
                    "\n【程序阈值】" + "%.1f".format(threshold) + "%（你只需输出WINRATE，是否达阈值由程序判断）"
            )
            val ans = llm.chat(
                s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, user, s.llmTimeoutSec,
                maxTokens = s.llmMaxTokens.coerceIn(64, 2048),
                temperature = s.llmTemperature.toDouble().coerceIn(0.0, 2.0),
                thinkingEnabled = s.llmThinkingEnabled,
            )
            val winEst = parseWinRate(ans, avoidEcho = hist)
            if (winEst == null) {
                return@evaluateSignal AiEvalResult(
                    winRatePct = null,
                    summary = "AI 未返回可解析的 WINRATE: " + ans.take(100),
                    passThreshold = false,
                    thresholdPct = threshold,
                    error = "parse_fail",
                )
            }
            val reason = ans.lines()
                .filter { !it.uppercase().contains("WINRATE") }
                .joinToString(" ")
                .trim()
                .ifBlank { ans.take(120) }
            val pass = winEst >= threshold
            AiEvalResult(
                winRatePct = winEst,
                summary = reason.take(180),
                passThreshold = pass,
                thresholdPct = threshold,
            )
        } catch (e: Exception) {
            AiEvalResult(
                winRatePct = null,
                summary = "AI 调用失败: " + (e.message ?: "unknown"),
                passThreshold = false,
                thresholdPct = threshold,
                error = e.message,
            )
        }
    }

    private fun buildSignalMarketBrief(
        symbol: String,
        interval: String,
        barData: List<Candle>,
        m: SignalMark,
        maxBars: Int = 40,
    ): String {
        val n = minOf(maxBars.coerceIn(6, 48), barData.size)
        val bars = barData.takeLast(n)
        val last = bars.last()
        val hi = bars.maxOf { it.high }
        val lo = bars.minOf { it.low }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("【行情】" + symbol + " 周期=" + interval + " 近" + bars.size + "根")
        sb.appendLine(
            "最新: " + fmt.format(java.util.Date(last.openTime)) +
                " O=" + "%.4f".format(last.open) +
                " H=" + "%.4f".format(last.high) +
                " L=" + "%.4f".format(last.low) +
                " C=" + "%.4f".format(last.close),
        )
        sb.appendLine("近端区间高=" + "%.4f".format(hi) + " 低=" + "%.4f".format(lo))
        sb.appendLine("信号时间: " + fmt.format(java.util.Date(m.openTime)))
        sb.appendLine("【K线 time,O,H,L,C】")
        val step = if (bars.size <= 24) 1 else (bars.size + 23) / 24
        var i = 0
        while (i < bars.size) {
            val c = bars[i]
            sb.appendLine(
                fmt.format(java.util.Date(c.openTime)) + "," +
                    "%.4f".format(c.open) + "," + "%.4f".format(c.high) + "," +
                    "%.4f".format(c.low) + "," + "%.4f".format(c.close),
            )
            i += step
        }
        return sb.toString()
    }

    private fun parseWinRate(text: String, avoidEcho: Double? = null): Double? {
        val primary = Regex("WINRATE\\s*[:=：]\\s*(\\d{1,3}(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        primary.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { v ->
            if (v in 0.0..100.0) return v
        }
        val pct = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*%")
        val candidates = pct.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in 0.0..100.0 }
            .toList()
        if (candidates.isEmpty()) return null
        if (avoidEcho != null) {
            val filtered = candidates.filter { kotlin.math.abs(it - avoidEcho) > 0.15 }
            if (filtered.isNotEmpty()) return filtered.first()
        }
        return candidates.firstOrNull()
    }

    /**
     * 行情页选中周期 → 事件合约 timeUnit（分钟）。
     * 只认 5m/10m/30m/1h；与官网档位 5/10/30/60 对齐（另有 15 可选）。
     */
    private fun eventTimeUnitMinutes(intervalCode: String): Int {
        return when (intervalCode.trim().lowercase()) {
            "5m" -> 5
            "10m" -> 10
            "15m" -> 15
            "30m" -> 30
            "1h", "60m" -> 60
            "1m" -> 5 // 信号用 1m K 线时，合约仍按下单页/默认 5m，避免误开 1 分钟不存在的档
            else -> Interval.from(intervalCode).timeUnit.let { u ->
                if (u in setOf(5, 10, 15, 30, 60)) u else 5
            }
        }
    }

    /**
     * 大单边行情防追单：近 N 根 1m K 几乎同向且涨跌占窗口波幅主导时，
     * 禁止继续同向自动下单（避免死硬追涨杀跌）。
     */
    private fun isOneSidedChase(bars: List<Candle>, side: String, barCount: Int): Boolean {
        val n = barCount.coerceIn(4, 20)
        if (bars.size < n) return false
        val win = bars.takeLast(n)
        val ups = win.count { it.close > it.open }
        val downs = win.count { it.close < it.open }
        val first = win.first().open
        val last = win.last().close
        val move = last - first
        val range = (win.maxOf { it.high } - win.minOf { it.low }).coerceAtLeast(1e-12)
        val dominance = kotlin.math.abs(move) / range
        // 至少约 80% K 线同向，且净位移占窗口高低波幅 >= 55%
        val strongUp = ups >= (n * 4 / 5) && move > 0 && dominance >= 0.55
        val strongDown = downs >= (n * 4 / 5) && move < 0 && dominance >= 0.55
        return when (side) {
            "B" -> strongUp   // 已大涨还买涨 = 追多
            "S" -> strongDown // 已大跌还买跌 = 追空
            else -> false
        }
    }




    /**
     * 实时模拟开仓：
     * - 周期对齐仅用于确定 entryTime / expiryTime
     * - 开仓价、平仓价一律走数据服务 priceAt(时刻)
     */
    private suspend fun maybeLiveSim(
        s: AppSettings,
        m: SignalMark,
        ai: AiEvalResult?,
        intervalCode: String,
        barData: List<Candle>,
    ) {
        if (!s.liveSimEnabled) return
        if (s.hibt.aiEvaluate) {
            if (ai?.passThreshold != true) return
        }
        if (barData.size < 2) return

        val ivMs = intervalMs(intervalCode)
        val signalIdx = barData.indexOfLast { it.openTime <= m.openTime }
        if (signalIdx < 0) {
            HibtWebSession.appendLog(
                "模拟跳过: ${m.side} $intervalCode 信号时点未落在${intervalCode}K线内 t=${m.openTime}",
            )
            return
        }
        val entryIdx = signalIdx + 1
        if (entryIdx >= barData.size) return

        val entryTime = barData[entryIdx].openTime
        val expiryTime = entryTime + ivMs
        val posKey = "${s.symbol}|$intervalCode|$entryTime|${m.side}"
        if (posKey in liveSimKeys || pendingLiveSims.containsKey(posKey)) return

        // 开仓价：仅 Binance GET klines startTime=entryTime
        val entryPrice = try {
            binance.priceAt(s.symbol, entryTime, preferClose = false)
        } catch (e: Exception) {
            HibtWebSession.appendLog("模拟开仓取价失败(Binance): ${e.message}")
            null
        }
        if (entryPrice == null) {
            HibtWebSession.appendLog(
                "模拟开仓暂缓 ${m.side} $intervalCode: Binance 无 t=$entryTime 价格，下轮重试",
            )
            return
        }

        pendingLiveSims[posKey] = PendingLiveSim(
            key = posKey,
            symbol = s.symbol,
            interval = intervalCode,
            side = m.side,
            signalTime = m.openTime,
            entryTime = entryTime,
            entryPrice = entryPrice,
        )
        savePendingToDisk()
        bumpLiveSim()
        HibtWebSession.appendLog(
            "模拟开仓 ${m.side} $intervalCode @${"%.4f".format(entryPrice)} " +
                "t=$entryTime 到期=$expiryTime（Binance 1m open）· 持仓数=${pendingLiveSims.size}",
        )
        settlePendingLiveSims(s, intervalCode)
    }

    /**
     * 平仓：仅当 now >= 到期时间，用数据服务取「到期时刻」价格结算。
     */
    private suspend fun settlePendingLiveSims(
        s: AppSettings,
        intervalCode: String,
    ) {
        val ivMs = intervalMs(intervalCode)
        val now = System.currentTimeMillis()
        val toClose = pendingLiveSims.values.filter {
            it.interval == intervalCode && it.symbol == s.symbol
        }
        for (p in toClose) {
            val expiry = p.entryTime + ivMs
            if (now < expiry) continue // 未到期

            val exitPrice = try {
                binance.priceAt(s.symbol, expiry, preferClose = true)
            } catch (e: Exception) {
                HibtWebSession.appendLog("模拟平仓取价失败(Binance): ${e.message}")
                null
            }
            if (exitPrice == null) {
                HibtWebSession.appendLog(
                    "模拟平仓暂缓 ${p.side} ${p.interval}: Binance 无到期 t=$expiry 价格",
                )
                continue
            }
            val long = p.side == "B"
            val pnl = if (long) (exitPrice - p.entryPrice) / p.entryPrice * 100
            else (p.entryPrice - exitPrice) / p.entryPrice * 100
            val win = pnl > 1e-9
            val trade = SimTrade(
                id = java.util.UUID.randomUUID().toString(),
                symbol = p.symbol,
                interval = p.interval,
                side = p.side,
                entryTime = p.entryTime,
                entryPrice = p.entryPrice,
                exitTime = expiry,
                exitPrice = exitPrice,
                pnlPct = pnl,
                win = win,
            )
            liveSimKeys.add(p.key)
            pendingLiveSims.remove(p.key)
            liveSimTrades = (liveSimTrades + trade).takeLast(200)
            liveSimStats = statsOf(liveSimTrades)
            saveLiveSimToDisk()
            savePendingToDisk()
            bumpLiveSim()
            HibtWebSession.appendLog(
                "模拟平仓 ${p.side} ${p.interval} entry=${"%.4f".format(p.entryPrice)} " +
                    "expiry=${"%.4f".format(exitPrice)}(Binance) @t=$expiry " +
                    "pnl=${"%.3f".format(pnl)}% ${if (win) "盈" else "亏"} · " +
                    "持仓${pendingLiveSims.size} 累计${liveSimStats.trades}笔 胜率${"%.1f".format(liveSimStats.winRate * 100)}%",
            )
        }
    }

    /** 胜率过低或连亏 → 自动 LLM 调优当前启用策略 */
    private suspend fun maybeAutoRetune(s: AppSettings) {
        if (!s.liveSimAutoRetune || !s.liveSimEnabled) return
        if (retuneInProgress) return
        if (System.currentTimeMillis() - lastAutoRetuneAt < 15 * 60_000L) return
        val st = liveSimStats
        if (st.trades < s.liveSimMinTradesBeforeRetune.coerceAtLeast(3)) return
        val consec = consecutiveLosses()
        val wrPct = st.winRate * 100
        val lowWr = wrPct < s.liveSimMinWinRatePct
        val tooManyLoss = consec >= s.liveSimMaxConsecutiveLosses.coerceIn(2, 10)
        if (!lowWr && !tooManyLoss) return
        val en = enabledStrategy() ?: return
        retuneInProgress = true
        lastAutoRetuneAt = System.currentTimeMillis()
        val reason = buildString {
            if (lowWr) append("模拟胜率${"%.1f".format(wrPct)}%<${s.liveSimMinWinRatePct}% ")
            if (tooManyLoss) append("连亏${consec}笔 ")
        }
        HibtWebSession.appendLog(">>> 自动调优触发: $reason · 策略「${en.title}」 kind=${en.kind}")
        try {
            if (en.kind == StrategyKind.MODEL) {
                // 模型：本地重训权重 + 校准参数（不依赖 LLM）
                Notify.orderResult(
                    appCtx, ok = true, dryRun = true,
                    message = "模型自动重训+校准中：$reason",
                    sideLabel = "模型调优", amount = null, timeUnit = null,
                )
                trainModelStrategy(en.id, epochs = 45).fold(
                    onSuccess = {
                        HibtWebSession.appendLog("<<< 模型自动调优完成: ${it.take(160)}")
                        Notify.orderResult(
                            appCtx, ok = true, dryRun = true,
                            message = "模型调优完成 ${it.take(80)}",
                            sideLabel = "模型调优", amount = null, timeUnit = null,
                        )
                    },
                    onFailure = {
                        HibtWebSession.appendLog("<<< 模型自动调优失败: ${it.message}")
                    },
                )
            } else {
                if (s.llmApiKey.isBlank()) {
                    HibtWebSession.appendLog("<<< 自动调优跳过: 非MODEL策略需要 LLM Key")
                    return
                }
                Notify.orderResult(
                    appCtx, ok = true, dryRun = true,
                    message = "策略自动调优中：$reason",
                    sideLabel = "LLM调优", amount = null, timeUnit = null,
                )
                val goal = buildString {
                    append("根据实时模拟表现自动调优。")
                    append("当前模拟${st.trades}笔 胜率${"%.1f".format(wrPct)}% 连亏$consec。")
                    append("目标：提高胜率、减少噪声信号，轮次4，目标胜率${"%.0f".format(s.liveSimMinWinRatePct.coerceAtLeast(50.0))}%，最少12笔。")
                    when (en.kind) {
                        StrategyKind.ALGO ->
                            append("当前算法 ${en.algoId} 参数 ${en.algoParams}。请直接改 algoParams。")
                        StrategyKind.MODEL ->
                            append("当前为 MODEL，请提高 threshold/cooldown/minEdge，禁止降低阈值导致信号过密。")
                        else ->
                            append("当前为指标规则，可改为 ALGO 或收紧买卖阈值。")
                    }
                }
                optimizeStrategyWithLlm(
                    baseId = en.id,
                    rounds = 4,
                    userGoal = goal,
                    minWinRatePct = s.liveSimMinWinRatePct.coerceAtLeast(50.0),
                    minTrades = 12,
                ).fold(
                    onSuccess = {
                        HibtWebSession.appendLog(
                            "<<< 自动调优完成 胜率${"%.1f".format(it.winRate * 100)}% ${it.trades}笔",
                        )
                        Notify.orderResult(
                            appCtx, ok = true, dryRun = true,
                            message = "调优完成 回测胜率${"%.1f".format(it.winRate * 100)}% ${it.trades}笔",
                            sideLabel = "LLM调优", amount = null, timeUnit = null,
                        )
                    },
                    onFailure = {
                        HibtWebSession.appendLog("<<< 自动调优失败: ${it.message}")
                    },
                )
            }
        } finally {
            retuneInProgress = false
        }
    }

    private suspend fun maybeAutoOrder(
        s: AppSettings,
        m: SignalMark,
        ai: AiEvalResult?,
        intervalCode: String,
    ) {
        val h = s.hibt
        // 真实自动下单：autoTrade + aiEvaluate + passThreshold + !dryRun + WebView
        if (!h.autoTrade) return
        if (!h.aiEvaluate) {
            HibtWebSession.appendLog(
                "跳过下单: AI评估未开 ${s.symbol} ${m.side} $intervalCode",
            )
            return
        }
        if (ai == null) {
            HibtWebSession.appendLog(
                "跳过下单: 无AI结果(超时/失败) ${s.symbol} ${m.side} $intervalCode",
            )
            return
        }
        if (ai.winRatePct == null || ai.passThreshold != true) {
            HibtWebSession.appendLog(
                "跳过下单: 未达阈值 ai=${ai.winRatePct} 阈=${ai.thresholdPct} " +
                    "pass=${ai.passThreshold} ${m.side} $intervalCode",
            )
            return
        }
        val unit = eventTimeUnitMinutes(intervalCode)
        val key = "${s.symbol}|${m.side}|${m.openTime}|${unit}"
        synchronized(placeLock) {
            if (!placedOrderKeys.add(key)) {
                HibtWebSession.appendLog("跳过下单: 同信号已处理过 $key")
                return
            }
        }
        val mode = if (h.dryRun) "DRY-RUN模拟" else "真实下单"
        val triggerMsg =
            ">>> 触发自动下单[$mode] ${s.symbol} ${m.side} tu=${unit}m iv=$intervalCode " +
                "ai=${ai.winRatePct}%≥${ai.thresholdPct}% amt=${h.defaultAmount}"
        HibtWebSession.appendLog(triggerMsg)
        // 触发即通知，避免 WebView 卡住时用户完全无感知
        Notify.orderResult(
            appCtx,
            ok = true,
            dryRun = h.dryRun,
            message = if (h.dryRun) {
                "AI达阈值，Dry-Run 模拟中（不会真实成交）"
            } else {
                "AI达阈值，正在提交下单… 请保持 WebView 已登录并锁定合约页"
            },
            sideLabel = if (m.side == "B") "买涨 B" else "买跌 S",
            amount = h.defaultAmount,
            timeUnit = unit,
        )
        val result = try {
            placePreferWeb(
                directionUp = m.side == "B",
                amount = h.defaultAmount,
                symbol = s.symbol,
                timeUnit = unit,
                cfg = h,
            )
        } catch (e: Exception) {
            HibtWebSession.appendLog("自动下单异常: ${e.message}")
            HibtClient.OrderResult(false, "异常: ${e.message}", dryRun = h.dryRun, raw = e.message)
        }
        HibtWebSession.appendLog(
            "<<< 自动下单结果 ok=${result.ok} dry=${result.dryRun} ${result.message.take(200)}",
        )
        // 结果再通知一次（覆盖“正在提交”）
        Notify.orderResult(
            appCtx,
            ok = result.ok,
            dryRun = result.dryRun,
            message = result.message,
            sideLabel = if (m.side == "B") "买涨 B" else "买跌 S",
            amount = h.defaultAmount,
            timeUnit = unit,
        )
        if (result.dryRun || !result.ok) {
            placedOrderKeys.remove(key)
        }
    }

    /**
     * 统一只走 WebView 下单，禁止原生时间戳 v / 直连接口下单（避免登录失效与错误 v）。
     */
    private suspend fun placePreferWeb(
        directionUp: Boolean,
        amount: Double,
        symbol: String,
        timeUnit: Int,
        cfg: HibtSettings,
    ): HibtClient.OrderResult {
        // 勿把「无 WebView」误标成 dryRun=true（用户设置可能是实盘）
        if (HibtWebSession.peek() == null) {
            HibtWebSession.appendLog("placePreferWeb: WebView=null，尝试 ensureAlive 重建")
            HibtWebSession.ensureAlive(appCtx)
        }
        if (HibtWebSession.peek() == null) {
            val msg =
                "【WebView 未启动】请先在下单页打开 WebView 登录，进入事件合约并「锁定下单页」，" +
                    "再点「隐藏(保活)」。不要点「停止 WebView」。当前 dryRun=${cfg.dryRun}"
            Notify.orderResult(
                appCtx, ok = false, dryRun = cfg.dryRun, message = msg,
                sideLabel = if (directionUp) "买涨" else "买跌",
                amount = amount, timeUnit = timeUnit,
            )
            return HibtClient.OrderResult(false, msg, dryRun = cfg.dryRun, raw = msg)
        }
        // 自动场景超时过长易“只见触发不见结果”；上限 35s，仍可用设置项但不超过 45
        val to = cfg.placeTimeoutSec.coerceIn(12, 45).coerceAtMost(35)
        HibtWebSession.appendLog("placePreferWeb 开始 dry=${cfg.dryRun} tu=$timeUnit to=${to}s peek=${HibtWebSession.peek()!=null}")
        val outcome = try {
            HibtWebSession.placeOrder(
                directionUp = directionUp,
                amount = amount,
                symbol = symbol,
                timeUnit = timeUnit,
                dryRun = cfg.dryRun,
                timeoutSec = to,
            )
        } catch (e: Exception) {
            HibtWebSession.appendLog("placeOrder异常: ${e.message}")
            null
        } ?: HibtWebSession.PlaceOutcome(
            false,
            "WebView 无响应/超时，请打开 WebView 登录并「锁定当前为下单页」后重试",
            cfg.dryRun,
        )
        val result = HibtClient.OrderResult(
            ok = outcome.ok,
            message = "[WebView] ${outcome.message}",
            dryRun = outcome.dryRun,
            raw = outcome.message,
        )
        HibtWebSession.appendLog(
            "placePreferWeb ok=${result.ok} dry=${result.dryRun} ${result.message.take(160)}"
        )
        // 真实下单或失败都通知；Dry-Run 也通知一条便于确认走的是 WebView
        Notify.orderResult(
            appCtx,
            ok = result.ok,
            dryRun = result.dryRun,
            message = result.message,
            sideLabel = if (directionUp) "买涨 B" else "买跌 S",
            amount = amount,
            timeUnit = timeUnit,
        )
        return result
    }

    suspend fun hibtTest() = hibt.testConnectivity(settings().hibt)

    suspend fun hibtPlace(up: Boolean, amount: Double? = null, intervalCode: String? = null): HibtClient.OrderResult {
        val s = settings()
        val iv = intervalCode
            ?: s.hibt.autoIntervals.firstOrNull()
            ?: s.interval
        val unit = eventTimeUnitMinutes(iv)
        val amt = amount ?: s.hibt.defaultAmount
        return placePreferWeb(up, amt, s.symbol, unit, s.hibt)
    }

    /** Chat 指令：策略/指标/斐波那契 */
    suspend fun handleChatCommand(user: String): String? {
        val t = user.trim()
        when {
            t.startsWith("/fib") || t.contains("斐波那契") -> {
                if (candles.size < 20) return "K 线不足，无法绘制斐波那契"
                val win = candles.takeLast(50)
                val hi = win.maxOf { it.high }
                val lo = win.minOf { it.low }
                addFibOverlay(lo, hi)
                return "已添加临时斐波那契（${"%.1f".format(lo)} ~ ${"%.1f".format(hi)}），见行情图。发送「清除绘图」可删除。"
            }
            t.contains("清除绘图") || t == "/clear_overlay" -> {
                clearOverlays()
                return "已清除临时绘图"
            }
            t.startsWith("/ma") || t.matches(Regex("""打开\s*MA\s*\d+""", RegexOption.IGNORE_CASE)) -> {
                val p = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: 20
                toggleChartIndicator("MA$p", p, true)
                return "已开启图表指标 MA$p（设置里可改周期）"
            }
            t.contains("列出策略") || t == "/strategies" -> {
                return strategies().joinToString("\n") { "${if (it.enabled) "●" else "○"} ${it.title} (${it.id.take(6)})" }
                    .ifBlank { "暂无策略" }
            }
            t.startsWith("启用策略") || t.startsWith("/enable ") -> {
                val name = t.removePrefix("启用策略").removePrefix("/enable").trim()
                val list = strategies().map { it.copy(enabled = it.title.contains(name) || it.id.startsWith(name)) }
                if (list.none { it.enabled }) return "未找到策略: $name"
                saveStrategies(list)
                return "已启用匹配「$name」的策略"
            }
            t.startsWith("添加策略") || t.startsWith("/add_strategy") -> {
                val title = t.substringAfter("：").substringAfter(":").ifBlank { "Chat策略${strategies().size + 1}" }
                val cfg = StrategyConfig(UUID.randomUUID().toString(), title.trim(), false)
                saveStrategies(strategies() + cfg)
                return "已添加策略「${cfg.title}」，请到策略页启用并编辑条件。"
            }
        }
        return null
    }

    private fun toggleChartIndicator(name: String, period: Int, enabled: Boolean) {
        val s = settings()
        val id = name.lowercase()
        val exists = s.chartIndicators.any { it.id == id || it.name.equals(name, true) }
        val list = if (exists) {
            s.chartIndicators.map {
                if (it.id == id || it.name.equals(name, true)) it.copy(enabled = enabled, period = period)
                else it
            }
        } else {
            s.chartIndicators + ChartIndicatorPref(id, name, enabled, period)
        }
        saveSettings(s.copy(chartIndicators = list))
    }

    /**
     * 将最近 [n] 根 K 线整理成文本，供 LLM 分析（控制长度避免超上下文）。
     */
    fun buildMarketContext(n: Int = 30): String {
        val s = settings()
        val count = n.coerceIn(5, 120)
        if (candles.isEmpty()) {
            return "当前无K线数据，请先在行情页刷新。"
        }
        val bars = candles.takeLast(count)
        val first = bars.first()
        val last = bars.last()
        val hi = bars.maxOf { it.high }
        val lo = bars.minOf { it.low }
        val chg = last.close - first.open
        val chgPct = if (first.open != 0.0) chg / first.open * 100 else 0.0
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("【行情摘要】")
        sb.appendLine("品种: ${s.symbol}  周期: ${s.interval}")
        sb.appendLine("样本: 最近 ${bars.size} 根K线（共缓存 ${candles.size}）")
        sb.appendLine("区间: ${fmt.format(java.util.Date(first.openTime))} ~ ${fmt.format(java.util.Date(last.openTime))}")
        sb.appendLine("开:${"%.4f".format(first.open)} 最新收:${"%.4f".format(last.close)}")
        sb.appendLine("区间高:${"%.4f".format(hi)} 区间低:${"%.4f".format(lo)}")
        sb.appendLine("区间涨跌: ${"%.4f".format(chg)} (${"%+.2f".format(chgPct)}%)")
        if (stats.trades > 0) {
            sb.appendLine("本地模拟: ${stats.trades}笔 胜率 ${"%.1f".format(stats.winRate * 100)}%")
        }
        sb.appendLine("【K线明细 time,O,H,L,C】")
        // 过长时抽样：头尾多、中间抽稀
        val lines = if (bars.size <= 40) {
            bars
        } else {
            val head = bars.take(12)
            val tail = bars.takeLast(12)
            val mid = bars.drop(12).dropLast(12)
            val step = (mid.size / 16).coerceAtLeast(1)
            head + mid.filterIndexed { i, _ -> i % step == 0 }.take(16) + tail
        }
        for (c in lines) {
            sb.appendLine(
                "${fmt.format(java.util.Date(c.openTime))}," +
                    "${"%.4f".format(c.open)},${"%.4f".format(c.high)}," +
                    "${"%.4f".format(c.low)},${"%.4f".format(c.close)}",
            )
        }
        if (lines.size < bars.size) {
            sb.appendLine("（中间已抽样，共输出 ${lines.size}/${bars.size} 根）")
        }
        return sb.toString()
    }

    /**
     * @param attachMarketBars 若 >0，将最近 N 根行情附到 user 消息供分析
     */
    suspend fun chat(user: String, attachMarketBars: Int = 0): String {
        handleChatCommand(user)?.let { return it }
        val s = settings()
        val market = if (attachMarketBars > 0) buildMarketContext(attachMarketBars) else ""
        val sys = """
            你是手机端 Crypto Agent 助手，擅长加密行情与事件合约思路分析。
            当前: ${s.symbol} ${s.interval}, 本地K线缓存 ${candles.size} 根, 模拟 ${stats.trades} 笔, 胜率 ${"%.1f".format(stats.winRate * 100)}%.
            若用户消息附带【行情摘要】与K线明细，请据此分析趋势、支撑阻力、波动与风险，用简洁中文；不要编造未给出的数据。
            本地命令仍可用: 斐波那契, 清除绘图, 打开MA20, 列出策略, 添加策略：名称
        """.trimIndent()
        val userPayload = if (market.isNotBlank()) {
            market + "\n\n" + "【用户问题】" + "\n" + user
        } else {
            user
        }
        return llm.chat(
            s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, userPayload, s.llmTimeoutSec,
            maxTokens = maxOf(s.llmMaxTokens, 256).coerceIn(64, 2048),
            temperature = s.llmTemperature.toDouble().coerceIn(0.0, 1.5),
            thinkingEnabled = s.llmThinkingEnabled,
        )
    }



    /**
     * LLM + 本地历史回测闭环优化策略。
     * @param baseId 非空则基于该策略优化并更新；空则生成新策略
     * @param rounds 与 LLM 迭代轮数（每轮：出策略 → 回测 → 反馈）
     */
    suspend fun optimizeStrategyWithLlm(
        baseId: String? = null,
        rounds: Int = 2,
        userGoal: String? = null,
        minWinRatePct: Double? = null,
        minTrades: Int? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<StrategyOptimizeResult> = withContext(Dispatchers.IO) {
        val s = settings()
        if (s.llmApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("请先在设置中配置 LLM API Key"))
        }
        binance.updateBase(s.binanceBaseUrl)
        val bars = try {
            binance.fetch(
                s.symbol,
                Interval.from(s.interval),
                s.klineLimit.coerceIn(200, 1000),
            )
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        if (bars.size < 80) {
            return@withContext Result.failure(IllegalStateException("历史K线不足(${bars.size})，无法回测优化"))
        }
        val existing = strategies()
        val base = baseId?.let { id -> existing.find { it.id == id } }
        val createNew = base == null
        var bestCfg: StrategyConfig = base ?: StrategyConfig(
            id = java.util.UUID.randomUUID().toString(),
            title = "LLM策略-${s.interval}",
            enabled = false,
        )
        var bestWr = -1.0
        var bestTrades = 0
        val log = StringBuilder()
        log.appendLine("标的 ${s.symbol} 周期 ${s.interval} K线 ${bars.size} 根 · 迭代 $rounds 轮")
        if (base != null) {
            val (t0, st0) = EventSim.backtest(
                bars, StrategyEngine.signals(bars, base), s.symbol, s.interval,
            )
            bestWr = st0.winRate
            bestTrades = st0.trades
            log.appendLine("基准「${base.title}」回测: ${st0.trades}笔 胜率${"%.1f".format(st0.winRate * 100)}%")
            onProgress?.invoke("基准回测 胜率${"%.1f".format(st0.winRate * 100)}%")
        }

        val goal = userGoal?.trim().orEmpty()
        val targetWr = minWinRatePct
        val targetTrades = minTrades
        if (goal.isNotBlank()) {
            log.appendLine("用户需求: $goal")
            onProgress?.invoke("需求: ${goal.take(40)}")
        }
        if (targetWr != null) log.appendLine("目标胜率 ≥ ${"%.1f".format(targetWr)}%")
        if (targetTrades != null) log.appendLine("最少成交 ≥ $targetTrades 笔")
        onProgress?.invoke(
            buildString {
                append("训练 ${rounds.coerceIn(1, 6)} 轮")
                targetWr?.let { append(" · 胜率≥${"%.0f".format(it)}%") }
                targetTrades?.let { append(" · ≥${it}笔") }
            },
        )
        var lastFeedback = buildString {
            if (goal.isNotBlank()) {
                appendLine("【用户明确需求——必须优先满足】")
                appendLine(goal)
                appendLine("可优先 kind=ALGO（TREND_FOLLOW/PEARSON_TRIPLE/BREAKOUT）。")
                appendLine("必须在 JSON 里给出可执行的 algoParams 数值（或 buyRules/sellRules），禁止空参数。")
                appendLine("不要无故退化成仅 RSI+KDJ；需求说明变更时以最新需求为准。")
            }
            if (targetWr != null || targetTrades != null) {
                appendLine("【训练约束——回测必须尽量达到】")
                targetWr?.let { appendLine("- 胜率 ≥ ${"%.1f".format(it)}%") }
                targetTrades?.let { appendLine("- 成交笔数 ≥ $it") }
                appendLine("未达标时请加大趋势过滤或调整 algoParams/规则。")
            }
            if (base != null) {
                appendLine("当前策略JSON:")
                appendLine(strategyToJson(base))
                appendLine("回测: ${bestTrades}笔 胜率${"%.1f".format(bestWr * 100)}%。在满足约束与需求下提升表现。")
            } else if (goal.isBlank()) {
                appendLine("请从零设计事件合约策略；优先 ALGO 顺势/皮尔逊，高胜率且交易不宜过少。")
            } else {
                appendLine("请严格按用户需求从零设计策略 JSON。")
            }
        }

        val schema = """
三种策略 kind（优先 ALGO；也可 MODEL 小模型）:

0) kind=MODEL  可训练逻辑回归（少而准，禁止每根K都出信号）
modelId 固定 LOGREG_V1。
modelParams 必须偏严格：
- threshold: 0.62~0.72（越高信号越少越准，默认0.64）
- cooldown: 8~24（两次信号最少间隔K线数）
- minEdge: 0.03~0.08（额外边距，越大越稀）
- confirmBars: 1~2
- lookback: 3~8
禁止把 threshold 降到 0.55 以下或 cooldown 小于 6（会导致信号过密、胜率差）。
权重由 App 本地训练+校准，JSON 不要编造 modelWeights。
例: {"title":"LR高胜率","kind":"MODEL","modelId":"LOGREG_V1","modelParams":{"threshold":0.66,"cooldown":14,"minEdge":0.05,"confirmBars":1,"lookback":5},"modelNote":"少而准","buyRules":[],"sellRules":[]}

1) kind=ALGO  算法配置（推荐）
algoId 只能是:
- TREND_FOLLOW 顺势/单边: params fast,slow,consecutive,cooldown
- PEARSON_TRIPLE 皮尔逊三曲线: params p1,p2,p3,window,minCorr,cooldown
- BREAKOUT 突破: params lookback,cooldown
algoNote 用中文写意图；algoParams 全是数字。
例:
{"title":"顺势","kind":"ALGO","algoId":"TREND_FOLLOW","algoParams":{"fast":12,"slow":26,"consecutive":3,"cooldown":3},"algoNote":"单边顺势","buyRules":[],"sellRules":[]}
{"title":"三曲线","kind":"ALGO","algoId":"PEARSON_TRIPLE","algoParams":{"p1":5,"p2":10,"p3":20,"window":30,"minCorr":0.55,"cooldown":2},"algoNote":"皮尔逊共振","buyRules":[],"sellRules":[]}

2) kind=RULES  指标规则（备用）
indicator: RSI,MACD,KDJ_J,BOLL_PCT,MA_BIAS,EMA_BIAS （禁止绝对币价）
op: GT,GTE,LT,LTE  period:2-200  同侧OR
例:
{"title":"布林","kind":"RULES","buyRules":[{"indicator":"BOLL_PCT","op":"LT","value":15,"period":20}],"sellRules":[{"indicator":"BOLL_PCT","op":"GT","value":85,"period":20}]}

只输出一个 JSON，不要 markdown。用户要顺势/单边/皮尔逊时必须 kind=ALGO。
""".trimIndent()

        val marketBrief = buildSignalMarketBrief(
            s.symbol, s.interval, bars,
            SignalMark(bars.last().openTime, "B", bars.last().close),
            maxBars = minOf(32, bars.size),
        )

        val maxRounds = rounds.coerceIn(1, 6)
        var metTarget = false
        for (round in 1..maxRounds) {
            onProgress?.invoke("第${round}/${maxRounds}轮：请求 LLM…")
            val sys = """
你是量化策略工程师。根据历史K线与回测反馈，输出可在本App运行的策略JSON。
$schema
禁止编造未给出的数据；不要解释，只输出JSON。
""".trimIndent()
            val user = """
$marketBrief

【任务】第${round}轮优化（事件合约视角，周期=${s.interval}）
${if (goal.isNotBlank()) "用户需求: $goal\n" else ""}$lastFeedback

请输出改进后的完整策略JSON。
""".trimIndent()
            val ans = try {
                llm.chat(
                    s.llmBaseUrl, s.llmApiKey, s.llmModel, sys, user, s.llmTimeoutSec,
                    maxTokens = maxOf(s.llmMaxTokens, 400).coerceIn(128, 2048),
                    temperature = (s.llmTemperature.toDouble() + 0.1).coerceIn(0.1, 1.2),
                    thinkingEnabled = s.llmThinkingEnabled,
                )
            } catch (e: Exception) {
                log.appendLine("第${round}轮 LLM失败: ${e.message}")
                onProgress?.invoke("第${round}轮失败: ${e.message?.take(40)}")
                continue
            }
            val parsed = parseStrategyFromLlm(ans, bestCfg.id, keepEnabled = base?.enabled == true)
            if (parsed == null) {
                log.appendLine("第${round}轮解析失败: ${ans.take(120)}")
                onProgress?.invoke("第${round}轮JSON解析失败")
                lastFeedback = "上轮输出无法解析，请严格只输出JSON。样例见schema。上轮片段:\n${ans.take(200)}"
                continue
            }
            val marks = StrategyEngine.signals(bars, parsed)
            val (tlist, st) = EventSim.backtest(bars, marks, s.symbol, s.interval)
            log.appendLine(
                "第${round}轮「${parsed.title}」: ${st.trades}笔 胜率${"%.1f".format(st.winRate * 100)}% " +
                    "买${parsed.buyRules.size}条/卖${parsed.sellRules.size}条",
            )
            onProgress?.invoke(
                "第${round}轮 回测 ${st.trades}笔 胜率${"%.1f".format(st.winRate * 100)}%",
            )
            // 未指定目标时禁止「提前结束」，必须跑满轮次；有目标才可提前达标退出
            fun meets(stWr: Double, stTrades: Int): Boolean {
                if (targetWr == null && targetTrades == null) return false
                val okWr = targetWr == null || stWr * 100 >= targetWr - 1e-6
                val okTr = targetTrades == null || stTrades >= targetTrades
                return okWr && okTr && stTrades >= 3
            }
            // 评分：达标优先，再比胜率与笔数
            fun scoreOf(stWr: Double, stTrades: Int): Double {
                val base = stWr * 100 + minOf(stTrades, 40) * 0.2
                return if (meets(stWr, stTrades)) base + 1000.0 else base
            }
            if (scoreOf(st.winRate, st.trades) >= scoreOf(bestWr, bestTrades) &&
                (st.trades >= 1 || bestTrades < 1)
            ) {
                bestCfg = parsed
                bestWr = st.winRate
                bestTrades = st.trades
            }
            if (meets(st.winRate, st.trades)) {
                metTarget = true
                log.appendLine("第${round}轮已达训练目标，提前结束")
                onProgress?.invoke("已达目标，提前结束")
            }
            lastFeedback = buildString {
                appendLine("上轮策略:")
                appendLine(strategyToJson(parsed))
                appendLine("回测: ${st.trades}笔 胜率${"%.1f".format(st.winRate * 100)}% kind=${parsed.kind} algo=${parsed.algoId}")
                appendLine("当前最优: ${bestTrades}笔 胜率${"%.1f".format(bestWr * 100)}%")
                if (targetWr != null || targetTrades != null) {
                    appendLine("目标: " + listOfNotNull(
                        targetWr?.let { "胜率≥${"%.0f".format(it)}%" },
                        targetTrades?.let { "笔数≥$it" },
                    ).joinToString("，"))
                    if (!meets(st.winRate, st.trades)) {
                        appendLine("未达标：请调整 algoParams 或规则，优先 ALGO 顺势/皮尔逊。")
                    }
                } else {
                    appendLine("请继续提高胜率，避免交易过少；优先 kind=ALGO。")
                }
            }.trimIndent()
            if (metTarget) break
        }

        if (bestTrades < 1 && createNew) {
            // 兜底：仍保存 LLM 最后能解析的结构
            log.appendLine("警告: 有效成交不足，仍保存当前最优结构供手工调整")
        }
        val list = strategies().toMutableList()
        if (createNew) {
            list.add(bestCfg.copy(enabled = false))
        } else {
            val i = list.indexOfFirst { it.id == bestCfg.id }
            if (i >= 0) list[i] = bestCfg else list.add(bestCfg)
        }
        saveStrategies(list)
        val report = log.toString() + "\n最终: 「${bestCfg.title}」 ${bestTrades}笔 胜率${"%.1f".format(bestWr * 100)}% " +
            (if (createNew) "【已新建】" else "【已更新】")
        onProgress?.invoke("完成 胜率${"%.1f".format(bestWr * 100)}%")
        Result.success(
            StrategyOptimizeResult(
                strategy = bestCfg,
                report = report,
                winRate = bestWr,
                trades = bestTrades,
                created = createNew,
            ),
        )
    }

    private fun strategyToJson(cfg: StrategyConfig): String {
        fun rules(rs: List<Rule>) = rs.joinToString(",") { r ->
            """{"indicator":"${r.indicator.name}","op":"${r.op.name}","value":${r.value},"period":${r.period}}"""
        }
        val params = cfg.algoParams.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        val note = cfg.algoNote.replace("\"", "").replace("\n", " ")
        return """{"title":"${cfg.title.replace("\"", "")}","kind":"${cfg.kind.name}","algoId":"${cfg.algoId}","algoParams":{$params},"algoNote":"$note","buyRules":[${rules(cfg.buyRules)}],"sellRules":[${rules(cfg.sellRules)}]}"""
    }

    private fun parseStrategyFromLlm(
        text: String,
        id: String,
        keepEnabled: Boolean,
    ): StrategyConfig? {
        val jsonStr = extractJsonObject(text) ?: return null
        return try {
            val o = JsonParser.parseString(jsonStr).asJsonObject
            val title = o.get("title")?.asString?.take(40) ?: "LLM策略"
            val kindRaw = o.get("kind")?.asString?.uppercase() ?: "RULES"
            val kind = when {
                kindRaw.contains("MODEL") || kindRaw.contains("模型") -> StrategyKind.MODEL
                kindRaw.contains("ALGO") || kindRaw.contains("算法") -> StrategyKind.ALGO
                else -> StrategyKind.RULES
            }
            val algoIdRaw = o.get("algoId")?.asString?.uppercase()?.replace("-", "_") ?: AlgoIds.TREND_FOLLOW
            val algoId = when {
                algoIdRaw.contains("PEARSON") || algoIdRaw.contains("皮尔逊") || algoIdRaw.contains("三曲线") ->
                    AlgoIds.PEARSON_TRIPLE
                algoIdRaw.contains("BREAK") || algoIdRaw.contains("突破") -> AlgoIds.BREAKOUT
                algoIdRaw.contains("TREND") || algoIdRaw.contains("顺势") || algoIdRaw.contains("单边") ->
                    AlgoIds.TREND_FOLLOW
                algoIdRaw in AlgoIds.all -> algoIdRaw
                else -> AlgoIds.TREND_FOLLOW
            }
            val algoParams = linkedMapOf<String, Double>()
            o.getAsJsonObject("algoParams")?.entrySet()?.forEach { e ->
                val v = runCatching { e.value.asDouble }.getOrNull()
                if (v != null) algoParams[e.key] = v
            }
            val algoNote = o.get("algoNote")?.asString?.take(200) ?: ""
            fun parseRules(key: String): List<Rule> {
                val arr = o.getAsJsonArray(key) ?: return emptyList()
                return arr.mapNotNull { el ->
                    val r = el.asJsonObject
                    val ind = r.get("indicator")?.asString?.uppercase()?.replace("-", "_") ?: return@mapNotNull null
                    val indicator = IndicatorType.entries.find {
                        it.name == ind || it.name == ind.replace("KDJJ", "KDJ_J") ||
                            it.label == r.get("indicator")?.asString
                    } ?: when {
                        ind.contains("BOLL_PCT") || ind.contains("BOLLPTC") ||
                            ind.contains("布林位置") || (ind.contains("PERCENT") && ind.contains("BOLL")) ->
                            IndicatorType.BOLL_PCT
                        ind.contains("MA_BIAS") || ind.contains("MABIAS") || ind.contains("MA偏离") ->
                            IndicatorType.MA_BIAS
                        ind.contains("EMA_BIAS") || ind.contains("EMABIAS") || ind.contains("EMA偏离") ->
                            IndicatorType.EMA_BIAS
                        ind.contains("RSI") -> IndicatorType.RSI
                        ind.contains("MACD") -> IndicatorType.MACD
                        ind.contains("KDJ") -> IndicatorType.KDJ_J
                        ind.contains("EMA") -> IndicatorType.EMA
                        ind.contains("MA") -> IndicatorType.MA
                        ind.contains("BOLL") -> IndicatorType.BOLL_PCT
                        ind.contains("CLOSE") || ind.contains("收盘") -> IndicatorType.CLOSE
                        else -> return@mapNotNull null
                    }
                    val opRaw = r.get("op")?.asString?.uppercase() ?: "LT"
                    val op = CompareOp.entries.find { it.name == opRaw }
                        ?: when {
                            opRaw.contains("GTE") || opRaw == ">=" -> CompareOp.GTE
                            opRaw.contains("GT") || opRaw == ">" -> CompareOp.GT
                            opRaw.contains("LTE") || opRaw == "<=" -> CompareOp.LTE
                            else -> CompareOp.LT
                        }
                    val value = r.get("value")?.asDouble ?: return@mapNotNull null
                    val period = r.get("period")?.asInt?.coerceIn(2, 200) ?: 14
                    Rule(indicator, op, value, period)
                }
            }
            val buy = parseRules("buyRules")
            val sell = parseRules("sellRules")
            val modelParams = linkedMapOf<String, Double>()
            o.getAsJsonObject("modelParams")?.entrySet()?.forEach { e ->
                runCatching { e.value.asDouble }.getOrNull()?.let { modelParams[e.key] = it }
            }
            val modelNote = o.get("modelNote")?.asString?.take(200) ?: ""
            val modelIdRaw = o.get("modelId")?.asString?.uppercase() ?: ModelIds.LOGREG_V1
            val modelId = if (modelIdRaw.contains("LOGREG") || modelIdRaw.isBlank()) ModelIds.LOGREG_V1 else modelIdRaw
            val finalKind = when {
                kind == StrategyKind.MODEL -> StrategyKind.MODEL
                kind == StrategyKind.ALGO -> StrategyKind.ALGO
                buy.isEmpty() && sell.isEmpty() && algoParams.isNotEmpty() -> StrategyKind.ALGO
                else -> kind
            }
            StrategyConfig(
                id = id,
                title = title,
                enabled = keepEnabled,
                kind = finalKind,
                buyRules = if (finalKind == StrategyKind.RULES && buy.isEmpty())
                    listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0, 14)) else buy,
                sellRules = if (finalKind == StrategyKind.RULES && sell.isEmpty())
                    listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14)) else sell,
                algoId = algoId,
                algoParams = algoParams,
                algoNote = algoNote,
                modelId = modelId,
                modelParams = modelParams.ifEmpty {
                    mapOf(
                        "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
                        "confirmBars" to 1.0, "lookback" to 5.0,
                    )
                },
                modelNote = modelNote,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonObject(text: String): String? {
        val t = text.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(t)
        val body = fence?.groupValues?.getOrNull(1)?.trim() ?: t
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return body.substring(start, end + 1)
    }


    private fun key(symbol: String, interval: String, m: SignalMark) =
        "$symbol|$interval|${m.openTime}|${m.side}"
}
