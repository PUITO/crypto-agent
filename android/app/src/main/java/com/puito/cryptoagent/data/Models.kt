package com.puito.cryptoagent.data

enum class Interval(val code: String) {
    M5("5m"), M10("10m"), M30("30m"), H1("1h");
    companion object {
        fun from(code: String) = entries.find { it.code == code } ?: M10
    }
}

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

enum class IndicatorType(val label: String) { RSI("RSI"), MACD("MACD柱"), KDJ_J("KDJ-J"), CLOSE("收盘价") }
enum class CompareOp(val label: String) { GT("大于"), GTE("大于等于"), LT("小于"), LTE("小于等于") }

data class Rule(
    val indicator: IndicatorType = IndicatorType.RSI,
    val op: CompareOp = CompareOp.LT,
    val value: Double = 30.0,
)

data class StrategyConfig(
    val id: String,
    val title: String,
    val enabled: Boolean = false,
    val buyRules: List<Rule> = listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0)),
    val sellRules: List<Rule> = listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0)),
)

data class SignalMark(val openTime: Long, val side: String, val price: Double)

data class SimTrade(
    val id: String,
    val symbol: String,
    val interval: String,
    val side: String,
    val entryTime: Long,
    val entryPrice: Double,
    val exitTime: Long,
    val exitPrice: Double,
    val pnlPct: Double,
    val win: Boolean,
)

data class Stats(
    val trades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRate: Double = 0.0,
    val totalReturnPct: Double = 0.0,
)

/** 本地完整运行配置：行情 + 策略 + LLM，不依赖自建服务器 */
data class AppSettings(
    val binanceBaseUrl: String = "https://data-api.binance.vision",
    val symbol: String = "BTCUSDT",
    val interval: String = "10m",
    val klineLimit: Int = 500, // 仅保存有限 K 线，减少存储
    val strategyRunning: Boolean = true, // 默认开启监控
    val backgroundEnabled: Boolean = true, // 默认后台
    // OpenAI 兼容 LLM（Chat 需配置）
    val llmBaseUrl: String = "https://api.openai.com/v1",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    val onboardingDone: Boolean = false,
)
