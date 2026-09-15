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
    @Volatile private var lastToken: String = ""
    @Volatile private var lastV: String = ""
    @Volatile private var lastApiBase: String = "https://api.hibt0.com"
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
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // keep default WebView UA
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            wv.addJavascriptInterface(Bridge(), "CaHibt")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (looksLikeOrderPage(url)) {
                        rememberOrderPage(url)
                    }
                    val tip = if (looksLikeOrderPage(url)) "合约下单页已锁定" else "页面已加载（请进入事件合约下单页）"
                    _ui.value = _ui.value.copy(pageUrl = url, loaded = true, status = tip)
                    injectHooks(view)
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

    fun clearSession(context: Context) {
        main.post {
            lastToken = ""
            lastV = ""
            lastVEnc = ""
            lastVPlain = ""
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
            injectHooks(wv)
            appendLog("手动更新 token：重新注入并触发页面请求…")
            // 触发账户接口 + 强制把当前 st 推给原生
            val js = """
            (function(){
              try {
                if (window.__caRefreshAccount) window.__caRefreshAccount();
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

        // —— 1) WebView 平台 UI 下单（优先，参数由官网处理）——
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
                    dryRun: false,
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
            injectHooks(wv)
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

        // 原生降级前再刷一次 token，降低「登录失效」
        appendLog("准备原生降级：先手动刷新 WebView token…")
        forceRefreshToken()
        kotlinx.coroutines.delay(1_200)

        // —— 2) 原生降级：加密 v → 明文时间戳 v，均带 Origin ——
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

        // API 候选：解析到的 + 按 origin 推断
        val apiCandidates = linkedSetOf<String>()
        apiCandidates.add(lastApiBase.ifBlank { "https://api.hibt0.com" })
        apiCandidates.add("https://api.hibt0.com")
        try {
            val host = java.net.URI(lastOrigin).host ?: ""
            if (host.contains("hibt1")) {
                apiCandidates.add("https://api.hibt1.com")
                apiCandidates.add("https://api.hibt0.com")
            }
        } catch (_: Exception) {}

        var lastMsg = "原生降级无结果"
        for (apiBaseTry in apiCandidates) {
        for (vTry in vCandidates) {
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
            )
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

    private fun injectHooks(view: WebView) {

        val script = INJECT_JS
        view.evaluateJavascript(script, null)
        // 再延迟一次，覆盖晚加载的 bundle
        main.postDelayed({
            webView?.evaluateJavascript(script, null)
            webView?.evaluateJavascript("window.__caRefreshAccount && window.__caRefreshAccount()", null)
        }, 1500)
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
                if (api.startsWith("http")) lastApiBase = api.trimEnd('/')
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
                // 覆盖写回原生 token / api，避免 WebView 登录后还要手填
                if (token.isNotBlank()) {
                    try {
                        settingsSync?.invoke(
                            lastToken,
                            lastApiBase.ifBlank { "https://api.hibt0.com" },
                            lastOrigin.ifBlank { "https://hibt.com" },
                        )
                        appendLog("token 已写回原生 · api=$lastApiBase · origin=$lastOrigin · preview=$preview")
                    } catch (e: Exception) {
                        appendLog("写回原生 token 失败: ${e.message}")
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
     * 注入：钩 fetch/XHR 抓会话；__caPlace 优先点平台UI下单（官网自处理v），失败再XHR/原生降级。
     */
    private val INJECT_JS = """
    (function(){
      if (window.__CA_HIBT_INJECTED__) {
        if (window.CaHibt) CaHibt.onLog('脚本已存在');
        return;
      }
      window.__CA_HIBT_INJECTED__ = 1;
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
          if (/hibt|bget|taichuwuji|api/i.test(u.hostname)) return u.protocol+'//'+u.host;
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
          st.apiBase = pickApi(url);
          try{ window.__CA_LAST_API = st.apiBase; }catch(e){}
          try {
            st.origin = location.origin || st.origin;
            st.referer = location.href || st.referer;
          } catch(e){}
          if (st.token || st.v) emitSession();
        } catch(e){}
      }
      function maybePlaceResponse(url, status, bodyText){
        try {
          var p = window.__caPendingPlace;
          if (!p || p.done) return;
          var u = String(url||'');
          if (!/place|event-order|option-order/i.test(u)) return;
          if (!/place/i.test(u) && status) { /* still allow event-order place */ }
          if (!/place/i.test(u)) return;
          var t = bodyText||'';
          var ok = status>=200 && status<300 && t.indexOf('参数错误')<0 && t.indexOf('"code":500')<0 && t.indexOf('"code":401')<0 && t.indexOf('未登录')<0;
          try {
            var j = JSON.parse(t);
            if (j.code===0 || j.code===200 || j.success===true) ok = true;
            if (j.code && j.code!==0 && j.code!==200) ok = false;
          } catch(e){}
          p.done = true;
          CaHibt.onPlaceResult(JSON.stringify({
            id: p.id, ok: !!ok, dryRun: !!p.dry,
            message: (ok?'平台下单成功 ':'平台下单失败 ')+status+' '+(t||'').slice(0,140)
          }));
        } catch(e){}
      }
      var OF = window.fetch;
      if (typeof OF === 'function') {
        window.fetch = function(){
          var req = arguments[0], init = arguments[1] || {};
          var url = '';
          try {
            url = typeof req === 'string' ? req : (req && req.url) || '';
            var hdr = {};
            try {
              if (init.headers) {
                if (init.headers.forEach) init.headers.forEach(function(v,k){ hdr[k]=v; });
                else Object.assign(hdr, init.headers);
              }
              if (req && req.headers && req.headers.forEach) req.headers.forEach(function(v,k){ hdr[k]=v; });
            } catch(e){}
            absorb(url, hdr);
          } catch(e){}
          return OF.apply(this, arguments).then(function(resp){
            try {
              var c = resp.clone();
              c.text().then(function(t){ maybePlaceResponse(url, resp.status, t); }).catch(function(){});
            } catch(e){}
            return resp;
          });
        };
      }
      if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
        var OO = XMLHttpRequest.prototype.open, OS = XMLHttpRequest.prototype.setRequestHeader, OE = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(m,u){ this.__caU=u; this.__caH={}; return OO.apply(this, arguments); };
        XMLHttpRequest.prototype.setRequestHeader = function(n,v){ try{ this.__caH[n]=v; }catch(e){} return OS.apply(this, arguments); };
        XMLHttpRequest.prototype.send = function(){
          try{ absorb(this.__caU||'', this.__caH||{}); }catch(e){}
          try {
            var xhr = this;
            var url = this.__caU||'';
            xhr.addEventListener('load', function(){
              try { maybePlaceResponse(url, xhr.status, xhr.responseText||''); } catch(e){}
            });
          } catch(e){}
          return OE.apply(this, arguments);
        };
      }
      function authHeaders(opt){
        opt = opt || {};
        var origin = opt.origin || st.origin || '';
        try { if (!origin) origin = location.origin || 'https://hibt.com'; } catch(e){ origin = origin || 'https://hibt.com'; }
        var referer = opt.referer || st.referer || (origin + '/');
        var h = {
          'accept': 'application/json, text/plain, */*',
          'content-type': 'application/x-www-form-urlencoded',
          'client-type': 'web',
          'platform': 'PC',
          'hc-platform': 'web',
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
            CaHibt.onLog('余额请求失败 '+e);
          });
      };
      window.__caPlace = function(opt){
        opt = opt || {};
        var dry = !!opt.dryRun;
        var amount = String(opt.amount||'3');
        var direction = Number(opt.direction==null?1:opt.direction);
        var timeUnit = String(opt.timeUnit||5);
        var symbol = String(opt.symbol||'btc_usdt');
        var pendingId = opt.id;

        function finish(ok, msg, isDry){
          CaHibt.onPlaceResult(JSON.stringify({
            id: pendingId, ok: !!ok, dryRun: !!isDry, message: msg
          }));
        }

        // 监听官网自己发出的 place 响应（不替平台造参）
        window.__caPendingPlace = {
          id: pendingId,
          at: Date.now(),
          dry: dry,
          done: false
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
            // UI 找不到则降级自建 POST（仍带 origin）
            window.__caPlaceXhrFallback && window.__caPlaceXhrFallback(opt);
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
                ? '【二次确认】页面可能弹出确认框，脚本未自动点确认（第二层保障）。请打开 WebView 手动点「确认」完成下单，或在官网关闭二次确认后重试。'
                : '已点击平台下单，但未捕获 place 响应。请确认在事件合约页。';
              finish(false, hint+' 步骤:'+steps.join('; '));
              p.done = true;
            }
          }, 12000);
          return;
        } catch(e) {
          CaHibt.onLog('UI下单异常 '+e);
          window.__caPlaceXhrFallback && window.__caPlaceXhrFallback(opt);
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
      setTimeout(function(){ try{ window.__caRefreshAccount(); }catch(e){} }, 2000);
    })();
    """.trimIndent()
}
