package com.puito.cryptoagent.domain

import com.puito.cryptoagent.data.Candle
import com.puito.cryptoagent.data.ModelIds
import com.puito.cryptoagent.data.SignalMark
import com.puito.cryptoagent.data.StrategyConfig
import kotlin.math.exp

/**
 * 可训练小模型引擎（本地、无第三方依赖）。
 * LOGREG_V1：逻辑回归；目标是「少而准」的事件合约方向信号，而非每根 K 都出信号。
 *
 * 信号条件（同时满足）：
 * - score ≥ threshold+minEdge → B；score ≤ 1-(threshold+minEdge) → S
 * - 与上一次信号至少间隔 cooldown 根
 * - 可选 requireConfirm：连续 confirmBars 根同向偏置
 */
object ModelEngine {

    data class TrainResult(
        val weights: Map<String, Double>,
        val samples: Int,
        val accuracy: Double,
        val report: String,
    )

    data class CalibrateResult(
        val params: Map<String, Double>,
        val trades: Int,
        val winRate: Double,
        val report: String,
    )

    val featureNames = listOf(
        "rsi14", "macd", "boll_pct", "ma_bias", "ema_bias",
        "ret1", "ret3", "ret5", "range", "vol_ratio",
    )

    private fun p(params: Map<String, Double>, key: String, def: Double) =
        params[key] ?: def

    fun signals(candles: List<Candle>, cfg: StrategyConfig): List<SignalMark> {
        if (candles.size < 40) return emptyList()
        // 默认偏「少而准」：阈值 0.64、冷却 12、边距 0.04
        val threshold = p(cfg.modelParams, "threshold", 0.64).coerceIn(0.55, 0.82)
        val cooldown = p(cfg.modelParams, "cooldown", 12.0).toInt().coerceIn(3, 80)
        val minEdge = p(cfg.modelParams, "minEdge", 0.04).coerceIn(0.0, 0.15)
        val confirmBars = p(cfg.modelParams, "confirmBars", 1.0).toInt().coerceIn(1, 5)
        val lookback = p(cfg.modelParams, "lookback", 5.0).toInt().coerceIn(2, 20)
        val hi = (threshold + minEdge).coerceAtMost(0.92)
        val lo = (1.0 - hi).coerceAtLeast(0.08)
        val weights = resolveWeights(cfg)
        val feats = buildFeatureMatrix(candles, lookback)
        val scores = DoubleArray(feats.size) { i ->
            val f = feats[i] ?: return@DoubleArray Double.NaN
            sigmoid(dot(weights, f))
        }
        val out = mutableListOf<SignalMark>()
        var lastSig = -999
        for (i in scores.indices) {
            val score = scores[i]
            if (score.isNaN()) continue
            if (i - lastSig < cooldown) continue
            // 连续确认：近 confirmBars 根均偏多/偏空
            if (confirmBars > 1) {
                var okB = true
                var okS = true
                for (k in 0 until confirmBars) {
                    val j = i - k
                    if (j < 0 || scores[j].isNaN()) {
                        okB = false; okS = false; break
                    }
                    if (scores[j] < 0.52) okB = false
                    if (scores[j] > 0.48) okS = false
                }
                when {
                    score >= hi && okB -> {
                        out.add(SignalMark(candles[i].openTime, "B", candles[i].close, tag = "model"))
                        lastSig = i
                    }
                    score <= lo && okS -> {
                        out.add(SignalMark(candles[i].openTime, "S", candles[i].close, tag = "model"))
                        lastSig = i
                    }
                }
            } else {
                when {
                    score >= hi -> {
                        out.add(SignalMark(candles[i].openTime, "B", candles[i].close, tag = "model"))
                        lastSig = i
                    }
                    score <= lo -> {
                        out.add(SignalMark(candles[i].openTime, "S", candles[i].close, tag = "model"))
                        lastSig = i
                    }
                }
            }
        }
        return out
    }

