package com.puito.cryptoagent.data

enum class Interval(val code: String, val timeUnit: Int) {
    M1("1m", 1), M5("5m", 5), M10("10m", 10), M30("30m", 30), H1("1h", 60);
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

enum class IndicatorType(val label: String) {
    RSI("RSI"), MACD("MACD柱"), KDJ_J("KDJ-J"), CLOSE("收盘价"),
    MA("MA"), EMA("EMA"), BOLL("布林带")
}

enum class CompareOp(val label: String) {
    GT("大于"), GTE("大于等于"), LT("小于"), LTE("小于等于")
}

data class Rule(
    val indicator: IndicatorType = IndicatorType.RSI,
    val op: CompareOp = CompareOp.LT,
    val value: Double = 30.0,
    val period: Int = 14, // MA/EMA/RSI 周期，可手机编辑
)

data class StrategyConfig(
    val id: String,
    val title: String,
    val enabled: Boolean = false,
    val buyRules: List<Rule> = listOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0, 14)),
    val sellRules: List<Rule> = listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14)),
)

data class SignalMark(val openTime: Long, val side: String, val price: Double)

/** AI 对单条信号的评估（可与真实下单解耦） */
data class AiEvalResult(
    val winRatePct: Double? = null,
    val summary: String = "",
    val passThreshold: Boolean? = null,
    val thresholdPct: Double = 55.0,
    val error: String? = null,
)

data class SignalNotifyPayload(
    val mark: SignalMark,
    val interval: String,
    val intervalWinRatePct: Double,
    val intervalTrades: Int = 0,
    val ai: AiEvalResult? = null,
)

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

/** 图表叠加指标（可运行时开关，无需重新打包） */
data class ChartIndicatorPref(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val period: Int = 20,
    val colorArgb: Long = 0xFF42A5F5,
)

/** Chat 临时绘图：斐波那契等 */
data class ChartOverlay(
    val id: String,
    val type: String, // fib | hline | note
    val values: List<Double> = emptyList(),
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class HibtSettings(
    val apiBase: String = "https://api-ws.taichuwuji.com",
    val authToken: String = "",
    val xAuthToken: String = "",
    val vParam: String = "",
    val bgetKey: String = "HotsCoinLimboA@1",
    val bgetId: String = "",
    val langCode: String = "zh_CN",
    val clientType: String = "web", // web | h5
    val autoTrade: Boolean = false,
    val aiEvaluate: Boolean = false,
    val aiMinWinRate: Double = 55.0, // 百分比
    val defaultAmount: Double = 3.0,
    val dryRun: Boolean = true, // 默认模拟，避免误真实下单
)

data class AppSettings(
    val binanceBaseUrl: String = "https://data-api.binance.vision",
    val symbol: String = "BTCUSDT",
    val interval: String = "10m",
    val klineLimit: Int = 1000, // 足够历史，指标/回测更稳
    val strategyRunning: Boolean = true,
    val backgroundEnabled: Boolean = true,
    val notifyVibrate: Boolean = true, // 信号通知默认震动
    val llmBaseUrl: String = "https://api.openai.com/v1",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    /** AI 评估 / 对话 HTTP 超时（秒）。原 poll 内硬编码 8s 易超时，默认 60 */
    val llmTimeoutSec: Int = 60,
    val onboardingDone: Boolean = false,
    val chartIndicators: List<ChartIndicatorPref> = listOf(
        ChartIndicatorPref("ma7", "MA7", false, 7, 0xFF42A5F5),
        ChartIndicatorPref("ma25", "MA25", false, 25, 0xFFFFA726),
        ChartIndicatorPref("ma99", "MA99", false, 99, 0xFFAB47BC),
        ChartIndicatorPref("ema12", "EMA12", false, 12, 0xFF26C6DA),
        ChartIndicatorPref("boll20", "BOLL20", false, 20, 0xFF78909C),
        ChartIndicatorPref("rsi14", "RSI14", false, 14, 0xFFEC407A),
    ),
    val hibt: HibtSettings = HibtSettings(),
)
