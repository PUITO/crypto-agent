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
    RSI("RSI"),
    MACD("MACD柱"),
    KDJ_J("KDJ-J"),
    CLOSE("收盘价"),
    MA("MA均线值"),
    EMA("EMA均线值"),
    /** 收盘价在布林带中的位置 0=下轨 50=中轨 100=上轨（震荡市推荐） */
    BOLL_PCT("布林位置%"),
    /** 兼容旧策略：按布林中轨绝对值（不推荐） */
    BOLL("布林中轨"),
    /** (收盘/MA-1)*100，如 -1 表示低于MA约1% */
    MA_BIAS("MA偏离%"),
    /** (收盘/EMA-1)*100 */
    EMA_BIAS("EMA偏离%"),
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

data class StrategyOptimizeResult(
    val strategy: StrategyConfig,
    val report: String,
    val winRate: Double,
    val trades: Int,
    val created: Boolean,
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
    /** 1m_confirm | ht_native */
    val source: String = "1m_confirm",
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
    val apiBase: String = "https://api.hibt0.com",
    val authToken: String = "",
    val xAuthToken: String = "",
    val vParam: String = "",
    /** 自动 v：下单/持仓=Base64(毫秒时间戳)；余额=明文毫秒时间戳 */
    val vAutoTimestamp: Boolean = true,
    val bgetKey: String = "HotsCoinLimboA@1",
    val bgetId: String = "",
    val langCode: String = "zh_CN",
    val clientType: String = "web", // web | h5
    /** 下单 Origin/Referer，与官网一致可降低 405/风控 */
    val origin: String = "https://m.hibt.com",
    val referer: String = "https://m.hibt.com/",
    /** WebView 起始/线路地址，用于控制访问线路 */
    val webHomeUrl: String = "https://m.hibt.com/",
    /** WebView Cookie 串，原生下单与手动一致 */
    val cookieHeader: String = "",
    val autoTrade: Boolean = false,
    val aiEvaluate: Boolean = false,
    val aiMinWinRate: Double = 55.0, // 百分比
    val defaultAmount: Double = 3.0,
    val dryRun: Boolean = true, // 默认模拟，避免误真实下单
    /** WebView 注入下单等待脚本回调超时（秒），默认 45，建议 20–120 */
    val placeTimeoutSec: Int = 45,
    /**
     * 自动下单多选周期（与行情页全局 interval 解耦）。
     * 合法：5m / 10m / 30m / 1h
     */
    val autoIntervals: List<String> = listOf("5m", "10m", "30m", "1h"),
    /** 大单边行情防追单：同向连续强势 K 线时跳过同向自动下单 */
    val antiChaseEnabled: Boolean = true,
    /** 检测窗口 K 线根数（1m 主源） */
    val antiChaseBars: Int = 6,
)

data class AppSettings(
    val binanceBaseUrl: String = "https://data-api.binance.vision",
    val symbol: String = "BTCUSDT",
    val interval: String = "10m",
    val klineLimit: Int = 1000, // 足够历史，指标/回测更稳
    val strategyRunning: Boolean = true,
    /**
     * 信号模式一：1m 策略触发 + 高周期软确认（低延迟，过滤噪声）。
     * 与 signalModeHtNative 可同时开，信号更多。
     */
    val signalMode1mConfirm: Boolean = true,
    /**
     * 信号模式二：在 5m/10m/30m/1h 上直接跑策略（周期原生，信号更多）。
     */
    val signalModeHtNative: Boolean = true,
    val backgroundEnabled: Boolean = true,
    val notifyVibrate: Boolean = true, // 信号通知默认震动
    val llmBaseUrl: String = "https://api.openai.com/v1",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    /** AI 评估 / 对话 HTTP 超时（秒）。原 poll 内硬编码 8s 易超时，默认 60 */
    val llmTimeoutSec: Int = 60,
    /** 评估/短答最大输出 token，越小越省（信号评估建议 32～128） */
    val llmMaxTokens: Int = 512,
    val llmTemperature: Float = 0.3f,
    /** DeepSeek 等：关闭 thinking 可大幅减少输出 token */
    val llmThinkingEnabled: Boolean = false,
    /** AI 评估只取最近 N 根 K 线摘要 */
    val llmEvalMaxBars: Int = 40,
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
