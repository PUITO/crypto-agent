package com.puito.cryptoagent.domain

import com.puito.cryptoagent.data.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 离线「小型金融模型」层：经典量化因子（动量 / 均值回归 / 波动突破 / 趋势质量）。
 * 纯本地计算、无网络、无第三方二进制，成本接近零；与分类器融合做低成本量化。
 *
 * 输出 score ∈ (0,1)：>0.5 偏多，<0.5 偏空。
 */
object FinancialFactorEngine {

    const val MOMENTUM_TS = "MOMENTUM_TS"
    const val MEAN_REVERSION = "MEAN_REVERSION"
    const val VOL_BREAKOUT = "VOL_BREAKOUT"
    const val TREND_QUALITY = "TREND_QUALITY"

    val all = listOf(MOMENTUM_TS, MEAN_REVERSION, VOL_BREAKOUT, TREND_QUALITY)

    fun label(id: String) = when (id) {
        MOMENTUM_TS -> "时序动量"
        MEAN_REVERSION -> "均值回归"
        VOL_BREAKOUT -> "波动突破"
        TREND_QUALITY -> "趋势质量"
        else -> id
    }

    /**
     * @return 与 candles 等长，无效处为 NaN
     */
    fun scores(
        candles: List<Candle>,
        financeId: String,
        params: Map<String, Double> = emptyMap(),
    ): DoubleArray {
        if (candles.size < 40) return DoubleArray(candles.size) { Double.NaN }
        return when (financeId) {
            MEAN_REVERSION -> meanReversion(candles, params)
            VOL_BREAKOUT -> volBreakout(candles, params)
            TREND_QUALITY -> trendQuality(candles, params)
            else -> momentumTs(candles, params)
        }
    }

    /** 时序动量：多周期收益加权 + 符号一致加成 */
    private fun momentumTs(candles: List<Candle>, p: Map<String, Double>): DoubleArray {
        val c = candles.map { it.close }
        val n = c.size
        val out = DoubleArray(n) { Double.NaN }
        val w1 = p["w1"] ?: 0.2
        val w3 = p["w3"] ?: 0.35
        val w10 = p["w10"] ?: 0.45
        for (i in 15 until n) {
            fun ret(k: Int): Double {
                val j = i - k
                if (j < 0 || c[j] == 0.0) return 0.0
                return (c[i] / c[j] - 1.0) * 100.0
            }
            val r1 = ret(1)
            val r3 = ret(3)
            val r10 = ret(10)
            val raw = w1 * r1 + w3 * r3 + w10 * r10
            // 同向加成
            val same = if ((r1 > 0 && r3 > 0 && r10 > 0) || (r1 < 0 && r3 < 0 && r10 < 0)) 1.15 else 1.0
            val z = (raw * same).coerceIn(-8.0, 8.0)
            out[i] = sigmoid(z * 0.35)
        }
        return out
    }

    /** 均值回归：价格相对均线的 z-score 反向 */
    private fun meanReversion(candles: List<Candle>, p: Map<String, Double>): DoubleArray {
        val c = candles.map { it.close }
        val n = c.size
        val out = DoubleArray(n) { Double.NaN }
        val win = (p["window"] ?: 20.0).toInt().coerceIn(10, 60)
        for (i in win until n) {
            var sum = 0.0
            for (j in (i - win + 1)..i) sum += c[j]
            val mean = sum / win
            var varSum = 0.0
            for (j in (i - win + 1)..i) {
                val d = c[j] - mean
                varSum += d * d
            }
            val std = sqrt(varSum / win).coerceAtLeast(1e-9)
            val z = (c[i] - mean) / std
            // 高 z → 看空回归，低 z → 看多
            out[i] = sigmoid(-z * 0.85)
        }
        return out
    }

    /** 波动突破：近端振幅扩张 + 方向 */
    private fun volBreakout(candles: List<Candle>, p: Map<String, Double>): DoubleArray {
        val n = candles.size
        val out = DoubleArray(n) { Double.NaN }
        val look = (p["lookback"] ?: 14.0).toInt().coerceIn(8, 40)
        for (i in look until n) {
            var avgRange = 0.0
            for (j in (i - look) until i) {
                val bar = candles[j]
                avgRange += if (bar.close != 0.0) (bar.high - bar.low) / bar.close else 0.0
            }
            avgRange /= look
            val cur = candles[i]
            val range = if (cur.close != 0.0) (cur.high - cur.low) / cur.close else 0.0
            val expand = if (avgRange > 1e-9) range / avgRange else 1.0
            val dir = if (i > 0 && candles[i - 1].close != 0.0) {
                (cur.close / candles[i - 1].close - 1.0) * 100.0
            } else 0.0
            // 扩张且有方向才给分
            val strength = ((expand - 1.0) * 2.0).coerceIn(0.0, 3.0)
            val z = dir.coerceIn(-5.0, 5.0) * (0.4 + 0.2 * strength)
            out[i] = sigmoid(z * 0.4)
        }
        return out
    }

    /** 趋势质量：简化 DI+/DI- 与均线斜率 */
    private fun trendQuality(candles: List<Candle>, p: Map<String, Double>): DoubleArray {
        val n = candles.size
        val out = DoubleArray(n) { Double.NaN }
        val period = (p["period"] ?: 14.0).toInt().coerceIn(8, 30)
        for (i in (period + 2) until n) {
            var up = 0.0
            var down = 0.0
            for (j in (i - period + 1)..i) {
                val upMove = candles[j].high - candles[j - 1].high
                val downMove = candles[j - 1].low - candles[j].low
                if (upMove > downMove && upMove > 0) up += upMove
                if (downMove > upMove && downMove > 0) down += downMove
            }
            val trSum = (up + down).coerceAtLeast(1e-9)
            val diPlus = up / trSum
            val diMinus = down / trSum
            val slope = if (candles[i - period].close != 0.0) {
                (candles[i].close / candles[i - period].close - 1.0) * 100.0
            } else 0.0
            val bias = (diPlus - diMinus) * 2.0 + slope * 0.08
            out[i] = sigmoid(bias)
        }
        return out
    }

    private fun sigmoid(z: Double): Double {
        val x = z.coerceIn(-20.0, 20.0)
        return 1.0 / (1.0 + kotlin.math.exp(-x))
    }
}
