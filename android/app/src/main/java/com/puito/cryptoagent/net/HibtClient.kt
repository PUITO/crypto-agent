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

    /** 账户/余额优先，再持仓列表，最后登录探测 */
    private val probePaths = listOf(
        "/option/option-account/info",
        "/uc/member/my-info",
        "/event/event-order/list",
        "/option/option-order/list",
        "/option/option-order/history-summary",
        "/event/event-order/history-summary",
        "/option/option-coin/list",
        "/uc/check/login",
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
        var anyOk = false
        var bestRaw: String? = null
        var bestBal: String? = null
        var bestPos: String? = null
        var hitHint: String? = null

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
                            val tag = "${resp.code} ${if (bearer) "B" else "R"} $path"
                            attempts.add(tag)
                            if (!looksAuthOk(resp.code, body)) return@use
                            anyOk = true
                            if (hitHint == null) hitHint = "$path @ $base"
                            if (bestRaw == null) bestRaw = body.take(600)

                            // 余额：多接口聚合，取第一个有效数字/字符串
                            if (bestBal.isNullOrBlank()) {
                                extractBalance(body)?.let { bestBal = it }
                            }
                            // 持仓：列表条数优先
                            if (bestPos.isNullOrBlank()) {
                                extractPositionSummary(body)?.let { bestPos = it }
                            }
                        }
                    } catch (e: Exception) {
                        attempts.add("ERR $path · ${e.message?.take(32)}")
                    }
                }
            }
            // 同一 base 已拿到余额+持仓可提前结束，减少请求
            if (anyOk && !bestBal.isNullOrBlank() && !bestPos.isNullOrBlank()) break
        }

        if (!anyOk) {
            return@withContext AccountSnapshot(
                ok = false,
                message = "Token 已填，但账户接口未命中。末次: " +
                    attempts.takeLast(5).joinToString(" | ").ifBlank { "无" },
                raw = attempts.takeLast(10).joinToString("\n"),
            )
        }

        AccountSnapshot(
            ok = true,
            message = "连通成功 · ${hitHint ?: "ok"}" +
                (if (bestBal.isNullOrBlank()) " · 余额字段未解析到" else "") +
                (if (bestPos.isNullOrBlank()) " · 持仓字段未解析到" else ""),
            balance = bestBal ?: "—",
            positions = bestPos ?: "—",
            raw = bestRaw,
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

    private val balanceKeys = listOf(
        "availableBalance", "usableBalance", "canUseAmount", "walletBalance",
        "available", "balance", "equity", "amount", "money", "usdtBalance",
        "optionBalance", "eventBalance", "accountBalance", "canUse",
    )

    private fun extractBalance(json: String): String? {
        return try {
            fun walk(el: com.google.gson.JsonElement?, depth: Int): String? {
                if (el == null || depth > 6) return null
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    for (k in balanceKeys) {
                        if (!o.has(k) || o.get(k).isJsonNull) continue
                        val v = o.get(k)
                        if (v.isJsonPrimitive) {
                            val s = v.asString.trim()
                            if (s.isNotEmpty() && s != "null") return s.take(32)
                        }
                    }
                    // data / result 包一层
                    for (wrap in listOf("data", "result", "info", "account")) {
                        if (o.has(wrap)) walk(o.get(wrap), depth + 1)?.let { return it }
                    }
                    for ((_, v) in o.entrySet()) {
                        walk(v, depth + 1)?.let { return it }
                    }
                } else if (el.isJsonArray) {
                    for (item in el.asJsonArray) {
                        walk(item, depth + 1)?.let { return it }
                    }
                }
                return null
            }
            walk(JsonParser.parseString(json), 0)
        } catch (_: Exception) {
            null
        }
    }

    /** 事件/期权持仓：优先数组长度，否则摘要字符串 */
    private fun extractPositionSummary(json: String): String? {
        return try {
            val root = JsonParser.parseString(json)
            fun arrayCount(el: com.google.gson.JsonElement?, depth: Int): Int? {
                if (el == null || depth > 6) return null
                if (el.isJsonArray) return el.asJsonArray.size()
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    for (k in listOf("list", "records", "rows", "openList", "positions", "data", "result")) {
                        if (!o.has(k) || o.get(k).isJsonNull) continue
                        val v = o.get(k)
                        if (v.isJsonArray) return v.asJsonArray.size()
                        if (v.isJsonObject) {
                            // data: { list: [] }
                            arrayCount(v, depth + 1)?.let { return it }
                        }
                    }
                    for ((_, v) in o.entrySet()) {
                        arrayCount(v, depth + 1)?.let { return it }
                    }
                }
                return null
            }
            val n = arrayCount(root, 0)
            if (n != null) return "持仓 ${n} 笔"
            // total / count 字段
            fun walkNum(el: com.google.gson.JsonElement?, depth: Int): String? {
                if (el == null || depth > 5) return null
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    for (k in listOf("total", "count", "openCount", "positionCount", "size")) {
                        if (o.has(k) && o.get(k).isJsonPrimitive) {
                            return "持仓 ${o.get(k).asString} 笔"
                        }
                    }
                    for ((_, v) in o.entrySet()) walkNum(v, depth + 1)?.let { return it }
                }
                return null
            }
            walkNum(root, 0)
        } catch (_: Exception) {
            null
        }
    }
}
