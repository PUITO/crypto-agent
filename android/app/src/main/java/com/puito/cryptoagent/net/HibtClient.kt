package com.puito.cryptoagent.net

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.puito.cryptoagent.data.HibtSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HiBT 事件合约 Web 接口客户端（非官方）。
 * 账户：优先 /option/option-account/info + /uc/member/my-info
 * 持仓：仅统计 /event|option/…/list 中「未平仓」条数，避免把币种列表当持仓。
 * 下单：对齐公开 curl（amount/direction/symbol/timeUnit/langCode + ?v=）
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
            .removePrefix("Bearer ").removePrefix("bearer ").trim()

    private fun Request.Builder.applyHibtHeaders(cfg: HibtSettings): Request.Builder {
        val ct = cfg.clientType.ifBlank { "web" }
        val isH5 = ct.equals("h5", true)
        header("accept", "application/json, text/plain, */*")
        header("content-type", "application/x-www-form-urlencoded")
        header("client-type", if (isH5) "h5" else "web")
        header("platform", if (isH5) "h5" else "PC")
        header("hc-platform", if (isH5) "h5" else "web")
        header("future_source", "1")
        header("origin", "https://hibt.com")
        header("referer", "https://hibt.com/")
        header(
            "user-agent",
            if (isH5) "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
            else "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
        )
        val lang = cfg.langCode.ifBlank { "zh_CN" }
        header("hc-language", lang)
        header("lang", lang)
        if (cfg.bgetKey.isNotBlank()) {
            header("bget-key", cfg.bgetKey)
            header("BGET_KEY", cfg.bgetKey)
        }
        if (cfg.bgetId.isNotBlank()) {
            header("bget-id", cfg.bgetId)
            header("BGET_ID", cfg.bgetId)
        }
        val tok = tokenOf(cfg)
        if (tok.isNotBlank()) {
            // 官方抓包通常 Authorization 与 x-auth-token 同值
            header("x-auth-token", tok)
            header("Authorization", tok)
            if (cfg.authToken.isNotBlank() && cfg.authToken != tok) {
                header("Authorization", cfg.authToken.trim())
            }
            if (cfg.xAuthToken.isNotBlank() && cfg.xAuthToken != tok) {
                header("x-auth-token", cfg.xAuthToken.trim())
            }
        }
        return this
    }

    private fun candidateBases(cfg: HibtSettings): List<String> {
        val primary = cfg.apiBase.trim().trimEnd('/')
        return listOf(
            primary,
            "https://api.hibt0.com",
            "https://api-ws.taichuwuji.com",
            "https://api.hibt.com",
        ).map { it.trimEnd('/') }.filter { it.startsWith("http") }.distinct()
    }

    private fun withV(url: String, cfg: HibtSettings): String {
        val v = cfg.vParam.trim()
        if (v.isEmpty()) return url
        return if (url.contains("?")) "$url&v=${java.net.URLEncoder.encode(v, "UTF-8")}"
        else "$url?v=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }

    private fun bizOk(code: Int, body: String): Boolean {
        if (code !in 200..299) return false
        if (body.isBlank()) return false
        return try {
            val o = JsonParser.parseString(body).asJsonObject
            val c = when {
                o.has("code") && o.get("code").isJsonPrimitive -> {
                    val p = o.get("code")
                    if (p.asJsonPrimitive.isNumber) p.asInt else p.asString.toIntOrNull()
                }
                else -> 0
            }
            // 0 / 200 常见成功；401/403 失败
            c == null || c == 0 || c == 200
        } catch (_: Exception) {
            !body.contains("\"code\":401") && !body.contains("未登录")
        }
    }

    private fun get(url: String, cfg: HibtSettings): Pair<Int, String> {
        val req = Request.Builder().url(url).get().applyHibtHeaders(cfg).build()
        client.newCall(req).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    /** 仅从账户类接口抽余额，避免把 list 里的 amount 当成余额 */
    private fun extractBalanceStrict(body: String): String? {
        return try {
            val root = JsonParser.parseString(body)
            val keys = listOf(
                "availableBalance", "usableBalance", "canUseAmount", "optionBalance",
                "eventBalance", "walletBalance", "available", "balance", "usdtBalance",
                "canUse", "money", "equity",
            )
            fun fromObj(o: JsonObject): String? {
                for (k in keys) {
                    if (!o.has(k) || o.get(k).isJsonNull) continue
                    val v = o.get(k)
                    if (v.isJsonPrimitive) {
                        val s = v.asString.trim()
                        if (s.isNotEmpty() && s != "null" && s != "0" && s != "0.0") return s
                        if (s == "0" || s == "0.0" || s == "0.00") return s // 真实 0 也显示
                    }
                }
                return null
            }
            fun walk(el: JsonElement?, depth: Int, underData: Boolean): String? {
                if (el == null || depth > 5) return null
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    // 优先 data / result / account
                    for (w in listOf("data", "result", "account", "info")) {
                        if (o.has(w)) walk(o.get(w), depth + 1, true)?.let { return it }
                    }
                    if (underData || depth <= 2) {
                        fromObj(o)?.let { return it }
                    }
                    for ((k, v) in o.entrySet()) {
                        if (k in listOf("list", "records", "rows", "orders")) continue
                        walk(v, depth + 1, underData)?.let { return it }
                    }
                }
                return null
            }
            walk(root, 0, false)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 只统计「持仓列表」接口里的未平仓笔数。
     * 不要用 option-coin/list 等全表长度。
     */
    private fun extractOpenPositionCount(body: String): Int? {
        return try {
            val root = JsonParser.parseString(body)
            fun isOpenItem(o: JsonObject): Boolean {
                // 已平仓常见字段
                if (o.has("closeTime") && !o.get("closeTime").isJsonNull) {
                    val ct = o.get("closeTime")
                    if (ct.isJsonPrimitive) {
                        val s = ct.asString
                        if (s.isNotBlank() && s != "0" && s != "null") return false
                    }
                }
                if (o.has("status") && o.get("status").isJsonPrimitive) {
                    val st = o.get("status")
                    val n = if (st.asJsonPrimitive.isNumber) st.asInt else st.asString.toIntOrNull()
                    // 0 进行中 / 1 完成 等（不同站可能相反，优先看 isOpen/open）
                    if (o.has("isOpen") && o.get("isOpen").isJsonPrimitive) {
                        val io = o.get("isOpen")
                        if (io.asJsonPrimitive.isBoolean) return io.asBoolean
                        if (io.asString == "1" || io.asString.equals("true", true)) return true
                        if (io.asString == "0" || io.asString.equals("false", true)) return false
                    }
                    if (n != null) {
                        // 多数：0 持仓中，1/2/3 已结束
                        if (n == 0) return true
                        if (n in listOf(1, 2, 3, 4, 5)) return false
                    }
                }
                // 有 direction + amount 且无 close 倾向视为持仓
                return o.has("direction") || o.has("timeUnit") || o.has("openPrice") || o.has("symbol")
            }
            fun countList(arr: JsonArray): Int {
                var n = 0
                for (el in arr) {
                    if (el.isJsonObject && isOpenItem(el.asJsonObject)) n++
                    else if (!el.isJsonObject) n++ // 无法判断则计 1
                }
                return n
            }
            fun findList(el: JsonElement?, depth: Int): Int? {
                if (el == null || depth > 6) return null
                if (el.isJsonArray) return countList(el.asJsonArray)
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    for (k in listOf("list", "records", "rows", "openList", "positions", "data", "result")) {
                        if (!o.has(k) || o.get(k).isJsonNull) continue
                        val v = o.get(k)
                        if (v.isJsonArray) return countList(v.asJsonArray)
                        if (v.isJsonObject) findList(v, depth + 1)?.let { return it }
                    }
                }
                return null
            }
            findList(root, 0)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun testConnectivity(cfg: HibtSettings): AccountSnapshot = withContext(Dispatchers.IO) {
        if (tokenOf(cfg).isBlank()) {
            return@withContext AccountSnapshot(false, "请填写 x-auth-token（或 Authorization）")
        }
        val log = StringBuilder()
        var balance: String? = null
        var posCount: Int? = null
        var anyAuth = false

        val accountPaths = listOf(
            "/option/option-account/info",
            "/uc/member/my-info",
            "/option/option-order/history-summary",
            "/event/event-order/history-summary",
        )
        val listPaths = listOf(
            "/event/event-order/list",
            "/option/option-order/list",
        )

        for (base in candidateBases(cfg)) {
            for (path in accountPaths) {
                val url = withV(base + path, cfg)
                try {
                    val (code, body) = get(url, cfg)
                    log.appendLine("$code $path @ $base body=${body.take(80).replace("\n", " ")}")
                    if (!bizOk(code, body)) continue
                    anyAuth = true
                    if (balance.isNullOrBlank()) {
                        extractBalanceStrict(body)?.let { balance = it }
                    }
                } catch (e: Exception) {
                    log.appendLine("ERR $path ${e.message?.take(40)}")
                }
            }
            for (path in listPaths) {
                val url = withV(base + path, cfg)
                try {
                    val (code, body) = get(url, cfg)
                    log.appendLine("$code $path @ $base body=${body.take(80).replace("\n", " ")}")
                    if (!bizOk(code, body)) continue
                    anyAuth = true
                    val n = extractOpenPositionCount(body)
                    if (n != null) {
                        // 取该路径的真实笔数；多路径时优先 event-order
                        if (posCount == null || path.contains("event-order")) posCount = n
                    }
                } catch (e: Exception) {
                    log.appendLine("ERR $path ${e.message?.take(40)}")
                }
            }
            if (anyAuth && balance != null && posCount != null) break
        }

        if (!anyAuth) {
            return@withContext AccountSnapshot(
                false,
                "鉴权未通过或接口未命中。请确认 token / API Base（常用 https://api.hibt0.com）与可选 v 参数。",
                raw = log.toString().take(1200),
            )
        }

        val posText = when (posCount) {
            null -> "未解析到持仓列表（可能暂无持仓或路径变更）"
            else -> "未平仓 ${posCount} 笔"
        }
        AccountSnapshot(
            ok = true,
            message = "在线查询完成" +
                (if (balance == null) " · 余额字段未识别" else "") +
                (if (posCount == null) " · 持仓列表未识别" else ""),
            balance = balance ?: "—",
            positions = posText,
            raw = log.toString().take(1500),
        )
    }

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
            symbol.contains("_") -> symbol.lowercase()
            else -> symbol.lowercase().replace("usdt", "_usdt")
        }
        val dir = if (directionUp) 1 else 0
        // 周期分钟：5/10/30/60；官网亦有 15，原样透传
        val unit = when (timeUnit) {
            1 -> 5 // 不应出现，兜底
            else -> timeUnit.coerceIn(1, 120)
        }
        val amountStr = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

        if (cfg.dryRun || !cfg.autoTrade) {
            return@withContext OrderResult(
                true,
                "DRY-RUN: symbol=$sym direction=$dir amount=$amountStr timeUnit=$unit（未真实提交）",
                dryRun = true,
            )
        }
        if (tokenOf(cfg).isBlank()) {
            return@withContext OrderResult(false, "缺少 token", dryRun = false)
        }

        val form = FormBody.Builder()
            .add("amount", amountStr)
            .add("direction", dir.toString())
            .add("symbol", sym)
            .add("timeUnit", unit.toString())
            .add("langCode", cfg.langCode.ifBlank { "zh_CN" })
            .build()

        val placePaths = listOf(
            "/option/option-order/place",
            "/event/event-order/place",
        )
        val tries = StringBuilder()
        var lastRaw: String? = null
        for (base in candidateBases(cfg)) {
            for (path in placePaths) {
                // 有 v / 无 v 各试一次
                val urls = buildList {
                    add(withV(base + path, cfg))
                    if (cfg.vParam.isNotBlank()) add(base + path)
                }.distinct()
                for (url in urls) {
                    try {
                        val req = Request.Builder().url(url).post(form)
                            .applyHibtHeaders(cfg)
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            lastRaw = body.take(400)
                            tries.appendLine("${resp.code} $path ${body.take(100).replace("\n", " ")}")
                            if (resp.isSuccessful && bizOk(resp.code, body)) {
                                // 业务错误码
                                val msg = try {
                                    val o = JsonParser.parseString(body).asJsonObject
                                    val m = when {
                                        o.has("msg") -> o.get("msg").asString
                                        o.has("message") -> o.get("message").asString
                                        else -> body.take(80)
                                    }
                                    val c = if (o.has("code")) o.get("code").toString() else ""
                                    "下单响应 code=$c msg=$m"
                                } catch (_: Exception) {
                                    body.take(120)
                                }
                                if (body.contains("参数") || body.contains("param", true)) {
                                    return@withContext OrderResult(false, msg, false, lastRaw)
                                }
                                return@withContext OrderResult(true, msg, false, lastRaw)
                            }
                        }
                    } catch (e: Exception) {
                        tries.appendLine("ERR ${e.message?.take(40)}")
                    }
                }
            }
        }
        OrderResult(
            false,
            "下单失败（参数/鉴权）。检查：symbol=btc_usdt、timeUnit 分钟、amount≥最小额、v 参数。\n" +
                tries.toString().take(400),
            false,
            lastRaw,
        )
    }
}
