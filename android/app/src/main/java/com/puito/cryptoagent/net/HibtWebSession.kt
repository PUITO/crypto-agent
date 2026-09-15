package com.puito.cryptoagent.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonParser
import com.puito.cryptoagent.data.HibtSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 常驻 WebView 会话：登录后隐藏不销毁，信号通过注入脚本在页面环境内下单。
 * 不在原生层伪造加密 v；优先使用拦截到的当次 token/v，或页面内 fetch。
 */
object HibtWebSession {

    data class SessionUi(
        val ready: Boolean = false,
        val pageUrl: String = "",
        val orderPageLocked: String = "",
        val tokenPreview: String = "",
        val hasV: Boolean = false,
        val lastVAt: Long = 0L,
        val balance: String = "—",
        val positions: String = "—",
        val status: String = "未启动",
        val lastPlaceMsg: String = "",
        val loaded: Boolean = false,
        /** 已写回原生输入框的 token 预览时间戳 */
        val nativeTokenSyncedAt: Long = 0L,
    )

    private val main = Handler(Looper.getMainLooper())
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    private val logLock = Any()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val maxLogLines = 200

    fun appendLog(msg: String) {
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) + " " + msg.trim()
        synchronized(logLock) {
            val next = (_logs.value + line).takeLast(maxLogLines)
            _logs.value = next
        }
        // 同步到 status 尾部便于观察
        _ui.value = _ui.value.copy(status = msg.take(120))
    }

    fun clearLogs() {
        _logs.value = emptyList()
        appendLog("日志已清空")
    }

    fun dumpLogs(): String = _logs.value.joinToString("\n")

    @Volatile private var webView: WebView? = null
    @Volatile private var appCtx: android.content.Context? = null
    @Volatile private var lastToken: String = ""
    @Volatile private var lastV: String = ""
    @Volatile private var lastApiBase: String = "https://api-ws.taichuwuji.com"
    @Volatile private var lastOrigin: String = "https://hibt.com"
    @Volatile private var lastReferer: String = "https://hibt.com/"
    /** 用户停留过的事件合约下单页（隐藏后再打开不丢） */
    @Volatile var lastOrderPageUrl: String = ""
        private set
    private var prefs: android.content.SharedPreferences? = null
    /** 拦截到的加密 v（长 base64 等） */
    @Volatile private var lastVEnc: String = ""
    /** 拦截/生成的明文时间戳 v */
    @Volatile private var lastVPlain: String = ""
    @Volatile private var lastSyncedToken: String = ""
    @Volatile private var lastSyncedApi: String = ""
    @Volatile private var placeWait: CompletableDeferred<PlaceOutcome>? = null
    private val placeSeq = AtomicLong(0)

    data class PlaceOutcome(val ok: Boolean, val message: String, val dryRun: Boolean)

    /**
     * 由 Application/Repository 注入：WebView 捕获 token/api 后写回原生设置。
     * (token, apiBase, origin)
     */
    @Volatile var settingsSync: ((String, String, String) -> Unit)? = null

    fun peek(): WebView? = webView

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context): WebView {
        webView?.let { return it }
        synchronized(this) {
            webView?.let { return it }
            val appCtx = context.applicationContext
            this.appCtx = appCtx
            if (prefs == null) {
                prefs = appCtx.getSharedPreferences("hibt_web_session", 0)
                lastOrderPageUrl = prefs?.getString("order_page_url", "").orEmpty()
                if (lastOrderPageUrl.isNotBlank()) {
                    _ui.value = _ui.value.copy(orderPageLocked = lastOrderPageUrl)
                }
            }
            val wv = WebView(appCtx)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                // 关键默认带 "; wv)" 的 UA，很多站点会卡 API loading
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            wv.addJavascriptInterface(Bridge(), "CaHibt")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (looksLikeOrderPage(url)) {
                        rememberOrderPage(url)
                    }
                    val tip = if (looksLikeOrderPage(url)) "合约下单页已锁定" else "页面已加载（请进入事件合约下单页）"
                    _ui.value = _ui.value.copy(pageUrl = url, loaded = true, status = tip)
                    // 不全页狂刷注入；仅首次/冷却后补一次轻量脚本（无网络钩子）
                    injectHooks(view, force = false)
                }
            }
            webView = wv
            _ui.value = _ui.value.copy(status = "WebView 已创建，请打开并登录")
            return wv
        }
    }

    fun detachFromParent() {
        val wv = webView ?: return
        main.post {
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
    }

    fun looksLikeOrderPage(url: String?): Boolean {
        val u = (url ?: "").lowercase()
        if (u.isBlank() || u == "about:blank") return false
        // 路径/哈希含事件/期权/交易特征；纯官网根路径不算
        val hit = listOf(
            "event", "option", "contract", "trade", "binary", "second",
            "事件", "期权", "合约",
        ).any { it in u }
        if (hit) return true
        // 已锁定页与当前一致
        if (lastOrderPageUrl.isNotBlank() && url == lastOrderPageUrl) return true
        return false
    }

    fun rememberOrderPage(url: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        lastOrderPageUrl = url
        prefs?.edit()?.putString("order_page_url", url)?.apply()
        _ui.value = _ui.value.copy(
            status = "已记住下单页",
            pageUrl = url,
            orderPageLocked = url,
        )
    }

    /**
     * 打开/显示：不强制回首页。
     * - 已有页面 URL → 不重新 load（避免 SPA 被重置）
     * - 否则优先恢复「已记住的事件合约页」
     * - 都没有才打开官网根路径让用户登录后自己点进合约
     */
    fun openSession(context: Context, forceReload: Boolean = false) {
        val wv = obtain(context)
        main.post {
            val cur = wv.url
            when {
                !forceReload && !cur.isNullOrBlank() && cur != "about:blank" -> {
                    _ui.value = _ui.value.copy(
                        status = if (looksLikeOrderPage(cur)) "显示中·合约页保活" else "显示中·请进入事件合约下单页",
                        pageUrl = cur,
                    )
                    injectHooks(wv)
                }
                lastOrderPageUrl.isNotBlank() -> {
                    _ui.value = _ui.value.copy(status = "恢复下单页…")
                    wv.loadUrl(lastOrderPageUrl)
                }
                else -> {
                    _ui.value = _ui.value.copy(status = "首次打开官网，登录后请进入事件合约并点「锁定当前为下单页」")
                    wv.loadUrl("https://hibt.com")
                }
            }
        }
    }

    @Deprecated("Use openSession")
    fun openHome(context: Context, url: String = "https://hibt.com") {
        openSession(context, forceReload = false)
    }

    /** 显式进入/恢复事件合约页（下单前也会自动调用） */
    fun goOrderPage(context: Context? = null) {
        val wv = webView ?: context?.let { obtain(it) } ?: return
        main.post {
            wakeWebView(wv)
            val target = lastOrderPageUrl.ifBlank { null }
            if (target != null) {
                if (wv.url != target) {
                    _ui.value = _ui.value.copy(status = "导航到已锁定下单页…")
                    wv.loadUrl(target)
                } else {
                    injectHooks(wv)
                    // 尝试页内点「事件合约」菜单（SPA 被踢回首页时）
                    wv.evaluateJavascript(GO_EVENT_MENU_JS, null)
                }
            } else {
                wv.evaluateJavascript(GO_EVENT_MENU_JS, null)
                _ui.value = _ui.value.copy(status = "未锁定下单页：尝试点击「事件合约」菜单，成功后请点锁定")
            }
        }
    }

    /**
     * 下单前确保在合约页：若当前不是，则恢复 lastOrderPageUrl 并等待加载。
     */
    suspend fun ensureOrderPage(timeoutMs: Long = 10_000L): Boolean {
        val wv = webView ?: return false
        main.post { wakeWebView(wv) }
        val cur = wv.url
        if (looksLikeOrderPage(cur)) {
            main.post { injectHooks(wv) }
            return true
        }
        val target = lastOrderPageUrl
        if (target.isBlank()) {
            main.post {
                wakeWebView(wv)
                wv.evaluateJavascript(GO_EVENT_MENU_JS, null)
            }
            kotlinx.coroutines.delay(2_500)
            return looksLikeOrderPage(webView?.url) || lastOrderPageUrl.isNotBlank()
        }
        main.post {
            _ui.value = _ui.value.copy(status = "下单前恢复合约页…")
            wv.loadUrl(target)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(400)
            val u = webView?.url
            if (looksLikeOrderPage(u) || u == target) {
                main.post { webView?.let { injectHooks(it) } }
                kotlinx.coroutines.delay(500)
                return true
            }
        }
        return looksLikeOrderPage(webView?.url)
    }

    fun lockCurrentAsOrderPage() {
        val u = webView?.url
        if (u.isNullOrBlank()) {
            _ui.value = _ui.value.copy(status = "当前无页面，无法锁定")
            return
        }
        rememberOrderPage(u)
        _ui.value = _ui.value.copy(status = "已锁定为下单页（隐藏后再打开会恢复）")
    }

    fun reload() {
        main.post { webView?.reload() }
    }

    private val GO_EVENT_MENU_JS = """
    (function(){
      function T(e){ try{ return (e.innerText||e.textContent||'').trim(); }catch(x){ return ''; } }
      var nodes = document.querySelectorAll('a,button,div,span,li');
      var keys = ['事件合约','事件','期权','Event','Option','二元','秒合约'];
      for (var i=0;i<nodes.length;i++){
        var t = T(nodes[i]);
        if (!t || t.length>16) continue;
        for (var k=0;k<keys.length;k++){
          if (t.indexOf(keys[k])>=0){
            try { nodes[i].click(); return 'clicked:'+t; } catch(e){}
          }
        }
      }
      return 'no-menu';
    })();
    """.trimIndent()

    /**
     * 仅清 WebView **页面缓存**（磁盘/HTTP 缓存），默认保留 Cookie 与登录态。
     * 不删 App 配置。
     */
    fun clearWebViewCache(keepLogin: Boolean = true): String {
        var msg = "WebView 缓存已清理"
        main.post {
            try {
                webView?.clearCache(true)
                webView?.clearFormData()
                webView?.clearHistory()
            } catch (_: Exception) {}
            // 清理 WebView 默认缓存目录（不碰 agent_local 配置）
            try {
                val base = android.webkit.WebView(android.app.Application()).context // may fail
            } catch (_: Exception) {}
            if (!keepLogin) {
                try {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    lastToken = ""
                    lastV = ""
                    lastVEnc = ""
                    lastVPlain = ""
                    lastSyncedToken = ""
                    lastSyncedApi = ""
                    _ui.value = SessionUi(status = "WebView 缓存+登录已清除")
                } catch (_: Exception) {}
            } else {
                _ui.value = _ui.value.copy(status = "WebView 缓存已清（登录保留）")
            }
            appendLog(if (keepLogin) "已清 WebView 缓存（保留 Cookie）" else "已清 WebView 缓存+Cookie")
        }
        // 同步清应用下 webview 相关目录
        val freed = clearWebViewDiskCache()
        msg = "已清 WebView 缓存约 %.2f MB（%s）".format(
            freed / (1024.0 * 1024.0),
            if (keepLogin) "登录保留" else "含登录态",
        )
        return msg
    }

    private fun clearWebViewDiskCache(): Long {
        var total = 0L
        val candidates = mutableListOf<java.io.File>()
        try {
            val app = appCtx ?: return 0L
            candidates += java.io.File(app.cacheDir, "WebView")
            candidates += java.io.File(app.cacheDir, "webview")
            candidates += java.io.File(app.cacheDir, "org.chromium.android_webview")
            app.cacheDir.listFiles()?.filter {
                it.name.contains("webview", true) || it.name.contains("WebView")
            }?.let { candidates.addAll(it) }
            // app_webview under data dir
            candidates += java.io.File(app.dataDir, "app_webview")
            candidates += java.io.File(app.dataDir, "webview")
            for (d in candidates.distinct()) {
                if (!d.exists()) continue
                total += dirSize(d)
                // 只删 Cache/HTTP Cache 子目录，尽量保留 Cookies 文件
                if (keepLoginSafe(d)) {
                    d.listFiles()?.forEach { child ->
                        val n = child.name.lowercase()
                        if (n.contains("cache") || n.contains("http") || n.contains("blob") ||
                            n.contains("gpu") || n.contains("code_cache") || n.endsWith(".cache")
                        ) {
                            total += dirSize(child)
                            runCatching { if (child.isDirectory) child.deleteRecursively() else child.delete() }
                        }
                    }
                } else {
                    // 整个目录是 cache 命名则整删
                    runCatching { d.deleteRecursively() }
                }
            }
        } catch (_: Exception) {}
        return total
    }

    private fun keepLoginSafe(d: java.io.File): Boolean {
        val n = d.name.lowercase()
        return n.contains("webview") || n.contains("app_webview")
    }

    private fun dirSize(f: java.io.File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    fun clearSession(context: Context) {
        main.post {
            lastToken = ""
            lastV = ""
            lastVEnc = ""
            lastVPlain = ""
            lastSyncedToken = ""
            lastSyncedApi = ""
            webView?.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            _ui.value = SessionUi(status = "会话已清除，请重新登录")
            webView?.loadUrl("https://hibt.com")
        }
    }

    /** 请求页面内刷新余额/持仓（注入脚本） */
    fun refreshAccount() {
        main.post {
            webView?.evaluateJavascript("window.__caRefreshAccount && window.__caRefreshAccount()", null)
            _ui.value = _ui.value.copy(status = "正在刷新账户…")
            appendLog("刷新账户请求已发送")
        }
    }

    /**
     * 手动从 WebView 再抓 token/api 并写回原生输入框。
     * 会注入脚本、触发页面请求、强制 emitSession。
     */
    fun forceRefreshToken() {
        val wv = webView
        if (wv == null) {
            appendLog("手动更新 token 失败：WebView 未创建，请先打开并登录")
            return
        }
        main.post {
            wakeWebView(wv)
            lastInjectAt = 0L
            lastSyncedToken = ""
            lastSyncedApi = ""
            injectHooks(wv)
            appendLog("手动更新 token：从页面存储扫描（不钩网络，避免账户loading）")
            // 触发账户接口 + 强制把当前 st 推给原生
            val js = """
            (function(){
              try {
                if (window.__caScanSession) window.__caScanSession();
                // 不自动调余额接口（易 CORS / 干扰页面）
                // 强制再发一次会话（即使 token 未变）
                if (typeof emitSession === 'function') { /* local */ }
                try {
                  var tok = '';
                  try { tok = sessionStorage.getItem('bget_token') || localStorage.getItem('bget_token') || ''; } catch(e){}
                  var origin = location.origin || '';
                  CaHibt.onSession(JSON.stringify({
                    token: tok || (window.__CA_LAST_TOKEN||''),
                    xAuthToken: tok,
                    apiBase: (window.__CA_LAST_API||'') || 'https://api.hibt0.com',
                    origin: origin,
                    referer: location.href || (origin+'/'),
                    v: (window.__CA_LAST_V||'')
                  }));
                } catch(e) { CaHibt.onLog('forceSession '+e); }
                return 'ok';
              } catch(e) { return String(e); }
            })();
            """.trimIndent()
            wv.evaluateJavascript(js, null)
            // 再从 cookie 读（部分站 token 在 cookie）
            wv.evaluateJavascript(
                """
                (function(){
                  try {
                    var m = document.cookie.match(/(?:^|;\s*)(?:bget_token|token)=([^;]+)/);
                    if (m) {
                      CaHibt.onSession(JSON.stringify({
                        token: decodeURIComponent(m[1]),
                        xAuthToken: decodeURIComponent(m[1]),
                        apiBase: window.__CA_LAST_API || 'https://api.hibt0.com',
                        origin: location.origin||'',
                        referer: location.href||''
                      }));
                      return 'cookie-token';
                    }
                  } catch(e){}
                  return 'no-cookie';
                })();
                """.trimIndent(),
                null,
            )
        }
    }

    /**
     * 下单优先级：
     * 1) WebView 触发**平台页面自身下单**（填表/点按钮，v 与签名由官网处理）
     * 2) 降级：页内自建 POST（带 Origin）
     * 3) 再降级：原生 OkHttp + 捕获会话
     */
    suspend fun placeOrder(
        directionUp: Boolean,
        amount: Double,
        symbol: String,
        timeUnit: Int,
        dryRun: Boolean,
        timeoutSec: Int = 45,
    ): PlaceOutcome? {
        val wv = webView
        if (wv == null) return null

        main.post { wakeWebView(wv) }

        // 隐藏后再下单：先恢复到已锁定的事件合约页（避免停在首页点不到买涨/买跌）
        val onOrder = ensureOrderPage(10_000L)
        if (!onOrder) {
            _ui.value = _ui.value.copy(
                status = "未在合约下单页：请打开 WebView 进入事件合约后点「锁定当前为下单页」",
            )
        }

        // 同步页面 origin
        main.post {
            wv.evaluateJavascript(
                "(function(){try{return location.origin||'';}catch(e){return ''}})()",
            ) { originJs ->
                val o = originJs?.trim()?.trim('"')?.takeIf { it.startsWith("http") }
                if (!o.isNullOrBlank()) {
                    lastOrigin = o
                    lastReferer = o.trimEnd('/') + "/"
                }
            }
        }

        val sym = when {
            symbol.equals("BTCUSDT", true) -> "btc_usdt"
            symbol.equals("ETHUSDT", true) -> "eth_usdt"
            symbol.contains("_") -> symbol.lowercase()
            else -> symbol.lowercase().replace("usdt", "_usdt")
        }
        val dir = if (directionUp) 1 else 0
        val unit = when (timeUnit) {
            1 -> 5
            else -> timeUnit
        }
        val amountStr =
            if (kotlin.math.abs(amount - amount.toLong()) < 1e-9) amount.toLong().toString()
            else amount.toString()

        val origin = lastOrigin.ifBlank { "https://hibt.com" }
        val referer = lastReferer.ifBlank { "$origin/" }
        val token = lastToken.trim()
        val vEnc = lastVEnc.ifBlank { lastV }.trim().takeIf { it.isNotBlank() && !it.all(Char::isDigit) }.orEmpty()
        val vPlain = lastVPlain.ifBlank {
            if (lastV.all { it.isDigit() }) lastV else ""
        }.ifBlank { System.currentTimeMillis().toString() }

        if (dryRun) {
            val msg = "DRY-RUN 将触发平台UI下单 amount=$amountStr dir=$dir $sym tu=$unit（不自造v）"
            _ui.value = _ui.value.copy(lastPlaceMsg = msg, status = msg.take(90))
            // 仍走注入 dryRun，让页面脚本回报将要点击的按钮
        }

        // —— 0) 原生+WebView Cookie（≈0.24 可用路径，与手动同源 Cookie）——
        if (!dryRun && lastToken.isNotBlank()) {
            val cookie0 = cookieHeaderFor(origin)
            if (cookie0.isNotBlank()) {
                appendLog("优先原生+Cookie下单 api=$lastApiBase cookieLen=${cookie0.length}")
                val native0 = HibtClient()
                val vTryList = linkedSetOf<String>()
                if (vEnc.isNotBlank()) vTryList.add(vEnc)
                if (lastV.isNotBlank()) vTryList.add(lastV)
                vTryList.add(vPlain)
                val apis = linkedSetOf(
                    lastApiBase.ifBlank { "https://api-ws.taichuwuji.com" },
                    "https://api-ws.taichuwuji.com",
                    "https://api.hibt0.com",
                )
                for (apiB in apis) {
                    for (vT in vTryList) {
                        val cfg0 = HibtSettings(
                            apiBase = apiB,
                            authToken = lastToken,
                            xAuthToken = lastToken,
                            vParam = vT,
                            vAutoTimestamp = false,
                            dryRun = false,
                            autoTrade = true,
                            origin = origin,
                            referer = referer,
                            cookieHeader = cookie0,
                            clientType = if (origin.contains("m.") || origin.contains("hibt1")) "h5" else "web",
                        )
                        val r0 = try {
                            native0.placeEventOrder(cfg0, symbol, directionUp, amount, unit)
                        } catch (e: Exception) {
                            HibtClient.OrderResult(false, "原生异常 ${e.message}", false)
                        }
                        appendLog("原生+Cookie ${if (r0.ok) "OK" else "FAIL"} $apiB ${r0.message.take(80)}")
                        if (r0.ok) {
                            val msg = "[原生+Cookie] ${r0.message}"
                            _ui.value = _ui.value.copy(lastPlaceMsg = msg, status = msg.take(100))
                            return PlaceOutcome(true, msg, dryRun = false)
                        }
                    }
                }
                appendLog("原生+Cookie未成交，再试平台UI点击")
            } else {
                appendLog("WebView Cookie 为空，跳过原生优先，走平台UI")
            }
        }

        // —— 1) WebView 平台 UI 下单（参数由官网处理）——
        val id = placeSeq.incrementAndGet()
        val deferred = CompletableDeferred<PlaceOutcome>()
        placeWait = deferred
        val originJs = origin.replace("'", "\\'")
        val refererJs = referer.replace("'", "\\'")
        val vEncJs = vEnc.replace("\\", "\\\\").replace("'", "\\'")
        val vPlainJs = vPlain.replace("'", "\\'")
        val js = """
            (function(){
              try {
                if (window.__caPlace) {
                  window.__caPlace({
                    id: $id,
                    direction: $dir,
                    amount: '$amountStr',
                    symbol: '$sym',
                    timeUnit: $unit,
                    dryRun: ${if (dryRun) "true" else "false"},
                    origin: '$originJs',
                    referer: '$refererJs',
                    vEnc: '$vEncJs',
                    vPlain: '$vPlainJs'
                  });
                } else {
                  CaHibt.onPlaceResult(JSON.stringify({
                    id: $id, ok: false, dryRun: false,
                    message: '脚本未注入，请打开 WebView 并登录合约页'
                  }));
                }
              } catch(e) {
                CaHibt.onPlaceResult(JSON.stringify({
                  id: $id, ok: false, dryRun: false, message: String(e)
                }));
              }
            })();
        """.trimIndent()
        main.post {
            wakeWebView(wv)
            injectHooks(wv, force = true)
            _ui.value = _ui.value.copy(status = "WebView 优先下单… origin=$origin")
            main.postDelayed({
                wakeWebView(webView ?: return@postDelayed)
                webView?.evaluateJavascript(js, null)
            }, 450)
        }
        val waitMs = (timeoutSec.coerceIn(10, 180) * 1000L).coerceAtMost(90_000L)
        val webResult = withTimeoutOrNull(waitMs) { deferred.await() }
        if (webResult != null) {
            if (webResult.ok) {
                _ui.value = _ui.value.copy(lastPlaceMsg = webResult.message, status = webResult.message.take(100))
                return webResult
            }
            // 405/跨域/失败 → 降级原生
            val softFail = webResult.message.contains("405") ||
                webResult.message.contains("跨域") ||
                webResult.message.contains("网络错误") ||
                webResult.message.contains("超时") ||
                webResult.message.contains("脚本未注入") ||
                !webResult.ok
            if (!softFail) {
                _ui.value = _ui.value.copy(lastPlaceMsg = webResult.message)
                return webResult
            }
            _ui.value = _ui.value.copy(status = "WebView失败，降级原生… ${webResult.message.take(40)}")
        } else {
            _ui.value = _ui.value.copy(status = "WebView 超时，降级原生 POST…")
        }

        if (dryRun) {
            val msg = webResult?.message
                ?: "DRY-RUN：未完成平台UI回调（不会真实下单）"
            appendLog(msg)
            return PlaceOutcome(true, msg, dryRun = true)
        }

        // 平台 UI 已成功则直接返回
        if (webResult != null && webResult.ok) {
            return webResult
        }

        val webMsg = webResult?.message.orEmpty()
        // 无 Cookie 的自建 XHR 常误报「登录失效」；手动同 WebView 正常 → 改走「Cookie+Token 原生」
        appendLog("UI/XHR未成交(${webMsg.take(60)})，改用原生+WebView Cookie（旧版可用路径）")
        val tokenNow = lastToken.ifBlank { token }
        if (tokenNow.isBlank()) {
            val msg = webResult?.message
                ?: "无 token：请 WebView 登录后点「手动更新 Token」"
            appendLog(msg)
            return PlaceOutcome(false, msg, dryRun = false)
        }
        val native = HibtClient()
        val vCandidates = linkedSetOf<String>()
        if (vEnc.isNotBlank()) vCandidates.add(vEnc)
        if (lastV.isNotBlank()) vCandidates.add(lastV.trim())
        vCandidates.add(vPlain)
        vCandidates.add(System.currentTimeMillis().toString())

        // API 候选：你日志里真实流量多为 api-ws.taichuwuji.com；api.hibt1.com 常 403
        val apiCandidates = linkedSetOf<String>()
        fun addApi(u: String) {
            val x = u.trim().trimEnd('/')
            if (x.startsWith("http") && !x.contains("://m.") && !x.contains("://www.")) {
                apiCandidates.add(x)
            }
        }
        addApi(lastApiBase)
        addApi("https://api-ws.taichuwuji.com")
        addApi("https://api.hibt0.com")

        var lastMsg = "原生降级无结果"
        for (apiBaseTry in apiCandidates) {
        for (vTry in vCandidates) {
            val cookie = cookieHeaderFor(origin)
            val isMobile = origin.contains("m.") || origin.contains("hibt1")
            val cfg = HibtSettings(
                apiBase = apiBaseTry,
                authToken = tokenNow,
                xAuthToken = tokenNow,
                vParam = vTry,
                vAutoTimestamp = false,
                dryRun = false,
                autoTrade = true,
                origin = origin,
                referer = referer,
                cookieHeader = cookie,
                clientType = if (isMobile) "h5" else "web",
            )
            appendLog("原生尝试 api=$apiBaseTry v=${vTry.take(10)}… cookie=${if (cookie.isBlank()) "无" else "有${cookie.length}字"}")
            _ui.value = _ui.value.copy(status = "原生POST v=${vTry.take(12)}… origin=$origin")
            val r = try {
                native.placeEventOrder(cfg, symbol, directionUp, amount, unit)
            } catch (e: Exception) {
                HibtClient.OrderResult(false, "原生异常: ${e.message}", dryRun = false)
            }
            lastMsg = "[原生降级] ${r.message}"
            if (r.ok) {
                appendLog(lastMsg)
                _ui.value = _ui.value.copy(lastPlaceMsg = lastMsg, status = lastMsg.take(100))
                return PlaceOutcome(true, lastMsg, dryRun = false)
            } else {
                appendLog(lastMsg.take(180))
            }
            // 参数错误则换下一个 v；405 也继续试
        } // vCandidates
        } // apiCandidates
        val fail = PlaceOutcome(false, lastMsg, dryRun = false)
        appendLog(fail.message)
        _ui.value = _ui.value.copy(lastPlaceMsg = fail.message, status = fail.message.take(100))
        return fail
    }

    fun applyNativeSettingsSnapshot(): HibtSettings? {
        if (lastToken.isBlank()) return null
        return HibtSettings(
            apiBase = lastApiBase,
            authToken = lastToken,
            xAuthToken = lastToken,
            vParam = lastV,
            vAutoTimestamp = false,
        )
    }

    fun wakeIfNeeded(wv: WebView) = wakeWebView(wv)

    /** 从 WebView CookieManager 取与手动操作一致的 Cookie，供原生下单 */
    fun cookieHeaderFor(url: String = lastOrigin.ifBlank { "https://m.hibt1.com" }): String {
        return try {
            val u = when {
                url.startsWith("http") -> url
                else -> "https://m.hibt1.com"
            }
            android.webkit.CookieManager.getInstance().getCookie(u)?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun snapshotForNativePlace(): Triple<String, String, String> {
        // token, api, cookie
        return Triple(
            lastToken.trim(),
            lastApiBase.ifBlank { "https://api-ws.taichuwuji.com" },
            cookieHeaderFor(lastOrigin.ifBlank { "https://m.hibt1.com" }),
        )
    }

    private fun wakeWebView(wv: WebView) {
        try {
            wv.resumeTimers()
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            wv.onResume()
        } catch (_: Exception) {
        }
    }

    @Volatile private var lastInjectAt = 0L
    @Volatile private var injectEver = false
    private fun injectHooks(view: WebView, force: Boolean = false) {
        val now = System.currentTimeMillis()
        // 非 force：首次后至少 15s 才再注，避免 SPA 反复 onPageFinished 刷日志/干扰页面
        if (!force && injectEver && now - lastInjectAt < 15_000) return
        if (!force && now - lastInjectAt < 3_000) return
        lastInjectAt = now
        injectEver = true
        view.evaluateJavascript(INJECT_JS, null)
    }

    private class Bridge {
        @JavascriptInterface
        fun onSession(json: String) {
            try {
                val o = JsonParser.parseString(json).asJsonObject
                fun s(k: String) =
                    if (o.has(k) && o.get(k).isJsonPrimitive) o.get(k).asString.trim() else ""
                val token = s("token").ifBlank { s("xAuthToken") }
                val v = s("v")
                val api = s("apiBase").ifBlank { lastApiBase }
                val origin = s("origin")
                val referer = s("referer")
                if (token.isNotBlank()) lastToken = token
                if (v.isNotBlank()) {
                    lastV = v
                    if (v.all { it.isDigit() }) lastVPlain = v
                    else lastVEnc = v
                    _ui.value = _ui.value.copy(hasV = true, lastVAt = System.currentTimeMillis())
                }
                // 过滤前端域名，保留真正 API
                if (api.startsWith("http")) {
                    val host = try { java.net.URI(api).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
                    val badFront = host.startsWith("m.") || host.startsWith("www.")
                    val looksApi = host.startsWith("api") || host.contains("api-") || host.contains("taichuwuji")
                    if (!badFront && looksApi) {
                        lastApiBase = api.trimEnd('/')
                    }
                }
                if (origin.startsWith("http")) {
                    lastOrigin = origin.trimEnd('/')
                    lastReferer = referer.ifBlank { lastOrigin + "/" }
                } else if (referer.startsWith("http")) {
                    lastReferer = referer
                    try {
                        val u = java.net.URI(referer)
                        lastOrigin = "${u.scheme}://${u.host}"
                    } catch (_: Exception) {}
                }
                val preview = if (lastToken.length > 12) lastToken.take(8) + "…" + lastToken.takeLast(4) else lastToken
                // 仅 token/api 变化时写回，避免刷屏
                if (token.isNotBlank()) {
                    val apiOut = lastApiBase.ifBlank { "https://api-ws.taichuwuji.com" }
                    if (token != lastSyncedToken || apiOut != lastSyncedApi) {
                        try {
                            settingsSync?.invoke(
                                lastToken,
                                apiOut,
                                lastOrigin.ifBlank { "https://m.hibt1.com" },
                            )
                            lastSyncedToken = token
                            lastSyncedApi = apiOut
                            appendLog("token 已写回原生 · api=$apiOut · origin=$lastOrigin · preview=$preview")
                        } catch (e: Exception) {
                            appendLog("写回原生 token 失败: ${e.message}")
                        }
                    }
                }
                _ui.value = _ui.value.copy(
                    ready = lastToken.isNotBlank(),
                    tokenPreview = preview,
                    nativeTokenSyncedAt = if (token.isNotBlank()) System.currentTimeMillis() else _ui.value.nativeTokenSyncedAt,
                    status = if (lastToken.isNotBlank()) {
                        "会话已捕获并已写回原生 token" + (if (lastV.isNotBlank()) " · 有 v" else " · 等待页面请求以捕获 v")
                    } else "未捕获 token，请在合约页点订单/资产",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(status = "会话解析失败: ${e.message}")
            }
        }

        @JavascriptInterface
        fun onAccount(json: String) {
            try {
                val o = JsonParser.parseString(json).asJsonObject
                val bal = if (o.has("balance") && !o.get("balance").isJsonNull) o.get("balance").asString else "—"
                val pos = if (o.has("positions") && !o.get("positions").isJsonNull) o.get("positions").asString else "—"
                _ui.value = _ui.value.copy(
                    balance = bal.ifBlank { "—" },
                    positions = pos.ifBlank { "—" },
                    status = "账户已更新",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(status = "账户解析失败: ${e.message}")
            }
        }

        @JavascriptInterface
        fun onPlaceResult(json: String) {
            try {
                val o = JsonParser.parseString(json).asJsonObject
                val ok = o.has("ok") && o.get("ok").asBoolean
                val dry = o.has("dryRun") && o.get("dryRun").asBoolean
                val msg = if (o.has("message")) o.get("message").asString else json.take(200)
                _ui.value = _ui.value.copy(lastPlaceMsg = msg, status = msg.take(80))
                appendLog((if (ok) "下单OK " else "下单FAIL ") + (if (dry) "[DRY] " else "") + msg)
                placeWait?.complete(PlaceOutcome(ok, msg, dry))
                placeWait = null
            } catch (e: Exception) {
                appendLog("下单结果解析失败: ${e.message}")
                placeWait?.complete(PlaceOutcome(false, e.message ?: "parse error", false))
                placeWait = null
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            appendLog("[JS] $msg")
        }
    }

    /**
     * 注入：默认不钩 fetch/XHR（避免账户接口一直 loading）；会话从 storage/cookie 读；下单时可选短时开钩。
     */
    private val INJECT_JS = """
    (function(){
      if (window.__CA_HIBT_INJECTED__) {
        try { if (window.__caDisableNetHooks) window.__caDisableNetHooks(); } catch(e){}
        try { if (window.__caScanSession) window.__caScanSession(); } catch(e){}
        return;
      }
      window.__CA_HIBT_INJECTED__ = 1;
      try { if (window.__caDisableNetHooks) window.__caDisableNetHooks(); } catch(e){}
      var st = { token: '', v: '', apiBase: 'https://api.hibt0.com', origin: '', referer: '', vEnc: '', vPlain: '' };
      try { st.origin = location.origin || ''; st.referer = location.href || (st.origin + '/'); } catch(e){}
      function T(x){ return x==null?'':String(x).trim(); }
      function emitSession(){
        try {
          var origin = st.origin || '';
          try { if (!origin) origin = location.origin || ''; } catch(e){}
          CaHibt.onSession(JSON.stringify({
            token: st.token, v: st.v, apiBase: st.apiBase, xAuthToken: st.token,
            origin: origin, referer: st.referer || (origin ? origin + '/' : '')
          }));
        } catch(e){}
      }
      function pickV(url){
        try {
          var u = new URL(url, location.href);
          var v = u.searchParams.get('v');
          return v ? decodeURIComponent(v) : '';
        } catch(e){ return ''; }
      }
      function pickApi(url){
        try {
          var u = new URL(url, location.href);
          var host = (u.hostname||'').toLowerCase();
          // 不要把前端 m.hibt1.com 当成 API；只要真正的 api 主机
          if (/^m\./.test(host) || /www\./.test(host)) return st.apiBase;
          if (/^(api[-.]|api-ws)/i.test(host) || /taichuwuji|hotscoin|hibt0|hibt1/i.test(host) && /api/i.test(host)) {
            return u.protocol+'//'+u.host;
          }
        } catch(e){}
        return st.apiBase;
      }
      function absorb(url, headers){
        try {
          var h = headers || {};
          var tok = T(h['x-auth-token'] || h['X-Auth-Token'] || h['authorization'] || h['Authorization'] || '');
          tok = tok.replace(/^Bearer\s+/i,'');
          if (tok.length > 20) { st.token = tok; try{ window.__CA_LAST_TOKEN = tok; }catch(e){} }
          var vv = pickV(url);
          if (vv) {
            st.v = vv;
            if (/^\d{10,}$/.test(vv)) st.vPlain = vv; else st.vEnc = vv;
            try{ window.__CA_LAST_V = vv; }catch(e){}
          }
          var api = pickApi(url);
          if (api && api !== st.apiBase && /api/i.test(api)) {
            st.apiBase = api;
            try{ window.__CA_LAST_API = st.apiBase; }catch(e){}
          }
          try {
            st.origin = location.origin || st.origin;
            st.referer = location.href || st.referer;
          } catch(e){}
          // 仅当 token/v/api 有实质变化时上报，避免刷屏写回原生
          var sig = (st.token||'')+'|'+(st.v||'')+'|'+(st.apiBase||'');
          if ((st.token || st.v) && sig !== window.__CA_LAST_SIG) {
            window.__CA_LAST_SIG = sig;
            emitSession();
          }
        } catch(e){}
      }
      function maybePlaceResponse(url, status, bodyText, fromXhrFallback){
        try {
          var p = window.__caPendingPlace;
          if (!p || p.done) return;
          var u = String(url||'');
          if (!/place/i.test(u)) return;
          var t = bodyText||'';
          var ok = status>=200 && status<300 && t.indexOf('参数错误')<0 && t.indexOf('"code":500')<0 && t.indexOf('"code":401')<0 && t.indexOf('未登录')<0 && t.indexOf('登录失效')<0 && t.indexOf('"code":4000')<0;
          try {
            var j = JSON.parse(t);
            if (j.code===0 || j.code===200 || j.success===true) ok = true;
            if (j.code && j.code!==0 && j.code!==200) ok = false;
          } catch(e){}
          // 自建 XHR 失败不终结 UI 等待（避免误报登录失效打断后续原生）
          if (fromXhrFallback && !ok) return;
          if (p.mode==='ui' && !ok && (t.indexOf('4000')>=0 || t.indexOf('登录失效')>=0)) {
            // 可能是页面误请求；不立即 done，留给超时后走原生 Cookie 路径
            CaHibt.onLog('捕获到登录失效响应，保留会话改走原生Cookie路径');
            return;
          }
          p.done = true;
          CaHibt.onPlaceResult(JSON.stringify({
            id: p.id, ok: !!ok, dryRun: !!p.dry,
            message: (ok?'平台下单成功 ':'平台下单失败 ')+status+' '+(t||'').slice(0,140)
          }));
        } catch(e){}
      }
      // ========== 默认不钩 fetch/XHR ==========
      // 永久钩子会导致部分 SPA 账户/行情接口一直 loading（UI 可点但数据不来）
      // 会话从 storage/cookie 读取；仅下单时短时启用网络钩子
      window.__caEnableNetHooks = function(){
        if (window.__CA_FETCH_OK) return 'already';
        var OF = window.fetch;
        if (typeof OF === 'function') {
          window.__CA_FETCH_OK = 1;
          window.__CA_OF = OF;
          window.fetch = function(input, init){
            var url = '';
            try {
              url = typeof input === 'string' ? input : (input && input.url) || '';
              var hdr = {};
              try {
                if (init && init.headers) {
                  if (typeof Headers !== 'undefined' && init.headers instanceof Headers)
                    init.headers.forEach(function(v,k){ hdr[k]=v; });
                  else if (init.headers.forEach) init.headers.forEach(function(v,k){ hdr[k]=v; });
                  else Object.assign(hdr, init.headers);
                }
              } catch(e){}
              try { absorb(url, hdr); } catch(e){}
            } catch(e){}
            var p = OF.apply(this, arguments);
            try {
              var pend = window.__caPendingPlace;
              if (pend && !pend.done && /place/i.test(url||'')) {
                p = p.then(function(resp){
                  try {
                    resp.clone().text().then(function(t){
                      maybePlaceResponse(url, resp.status, t, false);
                    }).catch(function(){});
                  } catch(e){}
                  return resp;
                });
              }
            } catch(e){}
            return p;
          };
        }
        if (window.XMLHttpRequest && XMLHttpRequest.prototype && !window.__CA_XHR_OK) {
          window.__CA_XHR_OK = 1;
          var OO = XMLHttpRequest.prototype.open, OS = XMLHttpRequest.prototype.setRequestHeader, OE = XMLHttpRequest.prototype.send;
          window.__CA_XHR = { OO: OO, OS: OS, OE: OE };
          XMLHttpRequest.prototype.open = function(m,u){
            try { this.__caU = u; this.__caH = {}; } catch(e){}
            return OO.apply(this, arguments);
          };
          XMLHttpRequest.prototype.setRequestHeader = function(n,v){
            try { if (!this.__caH) this.__caH = {}; this.__caH[n] = v; } catch(e){}
            return OS.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function(){
            try { absorb(this.__caU||'', this.__caH||{}); } catch(e){}
            try {
              var pend = window.__caPendingPlace;
              var url = this.__caU||'';
              if (pend && !pend.done && /place/i.test(url)) {
                var xhr = this;
                xhr.addEventListener('load', function(){
                  try { maybePlaceResponse(url, xhr.status, xhr.responseText||'', false); } catch(e){}
                });
              }
            } catch(e){}
            return OE.apply(this, arguments);
          };
        }
        return 'hooks-on';
      };
      window.__caDisableNetHooks = function(){
        try {
          if (window.__CA_OF) { window.fetch = window.__CA_OF; window.__CA_FETCH_OK = 0; }
          if (window.__CA_XHR) {
            XMLHttpRequest.prototype.open = window.__CA_XHR.OO;
            XMLHttpRequest.prototype.setRequestHeader = window.__CA_XHR.OS;
            XMLHttpRequest.prototype.send = window.__CA_XHR.OE;
            window.__CA_XHR_OK = 0;
          }
        } catch(e){}
        return 'hooks-off';
      };
      // 无网络钩子时从本地存储/Cookie 抓 token（不拦截任何请求）
      window.__caScanSession = function(){
        try {
          var keys = ['token','x-auth-token','xAuthToken','authToken','accessToken','bget_token','Authorization'];
          var tok = '';
          for (var i=0;i<keys.length;i++){
            try { var a = localStorage.getItem(keys[i]); if (a && a.length>20) { tok=a; break; } } catch(e){}
            try { var b = sessionStorage.getItem(keys[i]); if (b && b.length>20) { tok=b; break; } } catch(e){}
          }
          if (!tok) {
            try {
              var m = document.cookie.match(/(?:^|;\s*)(?:token|bget_token|x-auth-token)=([^;]+)/i);
              if (m) tok = decodeURIComponent(m[1]);
            } catch(e){}
          }
          tok = (tok||'').replace(/^Bearer\s+/i,'');
          if (tok.length > 20) {
            st.token = tok;
            try { window.__CA_LAST_TOKEN = tok; } catch(e){}
          }
          try {
            st.origin = location.origin || st.origin;
            st.referer = location.href || st.referer;
          } catch(e){}
          if (st.token) emitSession();
          return st.token ? ('token:'+st.token.slice(0,8)) : 'no-token';
        } catch(e){ return 'err'; }
      };
      // 启动时只扫存储，不装网络钩子
      try { window.__caScanSession(); } catch(e){}
      setTimeout(function(){ try { window.__caScanSession(); } catch(e){} }, 1500);
      function authHeaders(opt){
        opt = opt || {};
        var origin = opt.origin || st.origin || '';
        try { if (!origin) origin = location.origin || 'https://hibt.com'; } catch(e){ origin = origin || 'https://hibt.com'; }
        var referer = opt.referer || st.referer || (origin + '/');
        var h = {
          'accept': 'application/json, text/plain, */*',
          'content-type': 'application/x-www-form-urlencoded',
          'client-type': (location.hostname||'').indexOf('m.')===0 ? 'h5' : 'web',
          'platform': (location.hostname||'').indexOf('m.')===0 ? 'h5' : 'PC',
          'hc-platform': (location.hostname||'').indexOf('m.')===0 ? 'h5' : 'web',
          'future_source': '1',
          'lang': 'zh_CN',
          'hc-language': 'zh_CN',
          'Origin': origin,
          'origin': origin,
          'Referer': referer,
          'referer': referer
        };
        if (st.token) {
          h['x-auth-token'] = st.token;
          h['Authorization'] = st.token;
        }
        return h;
      }
      function findAmount(obj, d){
        if (!obj || d>6) return '';
        if (typeof obj === 'object') {
          if (obj.amount != null && obj.amount !== '') return String(obj.amount);
          for (var k in obj) {
            if (!Object.prototype.hasOwnProperty.call(obj,k)) continue;
            var r = findAmount(obj[k], d+1); if (r) return r;
          }
        }
        return '';
      }
      function countList(obj){
        try {
          var list = (obj && obj.data && (obj.data.list||obj.data.records)) || (obj && obj.list) || [];
          if (Array.isArray(list)) return list.length;
        } catch(e){}
        return null;
      }
      window.__caRefreshAccount = function(){
        var base = st.apiBase || 'https://api.hibt0.com';
        var vq = st.v ? ('&v=' + encodeURIComponent(st.v)) : ('&v=' + Date.now());
        var balUrl = base + '/rest/c/future/u/user/balance?langCode=zh_CN' + vq;
        fetch(balUrl, { method:'GET', headers: authHeaders(), credentials:'include' })
          .then(function(r){ return r.text(); })
          .then(function(t){
            var bal = '—', pos = '—';
            try {
              var j = JSON.parse(t);
              bal = findAmount(j) || '—';
            } catch(e){}
            var listUrl = base + '/event/event-order/list' + (st.v ? ('?v='+encodeURIComponent(st.v)) : '');
            return fetch(listUrl, {
              method:'POST', headers: authHeaders(), credentials:'include',
              body: 'status=0&pageNo=1&pageSize=100&langCode=zh_CN'
            }).then(function(r){ return r.text(); }).then(function(t2){
              try {
                var j2 = JSON.parse(t2);
                var n = countList(j2);
                pos = (n==null) ? '—' : ('开仓中 '+n+' 笔');
              } catch(e){}
              CaHibt.onAccount(JSON.stringify({ balance: bal, positions: pos }));
            }).catch(function(){
              CaHibt.onAccount(JSON.stringify({ balance: bal, positions: '—' }));
            });
          }).catch(function(e){
            if (!window.__CA_BAL_ERR) {
              window.__CA_BAL_ERR = 1;
              CaHibt.onLog('余额请求失败(可忽略CORS): '+String(e).slice(0,60));
            }
          });
      };
      window.__caPlace = function(opt){
        opt = opt || {};
        try { if (window.__caEnableNetHooks) window.__caEnableNetHooks(); } catch(e){}
        try { if (window.__caScanSession) window.__caScanSession(); } catch(e){}
        var dry = !!opt.dryRun;
        var amount = String(opt.amount||'3');
        var direction = Number(opt.direction==null?1:opt.direction);
        var timeUnit = String(opt.timeUnit||5);
        var symbol = String(opt.symbol||'btc_usdt');
        var pendingId = opt.id;

        function finish(ok, msg, isDry){
          try { if (window.__caDisableNetHooks) window.__caDisableNetHooks(); } catch(e){}
          CaHibt.onPlaceResult(JSON.stringify({
            id: pendingId, ok: !!ok, dryRun: !!isDry, message: msg
          }));
        }

        // 监听官网自己发出的 place 响应（不替平台造参）
        window.__caPendingPlace = {
          id: pendingId,
          at: Date.now(),
          dry: dry,
          done: false,
          mode: 'ui'
        };

        function textOf(el){
          try { return (el.innerText||el.textContent||el.value||'').trim(); } catch(e){ return ''; }
        }
        function clickEl(el){
          try {
            el.focus();
            el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));
            el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
            el.click();
            return true;
          } catch(e){ return false; }
        }
        function setInput(el, val){
          try {
            el.focus();
            var proto = window.HTMLInputElement && HTMLInputElement.prototype;
            var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && desc.set) desc.set.call(el, val); else el.value = val;
            el.dispatchEvent(new Event('input',{bubbles:true}));
            el.dispatchEvent(new Event('change',{bubbles:true}));
            return true;
          } catch(e){ return false; }
        }
        function allClickable(){
          return Array.prototype.slice.call(document.querySelectorAll('button,a,[role="button"],div,span'));
        }
        function findByText(cands, words){
          for (var i=0;i<cands.length;i++){
            var t = textOf(cands[i]);
            if (!t || t.length>20) continue;
            for (var j=0;j<words.length;j++){
              if (t === words[j] || t.indexOf(words[j])>=0) return cands[i];
            }
          }
          return null;
        }
        function findAmountInput(){
          var inputs = Array.prototype.slice.call(document.querySelectorAll('input'));
          for (var i=0;i<inputs.length;i++){
            var el = inputs[i];
            var ph = (el.placeholder||'') + (el.name||'') + (el.id||'') + (el.getAttribute('aria-label')||'');
            var t = ph.toLowerCase();
            if (el.type==='number' || /amount|金额|数量|quota|money/i.test(t) || el.inputMode==='decimal') return el;
          }
          // 可见的数字框
          for (var k=0;k<inputs.length;k++){
            if (inputs[k].offsetParent!==null && (inputs[k].type==='text'||inputs[k].type==='number'||!inputs[k].type)) return inputs[k];
          }
          return null;
        }
        function selectTimeUnit(unit){
          var cands = allClickable();
          var labels = [unit+'m', unit+'分钟', unit+'分', String(unit)];
          if (unit==='60' || unit==='1h') labels = labels.concat(['1h','1H','60m','60分钟','1小时']);
          var el = findByText(cands, labels);
          if (el) { clickEl(el); return true; }
          return false;
        }
        function selectDirection(dir){
          var cands = allClickable();
          var upWords = dir===1 ? ['买涨','看涨','涨','Up','UP','看多','做多'] : ['买跌','看跌','跌','Down','DOWN','看空','做空'];
          var el = findByText(cands, upWords);
          if (el) { clickEl(el); return textOf(el)||'方向'; }
          return null;
        }
        function findSubmit(dir){
          var cands = allClickable();
          var words = dir===1
            ? ['买涨','确认买涨','看涨','下单','确认']
            : ['买跌','确认买跌','看跌','下单','确认'];
          return findByText(cands, words);
        }

        // —— 主路径：驱动平台 UI，让官网自己发 place ——
        try {
          var steps = [];
          var amtEl = findAmountInput();
          if (amtEl) { setInput(amtEl, amount); steps.push('金额='+amount); }
          else steps.push('未找到金额框');

          if (selectTimeUnit(timeUnit)) steps.push('周期='+timeUnit);
          else steps.push('未点到周期'+timeUnit);

          var dirLabel = selectDirection(direction);
          if (dirLabel) steps.push('方向='+dirLabel);
          else steps.push('未点到方向按钮');

          var sub = findSubmit(direction);
          if (dry) {
            finish(true, 'DRY-RUN 平台UI '+steps.join('; ')+(sub?'; 将点提交「'+textOf(sub)+'」':'；未找到提交按钮'), true);
            return;
          }
          if (!sub) {
            // 找不到提交按钮：不走无Cookie的XHR（易误报登录失效），交还原生Cookie路径
            finish(false, '未找到提交按钮，交还原生Cookie下单。步骤:'+steps.join('; '));
            return;
          }
          clickEl(sub);
          steps.push('已点「'+textOf(sub)+'」');
          CaHibt.onLog('已触发平台下单UI: '+steps.join('; '));
          // 等待官网 place 响应（由 fetch/XHR 钩子识别）
          // 短延迟：检测二次确认弹窗（可提示用户，不强行代点）
          setTimeout(function(){
            try {
              var cands = allClickable();
              var confWords = ['确认下单','二次确认','确定下单','确认提交','我知道了','确认','确定'];
              var conf = null, confText = '';
              for (var i=0;i<cands.length;i++){
                var t = textOf(cands[i]);
                if (!t || t.length>24) continue;
                // 弹层内按钮通常较短
                for (var j=0;j<confWords.length;j++){
                  if (t === confWords[j] || (t.indexOf(confWords[j])>=0 && t.length<=12)) {
                    // 排除主下单按钮自身已点过的「买涨」
                    if (/买涨|买跌|看涨|看跌/.test(t)) continue;
                    conf = cands[i]; confText = t; break;
                  }
                }
                if (conf) break;
              }
              // 也检查常见 mask/dialog
              var dialogs = document.querySelectorAll('[class*="dialog"],[class*="modal"],[class*="confirm"],[class*="popup"],[class*="Dialog"],[class*="Modal"]');
              var hasDialog = dialogs && dialogs.length>0;
              if (conf || hasDialog) {
                var p0 = window.__caPendingPlace;
                if (p0 && !p0.done) {
                  p0.confirmHint = true;
                  CaHibt.onLog('检测到二次确认: '+(confText||'弹层'));
                }
              }
            } catch(e){}
          }, 800);

          setTimeout(function(){
            var p = window.__caPendingPlace;
            if (p && !p.done && p.id===pendingId) {
              var hint = p.confirmHint
                ? '【二次确认】请手动点确认；将尝试原生+Cookie下单'
                : 'UI未捕获成功响应，将尝试原生+Cookie下单';
              finish(false, hint+' 步骤:'+steps.join('; '));
              p.done = true;
            }
          }, 12000);
          return;
        } catch(e) {
          CaHibt.onLog('UI下单异常 '+e);
          finish(false, 'UI异常交还原生: '+String(e));
        }
      };

      // 降级：自建 POST（仅当平台 UI 找不到时）
      window.__caPlaceXhrFallback = function(opt){
        opt = opt || {};
        var dry = !!opt.dryRun;
        var payload = {
          amount: String(opt.amount||'3'),
          direction: String(opt.direction==null?1:opt.direction),
          symbol: String(opt.symbol||'btc_usdt'),
          timeUnit: String(opt.timeUnit||5),
          langCode: 'zh_CN'
        };
        var origin = opt.origin || st.origin || '';
        try { if (!origin) origin = location.origin || 'https://hibt.com'; } catch(e){ origin = 'https://hibt.com'; }
        var referer = opt.referer || st.referer || (origin + '/');
        if (dry) {
          CaHibt.onPlaceResult(JSON.stringify({
            id: opt.id, ok: true, dryRun: true,
            message: 'DRY-RUN 降级XHR '+JSON.stringify(payload)+' origin='+origin
          }));
          return;
        }
        if (!st.token) {
          CaHibt.onPlaceResult(JSON.stringify({ id:opt.id, ok:false, dryRun:false, message:'UI失败且无token，无法XHR降级' }));
          return;
        }
        var bases = ['https://api.hibt0.com'];
        if (st.apiBase && /api/i.test(st.apiBase) && bases.indexOf(st.apiBase)<0) bases.push(st.apiBase);
        var paths = ['/option/option-order/place', '/event/event-order/place'];
        var body = Object.keys(payload).map(function(k){ return encodeURIComponent(k)+'='+encodeURIComponent(payload[k]); }).join('&');
        var vList = [];
        function pushV(x){ if (x && vList.indexOf(x)<0) vList.push(x); }
        pushV(opt.vEnc); pushV(st.vEnc); pushV(st.v);
        pushV(opt.vPlain); pushV(st.vPlain); pushV(String(Date.now()));
        var hdr = authHeaders({ origin: origin, referer: referer });
        function finish(ok, msg){
          CaHibt.onPlaceResult(JSON.stringify({ id: opt.id, ok: !!ok, dryRun: false, message: '[XHR降级] '+msg }));
        }
        var bi=0,pi=0,vi=0;
        function next(){
          if (vi>=vList.length){ vi=0; pi++; }
          if (pi>=paths.length){ pi=0; bi++; }
          if (bi>=bases.length){ finish(false,'XHR全失败 origin='+origin); return; }
          var base=bases[bi], path=paths[pi], v=vList[vi];
          var url=base+path+(v?('?v='+encodeURIComponent(v)):'');
          try {
            var xhr=new XMLHttpRequest();
            xhr.open('POST',url,true);
            xhr.withCredentials=true;
            Object.keys(hdr).forEach(function(k){ try{ xhr.setRequestHeader(k,hdr[k]); }catch(e){} });
            xhr.timeout=20000;
            xhr.onreadystatechange=function(){
              if (xhr.readyState!==4) return;
              var code=xhr.status, t=xhr.responseText||'';
              var ok=code>=200&&code<300&&t.indexOf('参数错误')<0&&t.indexOf('"code":500')<0&&t.indexOf('"code":401')<0;
              try{ var j=JSON.parse(t); if(j.code===0||j.success===true)ok=true; if(j.code&&j.code!==0&&j.code!==200)ok=false; }catch(e){}
              if(ok){ finish(true, code+' '+path); return; }
              if(t.indexOf('参数')>=0){ vi++; next(); return; }
              vi++; next();
            };
            xhr.ontimeout=function(){ vi++; next(); };
            xhr.onerror=function(){ vi++; next(); };
            xhr.send(body);
          } catch(e){ vi++; next(); }
        }
        next();
      };

      try { CaHibt.onLog('注入完成，请浏览订单/资产以捕获 token/v'); } catch(e){}
      // 不自动刷余额（易 CORS Failed to fetch 刷屏）
    })();
    """.trimIndent()
}
