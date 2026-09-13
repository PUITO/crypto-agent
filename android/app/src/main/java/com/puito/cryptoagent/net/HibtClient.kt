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

    /**
     * 事件合约下单参数规范（对齐公开 Web 抓包 / 学习向逆向说明）：
     *
     * POST {apiBase}/option/option-order/place?v={v}
     * Content-Type: application/x-www-form-urlencoded
     * Headers: Authorization, x-auth-token, client-type, platform, hc-platform,
     *          future_source=1, hc-language, origin, referer
     * Body:
     *   amount   — 下单金额（USDT 字符串，官网说明最小约 2）
     *   direction— 1 涨 / 0 跌
     *   symbol   — btc_usdt | eth_usdt
     *   timeUnit — 合约时长（分钟）：5 | 10 | 15 | 30 | 60
     *   langCode — 如 zh_CN
     *
     * timeUnit 必须与行情页选中周期一致，禁止用错误周期下单。
     */
    data class PlaceSpec(
        val apiBase: String,
        val path: String,
        val symbol: String,
        val direction: Int,
        val amount: String,
        val timeUnit: Int,
        val langCode: String,
        val hasV: Boolean,
        val preview: String,
    )

    fun buildPlaceSpec(
        cfg: HibtSettings,
        symbol: String,
        directionUp: Boolean,
        amount: Double,
        timeUnit: Int,
    ): PlaceSpec {
        val sym = when {
            symbol.equals("BTCUSDT", true) || symbol.equals("btc_usdt", true) -> "btc_usdt"
            symbol.equals("ETHUSDT", true) || symbol.equals("eth_usdt", true) -> "eth_usdt"
            symbol.contains("_") -> symbol.lowercase()
            else -> symbol.lowercase().replace("usdt", "_usdt")
        }
        require(sym in setOf("btc_usdt", "eth_usdt")) {
            "symbol 仅支持 btc_usdt / eth_usdt，当前=$sym"
        }
        val dir = if (directionUp) 1 else 0
        // 仅允许官网/抓包常见档位；1m 等非法值拒绝映射到错误合约
        val allowed = setOf(5, 10, 15, 30, 60)
        val unit = when {
            timeUnit in allowed -> timeUnit
            timeUnit == 1 -> 5 // 仅信号源 1m → 默认 5m，调用方应尽量直接传 5/10/30/60
            else -> error("非法 timeUnit=$timeUnit，仅允许 $allowed")
        }
        require(amount >= 2.0) {
            "amount 过小($amount)，官网说明最小约 2 USDT，请调高默认下单金额"
        }
        require(amount <= 100_000.0) { "amount 异常过大: $amount" }
        val amountStr =
            if (kotlin.math.abs(amount - amount.toLong()) < 1e-9) amount.toLong().toString()
            else amount.toString()
        val base = cfg.apiBase.trim().trimEnd('/').ifBlank { "https://api.hibt0.com" }
        val lang = cfg.langCode.ifBlank { "zh_CN" }
        val preview =
            "POST $base/option/option-order/place" +
                (if (cfg.vParam.isNotBlank()) "?v=***" else "（无 v，部分环境会拒单）") +
                "\nform: amount=$amountStr&direction=$dir&symbol=$sym&timeUnit=$unit&langCode=$lang"
        return PlaceSpec(
            apiBase = base,
            path = "/option/option-order/place",
            symbol = sym,
            direction = dir,
            amount = amountStr,
            timeUnit = unit,
            langCode = lang,
            hasV = cfg.vParam.isNotBlank(),
            preview = preview,
        )
    }

    suspend fun placeEventOrder(
        cfg: HibtSettings,
        symbol: String,
        directionUp: Boolean,
        amount: Double,
        timeUnit: Int,
    ): OrderResult = withContext(Dispatchers.IO) {
        val spec = try {
            buildPlaceSpec(cfg, symbol, directionUp, amount, timeUnit)
        } catch (e: Exception) {
            return@withContext OrderResult(false, "参数校验失败: ${e.message}", dryRun = true)
        }

        if (cfg.dryRun || !cfg.autoTrade) {
            return@withContext OrderResult(
                true,
                "DRY-RUN 未提交\n${spec.preview}\n" +
                    "（需：关闭 Dry-Run + 开启自动化 才真实下单）",
                dryRun = true,
                raw = spec.preview,
            )
        }
        if (tokenOf(cfg).isBlank()) {
            return@withContext OrderResult(false, "缺少 token，拒绝下单", dryRun = false)
        }

        val form = FormBody.Builder()
            .add("amount", spec.amount)
            .add("direction", spec.direction.toString())
            .add("symbol", spec.symbol)
            .add("timeUnit", spec.timeUnit.toString())
            .add("langCode", spec.langCode)
            .build()

        // 实盘只打「主 apiBase + 规范 path」，避免打到错误镜像造成异常成交
        val paths = listOf(
            "/option/option-order/place",
            "/event/event-order/place",
        )
        val bases = listOf(spec.apiBase) + candidateBases(cfg).filter { it != spec.apiBase }
        val tries = StringBuilder()
        var lastRaw: String? = null

        for (base in bases) {
            for (path in paths) {
                val url = withV(base + path, cfg)
                try {
                    val req = Request.Builder().url(url).post(form)
                        .applyHibtHeaders(cfg)
                        .header("content-type", "application/x-www-form-urlencoded")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        lastRaw = body.take(500)
                        tries.appendLine("${resp.code} $path @ $base tu=${spec.timeUnit} ${body.take(100).replace("\n", " ")}")
                        val paramErr = body.contains("参数") || body.contains("param", ignoreCase = true)
                        val okBiz = resp.isSuccessful && bizOk(resp.code, body) && !paramErr
                        if (okBiz) {
                            return@withContext OrderResult(
                                true,
                                "已提交 timeUnit=${spec.timeUnit}m amount=${spec.amount} ${spec.symbol} dir=${spec.direction}\n${body.take(160)}",
                                dryRun = false,
                                raw = lastRaw,
                            )
                        }
                    }
                } catch (e: Exception) {
                    tries.appendLine("ERR $path @ $base ${e.message?.take(48)}")
                }
            }
            // 主域名试完路径后再试回退域名
        }
        OrderResult(
            false,
            "下单失败（已按规范提交 amount/direction/symbol/timeUnit）\n${spec.preview}\n${tries.toString().take(600)}",
            dryRun = false,
            raw = lastRaw,
        )
    }
}
