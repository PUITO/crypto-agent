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
        // 官网控制台：/rest/c/future/u/user/balance → 字段 amount
        "amount",
        "availableBalance", "usableBalance", "canUseAmount", "optionBalance",
        "eventBalance", "walletBalance", "available", "balance", "usdtBalance",
        "canUse", "money", "equity", "balanceStr", "availBalance",
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

    /**
     * 连通性 / 余额：只用控制台确认过的少量接口，避免多路径扫接口触发风控。
     * 余额：GET /rest/c/future/u/user/balance?langCode=&v= → 字段 amount
     * 鉴权：GET /uc/member/my-info（1 次）
     * 持仓：最多 1 次 POST /option/option-order/list（失败则跳过，不扫其它）
     */
    suspend fun testConnectivity(cfg: HibtSettings): AccountSnapshot = withContext(Dispatchers.IO) {
        if (tokenOf(cfg).isBlank()) {
            return@withContext AccountSnapshot(false, "请填写 x-auth-token")
        }
        val log = StringBuilder()
        // 余额接口以 api.hibt0.com 为准（控制台抓包）；若用户配置的是同源 api 也优先用配置
        val balBases = buildList {
            val primary = cfg.apiBase.trim().trimEnd('/')
            if (primary.startsWith("http")) add(primary)
            add("https://api.hibt0.com")
        }.distinct()

        var balance: String? = null
        var posCount: Int? = null
        var anyAuth = false
        var usedBase: String? = null
        val lang = cfg.langCode.ifBlank { "zh_CN" }

        fun balanceUrl(base: String): String {
            val q = "langCode=$lang" + if (cfg.vParam.isNotBlank()) {
                "&v=${java.net.URLEncoder.encode(cfg.vParam.trim(), "UTF-8")}"
            } else ""
            return "$base/rest/c/future/u/user/balance?$q"
        }

        // 1) 余额：每 base 只请求 1 次
        for (base in balBases) {
            val url = balanceUrl(base)
            try {
                val (code, body) = request("GET", url, cfg)
                log.appendLine("GET $code /rest/c/future/u/user/balance @ $base ${body.take(90).replace("\n", " ")}")
                if (bizOk(code, body)) {
                    anyAuth = true
                    usedBase = base
                    extractBalanceStrict(body)?.let { balance = it }
                    // 专门再抽 amount（控制台字段）
                    if (balance.isNullOrBlank()) {
                        try {
                            val root = JsonParser.parseString(body)
                            fun findAmount(el: com.google.gson.JsonElement?, d: Int): String? {
                                if (el == null || d > 5) return null
                                if (el.isJsonObject) {
                                    val o = el.asJsonObject
                                    if (o.has("amount") && o.get("amount").isJsonPrimitive) {
                                        return o.get("amount").asString
                                    }
                                    for ((_, v) in o.entrySet()) {
                                        findAmount(v, d + 1)?.let { return it }
                                    }
                                } else if (el.isJsonArray) {
                                    for (x in el.asJsonArray) findAmount(x, d + 1)?.let { return it }
                                }
                                return null
                            }
                            findAmount(root, 0)?.let { balance = it }
                        } catch (_: Exception) {}
                    }
                    if (!balance.isNullOrBlank()) break
                }
            } catch (e: Exception) {
                log.appendLine("GET ERR balance @ $base ${e.message?.take(48)}")
            }
        }

        // 2) 鉴权兜底：仅 1 次 my-info（余额未成功时）
        if (!anyAuth) {
            val base = balBases.first()
            val url = withV("$base/uc/member/my-info", cfg)
            try {
                val (code, body) = request("GET", url, cfg)
                log.appendLine("GET $code /uc/member/my-info @ $base ${body.take(70).replace("\n", " ")}")
                if (bizOk(code, body)) {
                    anyAuth = true
                    usedBase = base
                }
            } catch (e: Exception) {
                log.appendLine("GET ERR my-info ${e.message?.take(40)}")
            }
        }

        // 3) 持仓：最多 1 次 POST，不再扫 event/summary 等
        if (anyAuth) {
            val base = usedBase ?: balBases.first()
            val url = withV("$base/option/option-order/list", cfg)
            try {
                val (code, body) = request("POST", url, cfg, pageForm())
                log.appendLine("POST $code /option/option-order/list @ $base ${body.take(70).replace("\n", " ")}")
                if (bizOk(code, body)) {
                    posCount = extractOpenPositionCount(body)
                }
            } catch (e: Exception) {
                log.appendLine("POST ERR list ${e.message?.take(40)}")
            }
        }

        if (!anyAuth) {
            return@withContext AccountSnapshot(
                false,
                "鉴权失败。余额接口：GET /rest/c/future/u/user/balance（字段 amount）。请确认 token / v。",
                raw = log.toString().take(1500),
            )
        }

        val posText = when (posCount) {
            null -> "未查询到（已限制仅 1 次 list，降低风控）"
            else -> "未平仓 ${posCount} 笔"
        }
        AccountSnapshot(
            ok = true,
            message = "在线查询完成 · base=${usedBase ?: balBases.first()}" +
                (if (balance == null) " · 余额未识别" else " · amount=$balance") +
                (if (posCount == null) " · 持仓未识别" else ""),
            balance = balance ?: "—",
            positions = posText,
            raw = log.toString().take(1800),
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
        // 实盘最多试 2 个 base，减少无效重试触发风控
        val bases = (listOf(spec.apiBase) + candidateBases(cfg).filter { it != spec.apiBase }).distinct().take(2)
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
