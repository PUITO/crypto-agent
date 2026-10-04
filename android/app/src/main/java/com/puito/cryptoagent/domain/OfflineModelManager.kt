package com.puito.cryptoagent.domain

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class OfflineCatalog(
    val version: Int = 1,
    val updated: String = "",
    val baseUrl: String = "",
    val models: List<OfflineCatalogEntry> = emptyList(),
    val online: OfflineOnlineInfo? = null,
)

data class OfflineOnlineInfo(
    val id: String = "online_ai",
    val name: String = "在线 AI 预测",
    val desc: String = "",
)

data class OfflineCatalogEntry(
    val id: String,
    val name: String,
    val desc: String = "",
    val file: String,
    val sha256: String,
    val sizeBytes: Long = 0,
    val engine: String = "LOGREG_PACK",
    val locked: Boolean = true,
)

data class OfflinePack(
    val id: String,
    val name: String = "",
    val desc: String = "",
    val engine: String = "LOGREG_PACK",
    val featureNames: List<String> = emptyList(),
    val weights: Map<String, Double> = emptyMap(),
    val params: Map<String, Double> = emptyMap(),
    val locked: Boolean = true,
    val version: Int = 1,
)

/**
 * 离线小金融模型包管理：从 GitHub 分支拉取 catalog + 权重 JSON，校验 SHA256 后落盘。
 * 仅提供清单中存在的可下载项，避免无效链接。
 */
class OfflineModelManager(private val context: Context) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val rootDir: File
        get() = File(context.filesDir, "offline_models").also { if (!it.exists()) it.mkdirs() }

    private val catalogFile: File get() = File(rootDir, "catalog.json")

    fun catalog(): OfflineCatalog? {
        if (!catalogFile.exists()) return null
        return runCatching {
            gson.fromJson(catalogFile.readText(), OfflineCatalog::class.java)
        }.getOrNull()
    }

    /** 内置兜底清单（与仓库 android/models/catalog.json 同步；网络失败时仍可显示名称，下载需网络） */
    fun builtinCatalog(): OfflineCatalog = OfflineCatalog(
        version = 1,
        updated = "2026-10-04",
        baseUrl = DEFAULT_BASE,
        models = listOf(
            OfflineCatalogEntry(
                "off_momentum_v1", "动量顺势 V1",
                "离线小模型：偏顺势动量；固定参数不可改",
                "off_momentum_v1.json",
                "a5c43a4c43a8edaf48c1e706425f55b8553dd62c5eb79b1df305c8bf8c4a3a22",
                565, "LOGREG_PACK", true,
            ),
            OfflineCatalogEntry(
                "off_meanrev_v1", "均值回归 V1",
                "离线小模型：超买超卖回归；固定参数不可改",
                "off_meanrev_v1.json",
                "54e5144e87c160d3264f21fdeb36aecc4d9e050aba6f4c6d65d482e710b0af1c",
                569, "LOGREG_PACK", true,
            ),
            OfflineCatalogEntry(
                "off_breakout_v1", "波动突破 V1",
                "离线小模型：放量突破；固定参数不可改",
                "off_breakout_v1.json",
                "fa762dfaeb4eb38779975ea088c877eec2cda254f6e909e24b9ad3daf0afb923",
                552, "LOGREG_PACK", true,
            ),
        ),
        online = OfflineOnlineInfo(
            "online_ai", "在线 AI 预测",
            "使用已配置 LLM（DeepSeek/Grok 等）做对话式金融方向预测",
        ),
    )

    fun effectiveCatalog(): OfflineCatalog = catalog() ?: builtinCatalog()

    fun isInstalled(id: String): Boolean = packFile(id).exists()

    fun installedIds(): List<String> =
        rootDir.listFiles()?.filter { it.name.endsWith(".json") && it.name != "catalog.json" }
            ?.map { it.name.removeSuffix(".json") } ?: emptyList()

    fun loadPack(id: String): OfflinePack? {
        val f = packFile(id)
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), OfflinePack::class.java) }.getOrNull()
    }

    fun delete(id: String): Boolean {
        val f = packFile(id)
        return if (f.exists()) f.delete() else true
    }

    private fun packFile(id: String) = File(rootDir, "$id.json")

    suspend fun refreshCatalog(): Result<OfflineCatalog> = withContext(Dispatchers.IO) {
        runCatching {
            val url = DEFAULT_BASE + "catalog.json"
            val body = httpGet(url) ?: error("目录下载失败")
            val cat = gson.fromJson(body, OfflineCatalog::class.java)
                ?: error("目录解析失败")
            if (cat.models.isEmpty()) error("目录为空")
            catalogFile.writeText(body)
            cat
        }
    }

    /**
     * 下载并校验 SHA256。仅允许 catalog 中列出的 id。
     */
    suspend fun download(id: String): Result<OfflinePack> = withContext(Dispatchers.IO) {
        runCatching {
            val cat = catalog() ?: refreshCatalog().getOrThrow()
            val entry = cat.models.find { it.id == id }
                ?: error("模型不在官方清单中，拒绝下载")
            val base = cat.baseUrl.ifBlank { DEFAULT_BASE }
            val url = base.trimEnd('/') + "/" + entry.file
            val bytes = httpGetBytes(url) ?: error("下载失败: $url")
            val sha = sha256(bytes)
            if (entry.sha256.isNotBlank() &&
                !entry.sha256.startsWith("placeholder") &&
                entry.sha256.length >= 16 &&
                !sha.equals(entry.sha256, ignoreCase = true)
            ) {
                error("SHA256 校验失败 expect=${entry.sha256.take(12)}… got=${sha.take(12)}…")
            }
            val pack = gson.fromJson(bytes.decodeToString(), OfflinePack::class.java)
                ?: error("模型 JSON 解析失败")
            if (pack.weights.isEmpty()) error("模型权重为空，不可用")
            if (pack.engine != "LOGREG_PACK") error("不支持的引擎: ${pack.engine}")
            packFile(id).writeBytes(bytes)
            pack.copy(id = id, name = pack.name.ifBlank { entry.name }, locked = true)
        }
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun httpGetBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).get().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }

    private fun sha256(data: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        return d.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_BASE =
            "https://raw.githubusercontent.com/PUITO/crypto-agent/android-app/android/models/"
    }
}
