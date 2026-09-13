package com.puito.cryptoagent.util

import com.google.gson.JsonParser
import com.puito.cryptoagent.data.HibtSettings

/**
 * 解析书签 / 导入页复制的 HiBT 认证文本：
 * 1) key=value 行（apiBase=… / xAuthToken=…）
 * 2) JSON 对象（token / apiBaseUrl / bgetKey / …）
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
        // 优先 JSON
        if (text.startsWith("{")) {
            return parseJson(text, base)
        }
        // 尝试从文本中截取 JSON 块
        val i = text.indexOf('{')
        val j = text.lastIndexOf('}')
        if (i >= 0 && j > i) {
            val maybe = text.substring(i, j + 1)
            if (maybe.contains("token") || maybe.contains("apiBase")) {
                val r = parseJson(maybe, base)
                if (r.ok) return r
            }
        }
        return parseKv(text, base)
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
                .ifBlank { base.apiBase }
            val bgetKey = str("bgetKey", "bget_key", "BGET_KEY").ifBlank { base.bgetKey }
            val bgetId = str("bgetId", "bget_id", "BGET_ID")
            val lang = str("langCode", "lang", "hc-language").ifBlank { base.langCode }
            var client = str("clientType", "client_type", "platform")
            if (client == "1" || client.equals("pc", true) || client.equals("web", true)) client = "web"
            if (client == "2" || client.equals("h5", true) || client.equals("mobile", true)) client = "h5"
            if (client.isBlank()) client = base.clientType
            if (token.isBlank()) {
                return Result(base, "JSON 中无 token", false)
            }
            val nh = base.copy(
                apiBase = api,
                xAuthToken = token,
                authToken = token,
                bgetKey = bgetKey,
                bgetId = bgetId.ifBlank { base.bgetId },
                langCode = lang,
                clientType = client,
            )
            Result(nh, "已从 JSON 解析 token / apiBase / bget*", true)
        } catch (e: Exception) {
            Result(base, "JSON 解析失败: ${e.message}", false)
        }
    }

    private fun parseKv(text: String, base: HibtSettings): Result {
        val map = linkedMapOf<String, String>()
        // 支持 key=value 与 key: value
        val lines = text.lines()
        for (line in lines) {
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
            if (v.isNotEmpty()) map[k] = v
        }
        fun g(vararg keys: String): String {
            for (k in keys) {
                val kk = k.lowercase().replace("-", "").replace("_", "")
                map[kk]?.let { return it }
            }
            return ""
        }
        val token = g("xauthtoken", "authtoken", "token", "authorization", "xauthtoken")
        val api = g("apibase", "apibaseurl", "baseurl")
        val bgetKey = g("bgetkey", "vkey")
        val bgetId = g("bgetid", "memberid", "uid")
        val lang = g("langcode", "lang", "hclanguage")
        var client = g("clienttype", "platform")
        if (client == "1") client = "web"
        if (client == "2") client = "h5"
        if (token.isBlank() && api.isBlank() && bgetId.isBlank()) {
            return Result(base, "未识别到 key=value 字段", false)
        }
        val nh = base.copy(
            apiBase = api.ifBlank { base.apiBase },
            xAuthToken = token.ifBlank { base.xAuthToken },
            authToken = token.ifBlank { base.authToken },
            bgetKey = bgetKey.ifBlank { base.bgetKey },
            bgetId = bgetId.ifBlank { base.bgetId },
            langCode = lang.ifBlank { base.langCode },
            clientType = client.ifBlank { base.clientType },
        )
        val ok = nh.xAuthToken.isNotBlank() || nh.authToken.isNotBlank()
        return Result(
            nh,
            if (ok) "已从文本解析并填入" else "已部分解析，仍缺 token",
            ok,
        )
    }
}
