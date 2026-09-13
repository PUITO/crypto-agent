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
 * HiBT / 同源站事件合约客户端（Web 接口形态，非官方 API）。
 * 默认 dryRun=true。连通性会轮询多 base + 多路径（对齐书签抓包常见接口）。
 */
class HibtClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
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

    private fun tokenOf(cfg: HibtSettings): String =
        cfg.xAuthToken.ifBlank { cfg.authToken }.trim()

    private fun Request.Builder.applyHibtHeaders(
        cfg: HibtSettings,
        bearerStyle: Boolean = false,
    ): Request.Builder {
        header("accept", "application/json, text/plain, */*")
        val ct = cfg.clientType.ifBlank { "web" }
        val isH5 = ct.equals("h5", true)
        header("client-type", if (isH5) "h5" else "web")
        header("platform", if (isH5) "h5" else "PC")
        header("hc-platform", if (isH5) "h5" else "web")
        if (cfg.langCode.isNotBlank()) {
            header("hc-language", cfg.langCode)
            header("lang", cfg.langCode)
            header("language", cfg.langCode)
        }
        // 键名多种兼容
        if (cfg.bgetKey.isNotBlank()) {
            header("bget-key", cfg.bgetKey)
            header("bget_key", cfg.bgetKey)
            header("BGET_KEY", cfg.bgetKey)
        }
        if (cfg.bgetId.isNotBlank()) {
            header("bget-id", cfg.bgetId)
            header("bget_id", cfg.bgetId)
            header("BGET_ID", cfg.bgetId)
        }
        val tok = tokenOf(cfg)
        if (tok.isNotBlank()) {
            val auth = when {
                bearerStyle && !tok.startsWith("Bearer", ignoreCase = true) -> "Bearer $tok"
                !bearerStyle && tok.startsWith("Bearer", ignoreCase = true) ->
                    tok.removePrefix("Bearer").trim().removePrefix("bearer").trim()
                else -> tok
            }
            header("x-auth-token", auth.removePrefix("Bearer ").trim())
            header("Authorization", if (bearerStyle) {
                if (auth.startsWith("Bearer", true)) auth else "Bearer $auth"
            } else {
                cfg.authToken.ifBlank { auth }
            })
        }
        header("origin", "https://hibt.com")
        header("referer", "https://hibt.com/")
        header("user-agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
        return this
    }

    private fun candidateBases(cfg: HibtSettings): List<String> {
        val primary = cfg.apiBase.trim().trimEnd('/')
        return listOf(
            primary,
            "https://api-ws.taichuwuji.com",
            "https://api.hibt0.com",
            "https://api.hibt.com",
        ).map { it.trimEnd('/') }.filter { it.startsWith("http") }.distinct()
    }

    /** 书签 TR 对齐的常见已登录接口 */
    private val probePaths = listOf(
        "/uc/check/login",
        "/uc/member/my-info",
        "/option/option-order/list",
        "/event/event-order/list",
        "/option/option-coin/list",
        "/option/option-account/info",
        "/option/option-order/history-summary",
        "/event/event-order/history-summary",
    )

    private fun looksAuthOk(code: Int, body: String): Boolean {
        if (code !in 200..299) return false
        val b = body.lowercase()
        if (b.contains("token") && (b.contains("invalid") || b.contains("expire") || b.contains("未登录") || b.contains("unauthorized"))) {
            // 业务码里仍可能 HTTP 200
            if (b.contains("\"code\":401") || b.contains("\"code\":403") || b.contains("\"code\":-1")) return false
        }
        if (b.contains("\"code\":401") || b.contains("\"code\":403")) return false
        return body.isNotBlank()
    }

    suspend fun testConnectivity(cfg: HibtSettings): AccountSnapshot = withContext(Dispatchers.IO) {
        if (tokenOf(cfg).isBlank()) {
            return@withContext AccountSnapshot(
                false,
                "请填写 x-auth-token（书签抓取后粘贴）",
            )
        }
        val attempts = mutableListOf<String>()
        var bestRaw: String? = null
        var bestBal: String? = null
        var bestPos: String? = null
        var hitUrl: String? = null

        for (base in candidateBases(cfg)) {
            for (path in probePaths) {
                for (bearer in listOf(false, true)) {
                    val url = base + path + if (cfg.vParam.isNotBlank()) "?v=${cfg.vParam}" else ""
                    try {
                        val req = Request.Builder().url(url).get()
                            .applyHibtHeaders(cfg, bearerStyle = bearer)
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            val tag = "${resp.code} ${if (bearer) "Bearer" else "raw"} $url"
                            attempts.add(tag + if (body.isNotEmpty()) " body=${body.take(60).replace("\n", " ")}" else "")
                            if (looksAuthOk(resp.code, body)) {
                                hitUrl = url
                                bestRaw = body.take(800)
                                bestBal = extractField(
                                    body,
                                    listOf(
                                        "balance", "available", "equity", "amount", "availableBalance",
                                        "walletBalance", "canUseAmount", "usableBalance", "money",
                                    ),
                                )
                                bestPos = extractField(
                                    body,
                                    listOf("position", "positions", "list", "records", "openList"),
                                )
                                return@withContext AccountSnapshot(
                                    ok = true,
                                    message = "连通成功 · $path @ $base" +
                                        if (bearer) " (Bearer)" else "",
                                    balance = bestBal,
                                    positions = bestPos,
                                    raw = bestRaw,
                                )
                            }
                        }
                    } catch (e: Exception) {
                        attempts.add("ERR $url · ${e.message?.take(40)}")
                    }
                }
            }
        }

        AccountSnapshot(
            ok = false,
            message = "Token 已填，但账户接口未命中。请确认书签在已登录页触发过 API，" +
                "且 API Base 与抓包 host 一致。末次尝试: " +
                attempts.takeLast(4).joinToString(" | ").ifBlank { "无" },
            balance = null,
            positions = null,
            raw = attempts.takeLast(8).joinToString("\n"),
        )
    }

    /**
     * direction: 1 涨 / 0 跌
     * timeUnit: 分钟 5/10/30/60
     */
    suspend fun placeEventOrder(
        cfg: HibtSettings,
        symbol: String,
        directionUp: Boolean,
        amount: Double,
        timeUnit: Int,
    ): OrderResult = withContext(Dispatchers.IO) {
        val sym = when {
            symbol.equals("BTCUSDT", true) -> "btc_usdt"
            symbol.equals("ETHUSDT", true) -> "eth_usdt"
            else -> symbol.lowercase().let {
                if (it.contains("_")) it else it.replace("usdt", "_usdt")
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
        if (tokenOf(cfg).isBlank()) {
            return@withContext OrderResult(false, "缺少 token，无法真实下单", dryRun = false)
        }
        val form = FormBody.Builder()
            .add("amount", amount.toString())
            .add("direction", dir.toString())
            .add("symbol", sym)
            .add("timeUnit", timeUnit.toString())
            .add("langCode", cfg.langCode.ifBlank { "zh_CN" })
            .build()

        val placePaths = listOf(
            "/option/option-order/place",
            "/event/event-order/place",
            "/option-order/place",
            "/event-order/place",
        )
        var lastMsg = "下单失败"
        var lastRaw: String? = null
        for (base in candidateBases(cfg)) {
            for (path in placePaths) {
                for (bearer in listOf(false, true)) {
                    val url = base + path + if (cfg.vParam.isNotBlank()) "?v=${cfg.vParam}" else ""
                    try {
                        val req = Request.Builder().url(url).post(form)
                            .applyHibtHeaders(cfg, bearerStyle = bearer)
                            .header("content-type", "application/x-www-form-urlencoded")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            lastRaw = body.take(500)
                            lastMsg = "HTTP ${resp.code} $path @ $base: ${body.take(120)}"
                            if (resp.isSuccessful && looksAuthOk(resp.code, body)) {
                                return@withContext OrderResult(true, lastMsg, false, lastRaw)
                            }
                        }
                    } catch (e: Exception) {
                        lastMsg = e.message ?: "order error"
                    }
                }
            }
        }
        OrderResult(false, lastMsg, false, lastRaw)
    }

    private fun extractField(json: String, keys: List<String>): String? {
        return try {
            fun walk(el: com.google.gson.JsonElement?, depth: Int): String? {
                if (el == null || depth > 4) return null
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    for (k in keys) {
                        if (o.has(k) && !o.get(k).isJsonNull) {
                            val v = o.get(k)
                            if (v.isJsonPrimitive) return v.asString.take(80)
                            return v.toString().take(80)
                        }
                    }
                    for ((_, v) in o.entrySet()) {
                        walk(v, depth + 1)?.let { return it }
                    }
                } else if (el.isJsonArray && el.asJsonArray.size() > 0) {
                    return walk(el.asJsonArray[0], depth + 1)
                }
                return null
            }
            walk(JsonParser.parseString(json), 0)
        } catch (_: Exception) {
            null
        }
    }
}
