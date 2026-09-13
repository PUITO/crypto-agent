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
 * HiBT 事件合约 Web 接口（非官方）。
 * 日志结论：list / history-summary 需 POST；option-account/info 在部分站 404；
 * 必须优先使用配置里的 apiBase（书签解析值），再回退。
 */
class HibtClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
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
            header("x-auth-token", cfg.xAuthToken.ifBlank { tok }.trim().removePrefix("Bearer ").trim())
            header("Authorization", cfg.authToken.ifBlank { tok }.trim())
        }
        return this
    }

    /** 解析到的 apiBase 永远排第一，且完整跑完再考虑回退 */
    private fun candidateBases(cfg: HibtSettings): List<String> {
        val primary = cfg.apiBase.trim().trimEnd('/')
        val fallbacks = listOf(
            "https://api.hibt0.com",
            "https://api-ws.taichuwuji.com",
            "https://api.hibt.com",
        )
        return (listOf(primary) + fallbacks)
            .map { it.trimEnd('/') }
            .filter { it.startsWith("http") }
            .distinct()
    }

    private fun withV(url: String, cfg: HibtSettings): String {
        val v = cfg.vParam.trim()
        if (v.isEmpty()) return url
        val enc = java.net.URLEncoder.encode(v, "UTF-8")
        return if (url.contains("?")) "$url&v=$enc" else "$url?v=$enc"
    }

    private fun bizOk(code: Int, body: String): Boolean {
        if (code !in 200..299) return false
        if (body.isBlank()) return false
        if (body.contains("\"status\":404") || body.contains("Not Found")) return false
        return try {
            val o = JsonParser.parseString(body).asJsonObject
            val c = when {
                o.has("code") && o.get("code").isJsonPrimitive -> {
                    val p = o.get("code")
                    if (p.asJsonPrimitive.isNumber) p.asInt else p.asString.toIntOrNull()
                }
                else -> 0
            }
            // 500 且 method not supported 视为失败
            if (body.contains("not supported", true)) return false
            c == null || c == 0 || c == 200
        } catch (_: Exception) {
            !body.contains("\"code\":401") && !body.contains("未登录")
        }
    }

    private fun emptyForm(): FormBody = FormBody.Builder().build()

    private fun pageForm(): FormBody = FormBody.Builder()
        .add("pageNo", "1")
        .add("pageNum", "1")
        .add("page", "1")
        .add("pageSize", "50")
        .add("size", "50")
        .add("limit", "50")
        .add("status", "0")
        .build()

    private fun request(
        method: String,
        url: String,
        cfg: HibtSettings,
        body: FormBody? = null,
    ): Pair<Int, String> {
        val b = Request.Builder().url(url).applyHibtHeaders(cfg)
        when (method) {
            "POST" -> b.post(body ?: emptyForm()).header("content-type", "application/x-www-form-urlencoded")
            else -> b.get()
        }
        client.newCall(b.build()).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    private val balanceKeys = listOf(
        "availableBalance", "usableBalance", "canUseAmount", "optionBalance",
        "eventBalance", "walletBalance", "available", "balance", "usdtBalance",
        "canUse", "money", "equity", "amount", "balanceStr", "availBalance",
        "optionAvailable", "eventAvailable", "spotBalance", "totalBalance",
    )

    private fun extractBalanceStrict(body: String): String? {
        return try {
            fun fromObj(o: JsonObject): String? {
                for (k in balanceKeys) {
                    if (!o.has(k) || o.get(k).isJsonNull) continue
                    val v = o.get(k)
                    if (v.isJsonPrimitive) {
                        val s = v.asString.trim()
                        if (s.isNotEmpty() && s != "null") return s.take(32)
                    }
                }
                return null
            }
            fun walk(el: JsonElement?, depth: Int): String? {
                if (el == null || depth > 6) return null
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    for (w in listOf("data", "result", "account", "info", "wallet", "asset")) {
                        if (o.has(w)) walk(o.get(w), depth + 1)?.let { return it }
                    }
                    fromObj(o)?.let { return it }
                    for ((k, v) in o.entrySet()) {
                        if (k in listOf("list", "records", "rows", "orders")) continue
                        walk(v, depth + 1)?.let { return it }
                    }
                } else if (el.isJsonArray) {
                    for (item in el.asJsonArray) walk(item, depth + 1)?.let { return it }
                }
                return null
            }
            walk(JsonParser.parseString(body), 0)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractOpenPositionCount(body: String): Int? {
        return try {
            val root = JsonParser.parseString(body)
            fun isOpenItem(o: JsonObject): Boolean {
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
                    if (n != null) {
                        if (n == 0) return true
                        if (n in 1..9) return false
                    }
                }
                return o.has("direction") || o.has("timeUnit") || o.has("openPrice") ||
                    o.has("symbol") || o.has("amount")
            }
            fun countList(arr: JsonArray): Int {
                var n = 0
                for (el in arr) {
                    if (el.isJsonObject) {
                        if (isOpenItem(el.asJsonObject)) n++
                    }
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
            return@withContext AccountSnapshot(false, "请填写 x-auth-token")
        }
        val log = StringBuilder()
        log.appendLine("使用 API Base 优先: ${cfg.apiBase.trim()}")
        var balance: String? = null
        var posCount: Int? = null
        var anyAuth = false
        var usedBase: String? = null

        // GET 账户类
        val getAccountPaths = listOf(
            "/uc/member/my-info",
            "/option/option-account/info",
            "/uc/asset/wallet",
            "/uc/finance/wallet",
        )
        // POST 账户类（部分站 GET 404）
        val postAccountPaths = listOf(
            "/option/option-account/info",
            "/option/option-account/get",
            "/option/wallet/info",
            "/event/event-account/info",
        )
        // 持仓列表：日志明确要求 POST
        val postListPaths = listOf(
            "/event/event-order/list",
            "/option/option-order/list",
            "/event/event-order/history-summary",
            "/option/option-order/history-summary",
        )

        fun tryBase(base: String): Boolean {
            var hit = false
            for (path in getAccountPaths) {
                val url = withV(base + path, cfg)
                try {
                    val (code, body) = request("GET", url, cfg)
                    log.appendLine("GET $code $path @ $base ${body.take(70).replace("\n", " ")}")
                    if (!bizOk(code, body)) continue
                    hit = true
                    if (balance.isNullOrBlank()) extractBalanceStrict(body)?.let { balance = it }
                } catch (e: Exception) {
                    log.appendLine("GET ERR $path @ $base ${e.message?.take(48)}")
                }
            }
            for (path in postAccountPaths) {
                val url = withV(base + path, cfg)
                try {
                    val (code, body) = request("POST", url, cfg, emptyForm())
                    log.appendLine("POST $code $path @ $base ${body.take(70).replace("\n", " ")}")
                    if (!bizOk(code, body)) continue
                    hit = true
                    if (balance.isNullOrBlank()) extractBalanceStrict(body)?.let { balance = it }
                } catch (e: Exception) {
                    log.appendLine("POST ERR $path @ $base ${e.message?.take(48)}")
                }
            }
            for (path in postListPaths) {
                val url = withV(base + path, cfg)
                for (form in listOf(pageForm(), emptyForm())) {
                    try {
                        val (code, body) = request("POST", url, cfg, form)
                        log.appendLine("POST $code $path @ $base ${body.take(70).replace("\n", " ")}")
                        if (!bizOk(code, body)) continue
                        hit = true
                        // summary 可能带余额
                        if (balance.isNullOrBlank()) extractBalanceStrict(body)?.let { balance = it }
                        if (path.endsWith("/list")) {
                            extractOpenPositionCount(body)?.let { n ->
                                if (posCount == null || path.contains("event-order")) posCount = n
                            }
                        }
                        break
                    } catch (e: Exception) {
                        log.appendLine("POST ERR $path @ $base ${e.message?.take(48)}")
                    }
                }
            }
            return hit
        }

        val bases = candidateBases(cfg)
        // 1) 只用解析/配置的主域名
        val primary = bases.first()
        if (tryBase(primary)) {
            anyAuth = true
            usedBase = primary
        }
        // 2) 主域名未鉴权成功再回退
        if (!anyAuth) {
            for (base in bases.drop(1)) {
                if (tryBase(base)) {
                    anyAuth = true
                    usedBase = base
                    break
                }
            }
        } else if (balance == null || posCount == null) {
            // 主域名鉴权了但缺字段，再用回退补余额/持仓
            for (base in bases.drop(1)) {
                tryBase(base)
                if (balance != null && posCount != null) break
            }
        }

        if (!anyAuth) {
            return@withContext AccountSnapshot(
                false,
                "鉴权失败。请确认 API Base 与书签一致（当前优先 ${cfg.apiBase}），token 有效。",
                raw = log.toString().take(1800),
            )
        }

        val posText = when (posCount) {
            null -> "未解析到（已改 POST 拉列表）"
            else -> "未平仓 ${posCount} 笔"
        }
        AccountSnapshot(
            ok = true,
            message = "在线查询完成 · base=${usedBase ?: primary}" +
                (if (balance == null) " · 余额未识别" else "") +
                (if (posCount == null) " · 持仓未识别" else ""),
            balance = balance ?: "—",
            positions = posText,
            raw = log.toString().take(2000),
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
        // 事件合约分钟：5/10/30/60（与行情页选中周期一致）
        val unit = when (timeUnit) {
            1 -> 5
            in listOf(5, 10, 15, 30, 60) -> timeUnit
            else -> timeUnit.coerceIn(5, 60)
        }
        val amountStr =
            if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

        if (cfg.dryRun || !cfg.autoTrade) {
            return@withContext OrderResult(
                true,
                "DRY-RUN: symbol=$sym direction=$dir amount=$amountStr timeUnit=${unit}m" +
                    "（行情周期映射，未真实提交；关 Dry-Run 且开自动化才实盘）",
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
        // 下单同样：优先配置 apiBase
        for (base in candidateBases(cfg)) {
            for (path in placePaths) {
                val urls = buildList {
                    add(withV(base + path, cfg))
                    if (cfg.vParam.isNotBlank()) add(base + path)
                }.distinct()
                for (url in urls) {
                    try {
                        val req = Request.Builder().url(url).post(form)
                            .applyHibtHeaders(cfg)
                            .header("content-type", "application/x-www-form-urlencoded")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            lastRaw = body.take(400)
                            tries.appendLine("${resp.code} $path @ $base timeUnit=$unit ${body.take(100).replace("\n", " ")}")
                            if (resp.isSuccessful && bizOk(resp.code, body) &&
                                !body.contains("参数") && !body.contains("param", true)
                            ) {
                                return@withContext OrderResult(
                                    true,
                                    "下单成功 timeUnit=${unit}m · ${body.take(120)}",
                                    false,
                                    lastRaw,
                                )
                            }
                            if (body.contains("参数") || body.contains("param", true)) {
                                // 继续试其他 base/path
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
            "下单失败 timeUnit=${unit}m symbol=$sym。\n" + tries.toString().take(500),
            false,
            lastRaw,
        )
    }
}
