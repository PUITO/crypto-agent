package com.puito.cryptoagent.net

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.puito.cryptoagent.data.Candle
import com.puito.cryptoagent.data.ServiceHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 对接 crypto-agent Gateway（master 微服务），与 crypto-app 无关。
 */
class AgentApi(
    private var baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    fun updateBase(url: String) {
        baseUrl = url.trim().trimEnd('/')
    }

    suspend fun healthAll(): List<ServiceHealth> = withContext(Dispatchers.IO) {
        val body = getJson("/api/v1/health/all")
        val list = mutableListOf<ServiceHealth>()
        if (body.isJsonObject) {
            val obj = body.asJsonObject
            // 兼容 map 或 list
            if (obj.has("services") && obj.get("services").isJsonObject) {
                obj.getAsJsonObject("services").entrySet().forEach { (k, v) ->
                    val ok = when {
                        v.isJsonObject -> v.asJsonObject.get("status")?.asString == "ok" ||
                            v.asJsonObject.get("ok")?.asBoolean == true
                        v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                        else -> v.toString().contains("ok", true)
                    }
                    list.add(ServiceHealth(k, ok, v.toString().take(80)))
                }
            } else {
                obj.entrySet().forEach { (k, v) ->
                    if (k in listOf("ok", "status", "version")) return@forEach
                    val ok = v.toString().contains("ok", true) ||
                        (v.isJsonObject && (v.asJsonObject.get("ok")?.asBoolean == true))
                    list.add(ServiceHealth(k, ok, v.toString().take(80)))
                }
            }
        }
        if (list.isEmpty()) list.add(ServiceHealth("gateway", true, body.toString().take(60)))
        list
    }

    suspend fun mobileHealth(): String = withContext(Dispatchers.IO) {
        runCatching { getJson("/api/v1/mobile/health").toString() }
            .getOrElse { "mobile api: ${it.message}" }
    }

    suspend fun klines(symbol: String, interval: String, limit: Int): List<Candle> =
        withContext(Dispatchers.IO) {
            // 1) mobile API
            runCatching {
                val j = getJson("/api/v1/mobile/klines?symbol=$symbol&interval=$interval&limit=$limit")
                parseKlines(j)
            }.getOrElse {
                // 2) data proxy
                val j = getJson("/data/api/v1/klines?symbol=$symbol&interval=$interval&limit=$limit")
                parseKlines(j)
            }
        }

    suspend fun chat(message: String, sessionId: String = "android"): String =
        withContext(Dispatchers.IO) {
            val payload = """{"message":${gson.toJson(message)},"session_id":${gson.toJson(sessionId)}}"""
            val j = postJson("/agent/api/v1/chat", payload)
            when {
                j.isJsonObject && j.asJsonObject.has("reply") ->
                    j.asJsonObject.get("reply").asString
                j.isJsonObject && j.asJsonObject.has("message") ->
                    j.asJsonObject.get("message").asString
                j.isJsonObject && j.asJsonObject.has("content") ->
                    j.asJsonObject.get("content").asString
                else -> j.toString()
            }
        }

    suspend fun configPublic(): String = withContext(Dispatchers.IO) {
        runCatching { getJson("/config/api/v1/config").toString() }
            .getOrElse { "config error: ${it.message}" }
    }

    private fun getJson(path: String): JsonElement {
        val req = Request.Builder().url("$baseUrl$path").get().build()
        client.newCall(req).execute().use { resp ->
            val s = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${s.take(200)}")
            return JsonParser.parseString(s.ifBlank { "{}" })
        }
    }

    private fun postJson(path: String, json: String): JsonElement {
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$baseUrl$path").post(body).build()
        client.newCall(req).execute().use { resp ->
            val s = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${s.take(200)}")
            return JsonParser.parseString(s.ifBlank { "{}" })
        }
    }

    private fun parseKlines(el: JsonElement): List<Candle> {
        val rows = when {
            el.isJsonArray -> el.asJsonArray
            el.isJsonObject -> {
                val o = el.asJsonObject
                when {
                    o.has("klines") -> o.getAsJsonArray("klines")
                    o.has("data") -> o.getAsJsonArray("data")
                    o.has("items") -> o.getAsJsonArray("items")
                    else -> null
                }
            }
            else -> null
        } ?: return emptyList()

        return rows.mapNotNull { item ->
            try {
                if (item.isJsonObject) {
                    val o = item.asJsonObject
                    Candle(
                        openTime = (o.get("open_time") ?: o.get("openTime"))?.asLong ?: 0L,
                        open = (o.get("open") ?: o.get("o")).asDouble,
                        high = (o.get("high") ?: o.get("h")).asDouble,
                        low = (o.get("low") ?: o.get("l")).asDouble,
                        close = (o.get("close") ?: o.get("c")).asDouble,
                        volume = (o.get("volume") ?: o.get("v"))?.asDouble ?: 0.0,
                    )
                } else if (item.isJsonArray) {
                    val a = item.asJsonArray
                    Candle(
                        openTime = a[0].asLong,
                        open = a[1].asDouble,
                        high = a[2].asDouble,
                        low = a[3].asDouble,
                        close = a[4].asDouble,
                        volume = a[5].asDouble,
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
