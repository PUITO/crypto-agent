package com.puito.cryptoagent.util

import com.google.gson.JsonParser
import com.puito.cryptoagent.data.HibtSettings
import java.net.URLDecoder

/**
 * 解析书签 / 导入页 / 控制台复制的 HiBT 认证文本：
 * 1) key=value（含 v=）
 * 2) JSON
 * 3) 任意 URL 中的 ?v= / &v=（余额、持仓接口必备）
 */
object HibtBundleParser {

    data class Result(
        val settings: HibtSettings,
        val summary: String,
        val ok: Boolean,
    )

    fun parse(raw: String, base: HibtSettings = HibtSettings()): Result {
        val text = raw.trim()
        if (text.isEmpty()) {
            return Result(base, "剪贴板为空", false)
        }
        if (text.startsWith("{")) {
            return parseJson(text, base)
        }
        val i = text.indexOf('{')
        val j = text.lastIndexOf('}')
        if (i >= 0 && j > i) {
            val maybe = text.substring(i, j + 1)
            if (maybe.contains("token") || maybe.contains("apiBase") || maybe.contains("\"v\"")) {
                val r = parseJson(maybe, base)
                if (r.ok) return r
            }
        }
        return parseKvAndUrls(text, base)
    }

    /** 从整段文本提取 v=（URL 查询或 key=value） */
    private fun extractV(text: String): String {
        // 1) URL query v=
        val urlV = Regex("""[?&]v=([^&\s"'<>]+)""", RegexOption.IGNORE_CASE).find(text)
        if (urlV != null) {
            return try {
                URLDecoder.decode(urlV.groupValues[1].trim(), "UTF-8")
            } catch (_: Exception) {
                urlV.groupValues[1].trim()
            }
        }
        // 2) 行内 v= 或 vParam=
        for (line in text.lines()) {
            val t = line.trim()
            val m = Regex("""^(?:v|vparam)\s*[=:]\s*(.+)$""", RegexOption.IGNORE_CASE).matchEntire(t)
            if (m != null) {
                return m.groupValues[1].trim().trim('"')
            }
        }
        return ""
    }

    private fun extractApiFromText(text: String, fallback: String): String {
        val m = Regex(
            """https?://(?:api\.hibt0\.com|api-ws\.taichuwuji\.com|api\.hibt\.com)""",
            RegexOption.IGNORE_CASE,
        ).find(text)
        return m?.value?.trimEnd('/') ?: fallback
    }

    private fun parseJson(text: String, base: HibtSettings): Result {
        return try {
            val o = JsonParser.parseString(text).asJsonObject
            fun str(vararg keys: String): String {
                for (k in keys) {
                    if (o.has(k) && !o.get(k).isJsonNull) {
                        val v = o.get(k)
                        if (v.isJsonPrimitive) return v.asString.trim()
                    }
                }
                return ""
            }
            val token = str("token", "xAuthToken", "x-auth-token", "authToken", "authorization")
                .removePrefix("Bearer ").trim()
            val api = str("apiBaseUrl", "apiBase", "api_base")
                .ifBlank { extractApiFromText(text, base.apiBase) }
            val bgetKey = str("bgetKey", "bget_key", "BGET_KEY").ifBlank { base.bgetKey }
            val bgetId = str("bgetId", "bget_id", "BGET_ID")
            val lang = str("langCode", "lang", "hc-language").ifBlank { base.langCode }
            var client = str("clientType", "client_type", "platform")
            if (client == "1" || client.equals("pc", true) || client.equals("web", true)) client = "web"
            if (client == "2" || client.equals("h5", true) || client.equals("mobile", true)) client = "h5"
            if (client.isBlank()) client = base.clientType
            val vParam = str("v", "vParam", "v_param").ifBlank { extractV(text) }
            if (token.isBlank()) {
                return Result(base, "JSON 中无 token", false)
            }
            val nh = base.copy(
                apiBase = api.ifBlank { "https://api.hibt0.com" },
                xAuthToken = token,
                authToken = token,
                bgetKey = bgetKey,
                bgetId = bgetId.ifBlank { base.bgetId },
                langCode = lang,
                clientType = client,
                vParam = vParam.ifBlank { base.vParam },
            )
            val tip = buildString {
                append("已从 JSON 解析 token")
                if (nh.vParam.isNotBlank()) {
                    append(if (nh.vParam.length > 20 && !nh.vParam.all { it.isDigit() })
                        " · v(长串，多用于持仓/动态，余额可能仍需时间戳 v)"
                    else " · v(余额可用)")
                }
                if (nh.apiBase.isNotBlank()) append(" · apiBase")
            }
            Result(nh, tip, true)
        } catch (e: Exception) {
            Result(base, "JSON 解析失败: ${e.message}", false)
        }
    }

    private fun parseKvAndUrls(text: String, base: HibtSettings): Result {
        val map = linkedMapOf<String, String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val sep = when {
                t.contains("=") -> "="
                t.contains(":") -> ":"
                else -> null
            } ?: continue
            val idx = t.indexOf(sep)
            if (idx <= 0) continue
            val k = t.substring(0, idx).trim().lowercase().replace("-", "").replace("_", "")
            val v = t.substring(idx + 1).trim().removePrefix("Bearer ").trim()
            if (v.isNotEmpty() && !k.startsWith("http")) map[k] = v
        }
        fun g(vararg keys: String): String {
            for (k in keys) {
                val kk = k.lowercase().replace("-", "").replace("_", "")
                map[kk]?.let { return it }
            }
            return ""
        }
        val token = g("xauthtoken", "authtoken", "token", "authorization")
        val api = g("apibase", "apibaseurl", "baseurl").ifBlank { extractApiFromText(text, base.apiBase) }
        val bgetKey = g("bgetkey", "vkey")
        val bgetId = g("bgetid", "memberid", "uid")
        val lang = g("langcode", "lang", "hclanguage")
        var client = g("clienttype", "platform")
        if (client == "1") client = "web"
        if (client == "2") client = "h5"
        val vParam = g("v", "vparam").ifBlank { extractV(text) }

        // 纯 URL 粘贴：只有 v
        if (token.isBlank() && api.isBlank() && bgetId.isBlank() && vParam.isBlank()) {
            return Result(base, "未识别到 token / v / apiBase", false)
        }

        val nh = base.copy(
            apiBase = api.ifBlank { base.apiBase.ifBlank { "https://api.hibt0.com" } },
            xAuthToken = token.ifBlank { base.xAuthToken },
            authToken = token.ifBlank { base.authToken },
            bgetKey = bgetKey.ifBlank { base.bgetKey },
            bgetId = bgetId.ifBlank { base.bgetId },
            langCode = lang.ifBlank { base.langCode },
            clientType = client.ifBlank { base.clientType },
            vParam = vParam.ifBlank { base.vParam },
        )
        val ok = nh.xAuthToken.isNotBlank() || nh.authToken.isNotBlank()
        val tip = buildString {
            if (ok) append("已解析") else append("部分解析（仍缺 token）")
            if (nh.vParam.isNotBlank()) {
                append(
                    if (nh.vParam.length > 20 && !nh.vParam.all { it.isDigit() })
                        " · v已填(动态型，持仓用；余额请再贴 balance 的时间戳 v)"
                    else " · v已填(余额型)"
                )
            } else append(" · 建议粘贴余额 URL 带上 v")
            if (nh.apiBase.isNotBlank()) append(" · ${nh.apiBase}")
        }
        return Result(nh, tip, ok)
    }
}
