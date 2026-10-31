package com.puito.cryptoagent.domain

import com.puito.cryptoagent.data.AlgoIds
import com.puito.cryptoagent.data.Candle
import com.puito.cryptoagent.data.IndicatorType
import com.puito.cryptoagent.data.StrategyConfig
import com.puito.cryptoagent.data.StrategyKind

/**
 * 策略基准线：把当前策略用到的均线/布林/算法快慢线画到价格图上。
 * 振荡类（RSI/MACD 等）以水平阈值说明为主，不映射到价格轴。
 */
data class StrategyBaselineLine(
    val id: String,
    val label: String,
    val colorArgb: Long,
    /** 与 candles 等长；价格序列 */
    val values: List<Double?> = emptyList(),
    /** 价格水平线（如固定收盘价阈值） */
    val horizontal: Double? = null,
)

object StrategyBaselines {
    private val palette = longArrayOf(
        0xFF29B6F6, // 蓝
        0xFFFFA726, // 橙
        0xFFAB47BC, // 紫
        0xFF26A69A, // 青
        0xFFEF5350, // 红
        0xFF66BB6A, // 绿
        0xFFFFEE58, // 黄
        0xFF8D6E63, // 棕
    )

    fun build(candles: List<Candle>, cfg: StrategyConfig?): List<StrategyBaselineLine> {
        if (cfg == null || candles.isEmpty()) return emptyList()
        val closes = candles.map { it.close }
        val out = LinkedHashMap<String, StrategyBaselineLine>()
        var colorIdx = 0
        fun nextColor(): Long = palette[colorIdx++ % palette.size]

        when (cfg.kind) {
            StrategyKind.RULES -> {
                val rules = (cfg.buyRules + cfg.sellRules)
                for (r in rules) {
                    val period = r.period.coerceIn(2, 200)
                    when (r.indicator) {
                        IndicatorType.MA -> {
                            val id = "st_ma_$period"
                            if (id !in out) {
                                out[id] = StrategyBaselineLine(
                                    id, "策略MA$period", nextColor(),
                                    values = Indicators.sma(closes, period),
                                )
                            }
                        }
                        IndicatorType.EMA -> {
                            val id = "st_ema_$period"
                            if (id !in out) {
                                out[id] = StrategyBaselineLine(
                                    id, "策略EMA$period", nextColor(),
                                    values = Indicators.ema(closes, period),
                                )
                            }
                        }
                        IndicatorType.BOLL, IndicatorType.BOLL_PCT -> {
                            val id = "st_boll_$period"
                            if (id !in out) {
                                val (up, mid, low) = Indicators.boll(closes, period)
                                val c = nextColor()
                                out["${id}_u"] = StrategyBaselineLine("${id}_u", "策略BOLL上$period", c, values = up)
                                out["${id}_m"] = StrategyBaselineLine("${id}_m", "策略BOLL中$period", c, values = mid)
                                out["${id}_l"] = StrategyBaselineLine("${id}_l", "策略BOLL下$period", c, values = low)
                            }
                        }
                        IndicatorType.CLOSE -> {
                            val id = "st_close_${r.value}"
                            if (id !in out) {
                                out[id] = StrategyBaselineLine(
                                    id, "策略价${r.value}", nextColor(),
                                    horizontal = r.value,
                                )
                            }
                        }
                        IndicatorType.MA_BIAS -> {
                            val id = "st_ma_$period"
                            if (id !in out) {
                                out[id] = StrategyBaselineLine(
                                    id, "策略MA$period(偏离)", nextColor(),
                                    values = Indicators.sma(closes, period),
                                )
                            }
                        }
                        IndicatorType.EMA_BIAS -> {
                            val id = "st_ema_$period"
                            if (id !in out) {
                                out[id] = StrategyBaselineLine(
                                    id, "策略EMA$period(偏离)", nextColor(),
                                    values = Indicators.ema(closes, period),
                                )
                            }
                        }
                        else -> {
                            // RSI/MACD/KDJ：价格图不画振荡序列，仅在 label 层由 UI 提示
                        }
                    }
                }
            }
            StrategyKind.ALGO -> {
                when (cfg.algoId) {
                    AlgoIds.TREND_FOLLOW, AlgoIds.PEARSON_TRIPLE -> {
                        val fast = (cfg.algoParams["fast"] ?: cfg.algoParams["p1"] ?: 12.0).toInt().coerceIn(3, 80)
                        val slow = (cfg.algoParams["slow"] ?: cfg.algoParams["p2"] ?: 26.0).toInt().coerceIn(fast + 1, 120)
                        val p3 = (cfg.algoParams["p3"] ?: 0.0).toInt()
                        out["st_fast"] = StrategyBaselineLine(
                            "st_fast", "策略快线$fast", nextColor(),
                            values = Indicators.ema(closes, fast),
                        )
                        out["st_slow"] = StrategyBaselineLine(
                            "st_slow", "策略慢线$slow", nextColor(),
                            values = Indicators.ema(closes, slow),
                        )
                        if (cfg.algoId == AlgoIds.PEARSON_TRIPLE && p3 >= 3) {
                            out["st_p3"] = StrategyBaselineLine(
                                "st_p3", "策略线$p3", nextColor(),
                                values = Indicators.ema(closes, p3.coerceIn(3, 200)),
                            )
                        }
                    }
                    AlgoIds.BREAKOUT -> {
                        val lb = (cfg.algoParams["lookback"] ?: 20.0).toInt().coerceIn(5, 120)
                        val highs = candles.map { it.high }
                        val lows = candles.map { it.low }
                        out["st_brk_h"] = StrategyBaselineLine(
                            "st_brk_h", "突破上轨$lb", nextColor(),
                            values = rollingExtreme(highs, lb, high = true),
                        )
                        out["st_brk_l"] = StrategyBaselineLine(
                            "st_brk_l", "突破下轨$lb", nextColor(),
                            values = rollingExtreme(lows, lb, high = false),
                        )
                    }
                    else -> {}
                }
            }
            StrategyKind.MODEL -> {
                // 模型策略默认叠加一组参考均线，便于对照
                out["st_m_ema12"] = StrategyBaselineLine(
                    "st_m_ema12", "模型参考EMA12", nextColor(),
                    values = Indicators.ema(closes, 12),
                )
                out["st_m_ema26"] = StrategyBaselineLine(
                    "st_m_ema26", "模型参考EMA26", nextColor(),
                    values = Indicators.ema(closes, 26),
                )
            }
        }
        return out.values.toList()
    }

    /** 振荡类规则说明（画在图例，不进价格轴） */
    fun oscillatorHints(cfg: StrategyConfig?): List<String> {
        if (cfg == null || cfg.kind != StrategyKind.RULES) return emptyList()
        val hints = mutableListOf<String>()
        fun side(label: String, rules: List<com.puito.cryptoagent.data.Rule>) {
            for (r in rules) {
                when (r.indicator) {
                    IndicatorType.RSI, IndicatorType.MACD, IndicatorType.KDJ_J, IndicatorType.BOLL_PCT -> {
                        hints.add("$label ${r.indicator.label}${r.period} ${r.op.name} ${r.value}")
                    }
                    else -> {}
                }
            }
        }
        side("买", cfg.buyRules)
        side("卖", cfg.sellRules)
        return hints.distinct()
    }

    private fun rollingExtreme(xs: List<Double>, period: Int, high: Boolean): List<Double?> {
        val out = MutableList<Double?>(xs.size) { null }
        if (xs.size < period) return out
        for (i in period - 1 until xs.size) {
            val win = xs.subList(i - period + 1, i + 1)
            out[i] = if (high) win.maxOrNull() else win.minOrNull()
        }
        return out
    }
}
