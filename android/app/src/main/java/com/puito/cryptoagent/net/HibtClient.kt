package com.puito.cryptoagent.net

import com.google.gson.JsonParser
import com.puito.cryptoagent.data.HibtSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HiBT 事件合约客户端（基于公开逆向文档的 Web 接口形态）。
 * 默认 dryRun=true，仅本地校验配置；真实下单需用户自行填写 token，风险自担。
 * HiBT 官方未提供官方量化 API，本模块仅作研究与连通性测试。
 */
class HibtClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    data class AccountSnapshot(
        val ok: Boolean,
        val message: String,
        val balance: String? = null,
        val positions: String? = null,
        val raw: String? = null,
    )

    data class OrderResult(
        val ok: Boolean,
        val message: String,
        val dryRun: Boolean,
        val raw: String? = null,
    )

    suspend fun testConnectivity(cfg: HibtSettings): AccountSnapshot = withContext(Dispatchers.IO) {
        if (cfg.authToken.isBlank() && cfg.xAuthToken.isBlank()) {
            return@withContext AccountSnapshot(
                false,
                "请填写 Authorization / x-auth-token（浏览器登录 HiBT 后从开发者工具复制）",
            )
        }
        // 尝试拉取账户类接口；失败时仍返回 token 是否齐全
        val paths = listOf(
            "/option/option-account/info",
            "/option/option-order/position",
            "/user/account/info",
        )
        var lastErr = ""
        for (path in paths) {
            try {
                val url = cfg.apiBase.trimEnd('/') + path +
                    if (cfg.vParam.isNotBlank()) "?v=${cfg.vParam}" else ""
                val req = Request.Builder().url(url).get().apply {
                    header("accept", "application/json")
                    header("client-type", "web")
                    header("platform", "PC")
                    header("hc-platform", "web")
                    if (cfg.authToken.isNotBlank()) header("Authorization", cfg.authToken)
                    if (cfg.xAuthToken.isNotBlank()) header("x-auth-token", cfg.xAuthToken)
                }.build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val bal = extractField(body, listOf("balance", "available", "equity", "amount"))
                        return@withContext AccountSnapshot(
                            true,
                            "连通成功 HTTP ${resp.code}",
                            balance = bal ?: "见原始响应",
                            positions = extractField(body, listOf("position", "positions", "list")),
                            raw = body.take(500),
                        )
                    }
                    lastErr = "HTTP ${resp.code}: ${body.take(120)}"
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "error"
            }
        }
        AccountSnapshot(
            ok = cfg.authToken.isNotBlank() || cfg.xAuthToken.isNotBlank(),
            message = "Token 已填写，但账户接口未命中（$lastErr）。可继续 dry-run 测试下单参数。",
            balance = null,
            positions = null,
        )
    }

    /**
     * direction: 1 涨 / 0 跌（与逆向示例一致）
     * timeUnit: 分钟 5/10/30/60
     */
    suspend fun placeEventOrder(
        cfg: HibtSettings,
        symbol: String,
        directionUp: Boolean,
        amount: Double,
        timeUnit: Int,
    ): OrderResult = withContext(Dispatchers.IO) {
        val sym = symbol.lowercase().replace("usdt", "_usdt").let {
            if (it.contains("_")) it else "${symbol.lowercase()}_usdt"
        }.replace("btc_usdt", "btc_usdt").replace("eth_usdt", "eth_usdt")
            .let { s ->
                when {
                    symbol.equals("BTCUSDT", true) -> "btc_usdt"
                    symbol.equals("ETHUSDT", true) -> "eth_usdt"
                    else -> s
                }
            }
        val dir = if (directionUp) 1 else 0
        if (cfg.dryRun || !cfg.autoTrade) {
            return@withContext OrderResult(
                true,
                "DRY-RUN 下单: $sym dir=$dir amount=$amount timeUnit=${timeUnit}m",
                dryRun = true,
            )
        }
        if (cfg.authToken.isBlank() && cfg.xAuthToken.isBlank()) {
            return@withContext OrderResult(false, "缺少 token，无法真实下单", dryRun = false)
        }
        val form = FormBody.Builder()
            .add("amount", amount.toString())
            .add("direction", dir.toString())
            .add("symbol", sym)
            .add("timeUnit", timeUnit.toString())
            .add("langCode", "zh_CN")
            .build()
        val url = cfg.apiBase.trimEnd('/') + "/option/option-order/place" +
            if (cfg.vParam.isNotBlank()) "?v=${cfg.vParam}" else ""
        val req = Request.Builder().url(url).post(form).apply {
            header("accept", "application/json, text/plain, */*")
            header("content-type", "application/x-www-form-urlencoded")
            header("client-type", "web")
            header("platform", "PC")
            header("hc-platform", "web")
            header("origin", "https://hibt.com")
            header("referer", "https://hibt.com/")
            if (cfg.authToken.isNotBlank()) header("Authorization", cfg.authToken)
            if (cfg.xAuthToken.isNotBlank()) header("x-auth-token", cfg.xAuthToken)
        }.build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                OrderResult(resp.isSuccessful, "HTTP ${resp.code}: ${body.take(200)}", false, body.take(500))
            }
        } catch (e: Exception) {
            OrderResult(false, e.message ?: "order error", false)
        }
    }

    private fun extractField(json: String, keys: List<String>): String? {
        return try {
            val el = JsonParser.parseString(json)
            if (!el.isJsonObject) return null
            val o = el.asJsonObject
            for (k in keys) {
                if (o.has(k)) return o.get(k).toString().take(80)
                // shallow data nest
                if (o.has("data") && o.get("data").isJsonObject) {
                    val d = o.getAsJsonObject("data")
                    if (d.has(k)) return d.get(k).toString().take(80)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
