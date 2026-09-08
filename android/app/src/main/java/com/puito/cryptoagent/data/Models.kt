package com.puito.cryptoagent.data

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

data class ChatMessage(
    val role: String, // user / assistant
    val content: String,
    val time: Long = System.currentTimeMillis(),
)

data class ServiceHealth(
    val name: String,
    val ok: Boolean,
    val detail: String = "",
)

data class AppPrefs(
    val gatewayBaseUrl: String = "http://10.0.2.2:8000", // emulator -> host
    val symbol: String = "BTCUSDT",
    val interval: String = "15m",
    val limit: Int = 200,
)
