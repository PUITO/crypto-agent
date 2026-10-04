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
    /** LOGREG_PACK | FUSION_V1 */
    val engine: String = "LOGREG_PACK",
    val featureNames: List<String> = emptyList(),
    val weights: Map<String, Double> = emptyMap(),
    val params: Map<String, Double> = emptyMap(),
    val locked: Boolean = true,
    val version: Int = 1,
    /** 金融因子模型 ID，见 FinancialFactorEngine */
    val financeId: String = "",
    val financeParams: Map<String, Double> = emptyMap(),
    /** 融合权重：分类器 / 金融因子 / 在线AI，和为 1 最佳 */
    val wClassifier: Double = 1.0,
    val wFinance: Double = 0.0,
    val wOnline: Double = 0.0,
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
        version = 2,
        updated = "2026-10-04",
        baseUrl = DEFAULT_BASE,
        models = listOf(
            OfflineCatalogEntry(
                "off_momentum_v1", "动量融合 V1",
                "分类器(动量权重)+时序动量金融因子；可选在线AI加权。低成本离线量化",
                "off_momentum_v1.json",
                "168232cb330ea38667b55227fc9f21d7da573f60388b9b9c48e6bdeeec668c46",
                683, "FUSION_V1", true,
            ),
            OfflineCatalogEntry(
                "off_meanrev_v1", "回归融合 V1",
                "分类器(回归权重)+均值回归金融因子；震荡市友好。离线可跑",
                "off_meanrev_v1.json",
                "c4775b3bf2201e27c233ccd4171d5e0076a7f028afeff38c66c8314d14d3ef2e",
                661, "FUSION_V1", true,
            ),
            OfflineCatalogEntry(
                "off_breakout_v1", "突破融合 V1",
                "分类器+波动突破金融因子；放量突破场景。离线可跑",
                "off_breakout_v1.json",
                "a5894a40b25f3c8c22179ec148c9e865b9ebccd817b2b5a5f2af53851b1db038",
                642, "FUSION_V1", true,
            ),
            OfflineCatalogEntry(
                "off_trend_v1", "趋势融合 V1",
                "分类器+趋势质量金融因子；识别趋势强度。离线可跑",
                "off_trend_v1.json",
                "72fe0fe22c88fe385d7ad00261a93fe701aaef60c61e079ead5cadbf73fd9d17",
                640, "FUSION_V1", true,
            ),
        ),
        online = OfflineOnlineInfo(
            "online_ai", "在线 AI 预测",
            "融合第三腿 + Chat 对话式金融预测（需 LLM Key）",
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
            if (pack.engine != "LOGREG_PACK" && pack.engine != "FUSION_V1") {
                error("不支持的引擎: ${pack.engine}")
            }
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