    /**
     * 在历史 K 上训练逻辑回归。
     * 标签优先「下一根涨跌」；训练后需再 calibrate 拉高阈值，避免信号过密。
     */
    fun train(
        candles: List<Candle>,
        params: Map<String, Double> = emptyMap(),
        epochs: Int = 40,
    ): TrainResult {
        if (candles.size < 80) {
            return TrainResult(
                emptyMap(), 0, 0.0,
                "K线不足(${candles.size})，至少需要约 80 根才能训练",
            )
        }
        val lookback = p(params, "lookback", 5.0).toInt().coerceIn(2, 20)
        val feats = buildFeatureMatrix(candles, lookback)
        val xs = mutableListOf<DoubleArray>()
        val ys = mutableListOf<Double>()
        for (i in 0 until candles.size - 1) {
            val f = feats[i] ?: continue
            // 事件合约简化：下一根收盘相对开盘方向（比单纯 close-close 更贴合约）
            val y = if (candles[i + 1].close > candles[i + 1].open) 1.0 else 0.0
            xs.add(f)
            ys.add(y)
        }
        if (xs.size < 40) {
            return TrainResult(emptyMap(), xs.size, 0.0, "有效样本过少(${xs.size})")
        }
        val dim = featureNames.size
        val w = DoubleArray(dim + 1)
        for (j in w.indices) w[j] = 0.01 * (j % 3 - 1)
        val lr = 0.06
        val n = xs.size
        val ep = epochs.coerceIn(15, 80)
        repeat(ep) {
            for (i in 0 until n) {
                val x = xs[i]
                val y = ys[i]
                val z = w[0] + (0 until dim).sumOf { j -> w[j + 1] * x[j] }
                val pred = sigmoid(z)
                val err = pred - y
                w[0] -= lr * err
                for (j in 0 until dim) w[j + 1] -= lr * err * x[j]
            }
        }
        var correct = 0
        for (i in 0 until n) {
            val pred = if (sigmoid(dot(w, xs[i])) >= 0.5) 1.0 else 0.0
            if (pred == ys[i]) correct++
        }
        val acc = correct.toDouble() / n
        val weightMap = linkedMapOf<String, Double>()
        weightMap["bias"] = w[0]
        featureNames.forEachIndexed { j, name -> weightMap[name] = w[j + 1] }
        val report =
            "模型=${ModelIds.LOGREG_V1} 样本=$n 准确率=${"%.1f".format(acc * 100)}% epochs=$ep\n" +
                "权重摘要: " + weightMap.entries.take(5).joinToString { "${it.key}=${"%.3f".format(it.value)}" } + "…"
        return TrainResult(weightMap, n, acc, report)
    }

