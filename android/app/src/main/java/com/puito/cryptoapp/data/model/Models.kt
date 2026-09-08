package com.puito.cryptoapp.data.model

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long = openTime,
)

enum class Interval(val code: String, val millis: Long) {
    M5("5m", 5 * 60_000L),
    M10("10m", 10 * 60_000L),
    M30("30m", 30 * 60_000L),
    H1("1h", 60 * 60_000L);

    companion object {
        fun fromCode(code: String): Interval =
            entries.find { it.code == code } ?: M10
    }
}

enum class CompareOp(val label: String) {
    GT("大于"),
    GTE("大于等于"),
    LT("小于"),
    LTE("小于等于");
}

enum class IndicatorType(val label: String) {
    RSI("RSI"),
    MACD("MACD柱"),
    KDJ_J("KDJ-J"),
    CLOSE("收盘价");
}

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

data class SimTrade(
    val id: String,
    val symbol: String,
    val interval: String,
    val side: String, // B / S
    val entryTime: Long,
    val entryPrice: Double,
    val exitTime: Long,
    val exitPrice: Double,
    val pnlPct: Double,
    val win: Boolean,
)

data class BacktestStats(
    val signals: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRate: Double = 0.0,
    val totalReturnPct: Double = 0.0,
)

data class AppSettings(
    val binanceBaseUrl: String = "https://data-api.binance.vision",
    val defaultLimit: Int = 1000,
    val defaultSymbol: String = "BTCUSDT",
    val defaultInterval: String = "10m",
    val onboardingDone: Boolean = false,
)

data class SignalMark(
    val openTime: Long,
    val side: String, // B / S
    val price: Double,
)
