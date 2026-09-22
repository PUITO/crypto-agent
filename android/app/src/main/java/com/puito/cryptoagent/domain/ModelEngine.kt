package com.puito.cryptoagent.domain

import com.puito.cryptoagent.data.Candle
import com.puito.cryptoagent.data.ModelIds
import com.puito.cryptoagent.data.SignalMark
import com.puito.cryptoagent.data.StrategyConfig
import kotlin.math.exp

/**
 * 可训练小模型引擎（本地、无第三方依赖）。
 * LOGREG_V1：逻辑回归，特征复用 Indicators；标签=下一根收涨(1)/收跌(0)。
 * 信号：score≥threshold → B，score≤1-threshold → S。
 */
object ModelEngine {

    data class TrainResult(
        val weights: Map<String, Double>,
        val samples: Int,
        val accuracy: Double,
        val report: String,
    )

    /** 特征名顺序固定，训练/推理必须一致 */
    val featureNames = listOf(
        "rsi14", "macd", "boll_pct", "ma_bias", "ema_bias",
        "ret1", "ret3", "ret5", "range", "vol_ratio",
    )

    private fun p(params: Map<String, Double>, key: String, def: Double) =
        params[key] ?: def

    fun signals(candles: List<Candle>, cfg: StrategyConfig): List<SignalMark> {
        if (candles.size < 40) return emptyList()
        val threshold = p(cfg.modelParams, "threshold", 0.55).coerceIn(0.51, 0.85)
        val cooldown = p(cfg.modelParams, "cooldown", 3.0).toInt().coerceIn(0, 30)
        val lookback = p(cfg.modelParams, "lookback", 5.0).toInt().coerceIn(2, 20)
        val weights = resolveWeights(cfg)
        val feats = buildFeatureMatrix(candles, lookback)
        val out = mutableListOf<SignalMark>()
        var lastSig = -999
        for (i in feats.indices) {
            val f = feats[i] ?: continue
            if (i - lastSig < cooldown) continue
            val score = sigmoid(dot(weights, f))
            when {
                score >= threshold -> {
                    out.add(SignalMark(candles[i].openTime, "B", candles[i].close, tag = "model"))
                    lastSig = i
                }
                score <= 1.0 - threshold -> {
                    out.add(SignalMark(candles[i].openTime, "S", candles[i].close, tag = "model"))
                    lastSig = i
                }
            }
        }
        return out
    }

    /**
     * 在历史 K 线上训练逻辑回归（SGD）。
     * 标签：下一根 close > 当前 close → 1 否则 0（事件合约同向简化）。
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
            val y = if (candles[i + 1].close > candles[i].close) 1.0 else 0.0
            xs.add(f)
            ys.add(y)
        }
        if (xs.size < 40) {
            return TrainResult(emptyMap(), xs.size, 0.0, "有效样本过少(${xs.size})")
        }
        val dim = featureNames.size
        val w = DoubleArray(dim + 1) // [bias, f0..]
        // 轻微初始化
        for (j in w.indices) w[j] = 0.01 * (j % 3 - 1)
        val lr = 0.08
        val n = xs.size
        repeat(epochs.coerceIn(10, 80)) {
            for (i in 0 until n) {
                val x = xs[i]
                val y = ys[i]
                val z = w[0] + (0 until dim).sumOf { j -> w[j + 1] * x[j] }
                val pred = sigmoid(z)
                val err = pred - y
                w[0] -= lr * err
                for (j in 0 until dim) {
                    w[j + 1] -= lr * err * x[j]
                }
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
            "模型=${ModelIds.LOGREG_V1} 样本=$n 准确率=${"%.1f".format(acc * 100)}% " +
                "epochs=$epochs lookback=$lookback\n" +
                "权重: " + weightMap.entries.take(6).joinToString { "${it.key}=${"%.3f".format(it.value)}" } + "…"
        return TrainResult(weightMap, n, acc, report)
    }

    fun scoreAt(
        candles: List<Candle>,
        cfg: StrategyConfig,
        index: Int,
    ): Double? {
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
        // 未训练默认：偏中性，轻微跟随 RSI 超卖/超买
        w[0] = 0.0
        w[1] = -0.02 // rsi 高 → 空
        w[2] = 0.05  // macd
        w[3] = -0.015 // boll_pct
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
        val out = arrayOfNulls<DoubleArray>(n)
        val volMa = Indicators.sma(vols, 20)
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
                (r - 50.0) / 50.0,           // 归一 RSI
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