    /**
     * 网格搜索 threshold/cooldown/minEdge，在「最少成交 + 高胜率」下选参。
     * 优先：胜率高且笔数不过少也不过多（避免每根 K 都信号）。
     */
    fun calibrate(
        candles: List<Candle>,
        cfg: StrategyConfig,
        minTrades: Int = 8,
        maxTradesRatio: Double = 0.12, // 信号数 / K线 上限，抑制过密
        targetWinRate: Double = 0.55,
    ): CalibrateResult {
        if (candles.size < 60 || cfg.modelWeights.isEmpty()) {
            return CalibrateResult(
                mapOf("threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04, "confirmBars" to 1.0, "lookback" to 5.0),
                0, 0.0, "无法校准：需已训练权重且足够K线",
            )
        }
        val lookback = p(cfg.modelParams, "lookback", 5.0)
        val maxTrades = (candles.size * maxTradesRatio).toInt().coerceIn(minTrades, 80)
        var bestParams = mapOf(
            "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
            "confirmBars" to 1.0, "lookback" to lookback,
        )
        var bestScore = -1e9
        var bestTrades = 0
        var bestWr = 0.0
        val thresholds = listOf(0.58, 0.62, 0.64, 0.66, 0.68, 0.70, 0.72)
        val cooldowns = listOf(8.0, 12.0, 16.0, 20.0, 24.0)
        val edges = listOf(0.02, 0.04, 0.06, 0.08)
        val confirms = listOf(1.0, 2.0)
        for (th in thresholds) {
            for (cd in cooldowns) {
                for (ed in edges) {
                    for (cf in confirms) {
                        val trial = cfg.copy(
                            modelParams = mapOf(
                                "threshold" to th,
                                "cooldown" to cd,
                                "minEdge" to ed,
                                "confirmBars" to cf,
                                "lookback" to lookback,
                            ),
                        )
                        val marks = signals(candles, trial)
                        if (marks.size < minTrades) continue
                        if (marks.size > maxTrades) continue
                        val (_, st) = EventSim.backtest(candles, marks, "SYM", "iv")
                        // 评分：达标胜率优先，其次胜率，惩罚过多信号
                        val density = marks.size.toDouble() / candles.size
                        val score = st.winRate * 100 +
                            (if (st.winRate >= targetWinRate) 30.0 else 0.0) +
                            minOf(st.trades, 40) * 0.15 -
                            density * 80.0
                        if (score > bestScore) {
                            bestScore = score
                            bestParams = trial.modelParams
                            bestTrades = st.trades
                            bestWr = st.winRate
                        }
                    }
                }
            }
        }
        val report =
            "校准完成 最优 threshold=${bestParams["threshold"]} cooldown=${bestParams["cooldown"]} " +
                "minEdge=${bestParams["minEdge"]} confirmBars=${bestParams["confirmBars"]} → " +
                "${bestTrades}笔 胜率${"%.1f".format(bestWr * 100)}%（目标≥少而准）"
        return CalibrateResult(bestParams, bestTrades, bestWr, report)
    }

    fun scoreAt(candles: List<Candle>, cfg: StrategyConfig, index: Int): Double? {
        if (index < 0 || index >= candles.size) return null
        val lookback = p(cfg.modelParams, "lookback", 5.0).toInt().coerceIn(2, 20)
        val feats = buildFeatureMatrix(candles, lookback)
        val f = feats.getOrNull(index) ?: return null
        return sigmoid(dot(resolveWeights(cfg), f))
    }

    private fun resolveWeights(cfg: StrategyConfig): DoubleArray {
        val dim = featureNames.size
        val w = DoubleArray(dim + 1)
        val src = cfg.modelWeights
        if (src.isNotEmpty() && src.containsKey("bias")) {
            w[0] = src["bias"] ?: 0.0
            featureNames.forEachIndexed { j, name -> w[j + 1] = src[name] ?: 0.0 }
            return w
        }
        // 未训练默认：偏保守，不易每根都触发
        w[0] = 0.0
        w[1] = -0.03
        w[2] = 0.04
        w[3] = -0.02
        return w
    }

    private fun buildFeatureMatrix(candles: List<Candle>, lookback: Int): Array<DoubleArray?> {
        val n = candles.size
        val closes = candles.map { it.close }
        val vols = candles.map { it.volume }
        val rsi = Indicators.rsi(closes, 14)
        val macd = Indicators.macdHist(closes)
        val boll = Indicators.bollPct(closes, 20)
        val ma = Indicators.sma(closes, 20)
        val ema = Indicators.ema(closes, 12)
        val volMa = Indicators.sma(vols, 20)
        val out = arrayOfNulls<DoubleArray>(n)
        for (i in 0 until n) {
            if (i < 25) continue
            val r = rsi.getOrNull(i) ?: continue
            val m = macd.getOrNull(i) ?: continue
            val b = boll.getOrNull(i) ?: continue
            val maV = ma.getOrNull(i) ?: continue
            val emaV = ema.getOrNull(i) ?: continue
            if (maV == 0.0) continue
            val ret1 = if (i >= 1) (closes[i] / closes[i - 1] - 1.0) * 100 else 0.0
            val ret3 = if (i >= 3) (closes[i] / closes[i - 3] - 1.0) * 100 else 0.0
            val ret5 = if (i >= lookback) (closes[i] / closes[i - lookback] - 1.0) * 100 else 0.0
            val range = if (candles[i].low != 0.0) {
                (candles[i].high - candles[i].low) / candles[i].close * 100
            } else 0.0
            val vr = volMa.getOrNull(i)?.takeIf { it > 0 }?.let { vols[i] / it } ?: 1.0
            out[i] = doubleArrayOf(
                (r - 50.0) / 50.0,
                m.coerceIn(-2.0, 2.0),
                (b - 50.0) / 50.0,
                (closes[i] / maV - 1.0) * 10,
                (closes[i] / emaV - 1.0) * 10,
                ret1.coerceIn(-5.0, 5.0),
                ret3.coerceIn(-8.0, 8.0),
                ret5.coerceIn(-10.0, 10.0),
                range.coerceIn(0.0, 10.0),
                (vr - 1.0).coerceIn(-2.0, 3.0),
            )
        }
        return out
    }

    private fun sigmoid(z: Double): Double {
        val x = z.coerceIn(-20.0, 20.0)
        return 1.0 / (1.0 + exp(-x))
    }

    private fun dot(w: DoubleArray, x: DoubleArray): Double {
        var s = w[0]
        val n = minOf(x.size, w.size - 1)
        for (i in 0 until n) s += w[i + 1] * x[i]
        return s
    }
}
