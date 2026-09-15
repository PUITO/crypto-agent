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
        val tokenPreview: String = "",
        val hasV: Boolean = false,
        val lastVAt: Long = 0L,
        val balance: String = "—",
        val positions: String = "—",
        val status: String = "未启动",
        val lastPlaceMsg: String = "",
        val loaded: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    @Volatile private var webView: WebView? = null
    @Volatile private var lastToken: String = ""
    @Volatile private var lastV: String = ""
    @Volatile private var lastApiBase: String = "https://api.hibt0.com"
    @Volatile private var lastOrigin: String = "https://hibt.com"
    @Volatile private var lastReferer: String = "https://hibt.com/"
    /** 拦截到的加密 v（长 base64 等） */
    @Volatile private var lastVEnc: String = ""
    /** 拦截/生成的明文时间戳 v */
    @Volatile private var lastVPlain: String = ""
    @Volatile private var placeWait: CompletableDeferred<PlaceOutcome>? = null
    private val placeSeq = AtomicLong(0)

    data class PlaceOutcome(val ok: Boolean, val message: String, val dryRun: Boolean)

    fun peek(): WebView? = webView

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context): WebView {
        webView?.let { return it }
        synchronized(this) {
            webView?.let { return it }
            val appCtx = context.applicationContext
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
                    _ui.value = _ui.value.copy(pageUrl = url, loaded = true, status = "页面已加载，注入中…")
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

    fun openHome(context: Context, url: String = "https://hibt.com") {
        val wv = obtain(context)
        main.post {
            _ui.value = _ui.value.copy(status = "加载 $url")
            wv.loadUrl(url)
        }
    }

    fun reload() {
        main.post { webView?.reload() }
    }

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
        }
    }

    /**
     * 下单优先级：
     * 1) WebView 页内 POST（带解析到的 Origin/Referer + v）
     * 2) 降级原生 OkHttp POST（同样带 Origin；v 优先加密，其次明文时间戳）
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
            val msg = "DRY-RUN web-first amount=$amountStr dir=$dir $sym tu=$unit origin=$origin vEnc=${vEnc.take(10).ifBlank { "无" }}… vPlain=$vPlain"
            _ui.value = _ui.value.copy(lastPlaceMsg = msg, status = msg.take(90))
            return PlaceOutcome(true, msg, dryRun = true)
        }

        // —— 1) WebView 页内下单（优先）——
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

        // —— 2) 原生降级：加密 v → 明文时间戳 v，均带 Origin ——
        if (token.isBlank()) {
            val msg = webResult?.message
                ?: "无 token：请 WebView 登录；WebView 与原生均无法下单"
            return PlaceOutcome(false, msg, dryRun = false)
        }
        val native = HibtClient()
        val vCandidates = linkedSetOf<String>()
        if (vEnc.isNotBlank()) vCandidates.add(vEnc)
        if (lastV.isNotBlank()) vCandidates.add(lastV.trim())
        vCandidates.add(vPlain)
        vCandidates.add(System.currentTimeMillis().toString())

        var lastMsg = "原生降级无结果"
        for (vTry in vCandidates) {
            val cfg = HibtSettings(
                apiBase = lastApiBase.ifBlank { "https://api.hibt0.com" },
                authToken = token,
                xAuthToken = token,
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
                _ui.value = _ui.value.copy(lastPlaceMsg = lastMsg, status = lastMsg.take(100))
                return PlaceOutcome(true, lastMsg, dryRun = false)
            }
            // 参数错误则换下一个 v；405 也继续试
        }
        val fail = PlaceOutcome(false, lastMsg, dryRun = false)
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
                _ui.value = _ui.value.copy(
                    ready = lastToken.isNotBlank(),
                    tokenPreview = preview,
                    status = if (lastToken.isNotBlank()) "会话已捕获" + (if (lastV.isNotBlank()) " · 有 v" else " · 等待页面请求以捕获 v")
                    else "未捕获 token，请在合约页点订单/资产",
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
                placeWait?.complete(PlaceOutcome(ok, msg, dry))
                placeWait = null
            } catch (e: Exception) {
                placeWait?.complete(PlaceOutcome(false, e.message ?: "parse error", false))
                placeWait = null
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            _ui.value = _ui.value.copy(status = msg.take(120))
        }
    }

    /**
     * 注入：钩 fetch/XHR 抓 token/v；提供 __caPlace / __caRefreshAccount。
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
          if (tok.length > 20) st.token = tok;
          var vv = pickV(url);
          if (vv) {
            st.v = vv;
            if (/^\d{10,}$/.test(vv)) st.vPlain = vv; else st.vEnc = vv;
          }
          st.apiBase = pickApi(url);
          try {
            st.origin = location.origin || st.origin;
            st.referer = location.href || st.referer;
          } catch(e){}
          if (st.token || st.v) emitSession();
        } catch(e){}
      }
      var OF = window.fetch;
      if (typeof OF === 'function') {
        window.fetch = function(){
          try {
            var req = arguments[0], init = arguments[1] || {};
            var url = typeof req === 'string' ? req : (req && req.url) || '';
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
          return OF.apply(this, arguments);
        };
      }
      if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
        var OO = XMLHttpRequest.prototype.open, OS = XMLHttpRequest.prototype.setRequestHeader, OE = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(m,u){ this.__caU=u; this.__caH={}; return OO.apply(this, arguments); };
        XMLHttpRequest.prototype.setRequestHeader = function(n,v){ try{ this.__caH[n]=v; }catch(e){} return OS.apply(this, arguments); };
        XMLHttpRequest.prototype.send = function(){ try{ absorb(this.__caU||'', this.__caH||{}); }catch(e){} return OE.apply(this, arguments); };
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
            message: 'WEB-DRY-RUN '+JSON.stringify(payload)+' origin='+origin+' vEnc='+(st.vEnc||opt.vEnc||'').slice(0,10)+' vPlain='+(st.vPlain||opt.vPlain||'')
          }));
          return;
        }
        if (!st.token) {
          CaHibt.onPlaceResult(JSON.stringify({ id:opt.id, ok:false, dryRun:false, message:'无 token：请在 WebView 登录并点击订单/资产页以捕获会话' }));
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
          CaHibt.onPlaceResult(JSON.stringify({ id: opt.id, ok: !!ok, dryRun: false, message: msg }));
        }
        var bi = 0, pi = 0, vi = 0;
        function next(){
          if (vi >= vList.length) {
            vi = 0; pi++;
          }
          if (pi >= paths.length) {
            pi = 0; bi++;
          }
          if (bi >= bases.length) {
            finish(false, 'WebView POST 全失败(含405)。origin='+origin+' 将降级原生');
            return;
          }
          var base = bases[bi], path = paths[pi], v = vList[vi];
          var url = base + path + (v ? ('?v='+encodeURIComponent(v)) : '');
          try {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', url, true);
            xhr.withCredentials = true;
            Object.keys(hdr).forEach(function(k){ try{ xhr.setRequestHeader(k, hdr[k]); }catch(e){} });
            xhr.timeout = 20000;
            xhr.onreadystatechange = function(){
              if (xhr.readyState !== 4) return;
              var code = xhr.status, t = xhr.responseText || '';
              var ok = code>=200 && code<300 && t.indexOf('参数错误')<0 && t.indexOf('"code":500')<0 && t.indexOf('"code":401')<0 && t.indexOf('未登录')<0;
              try {
                var j = JSON.parse(t);
                if (j.code===0 || j.code===200 || j.success===true) ok = true;
                if (j.code && j.code!==0 && j.code!==200) ok = false;
              } catch(e){}
              if (ok) {
                finish(true, '下单成功 '+code+' '+path+' v='+String(v).slice(0,12)+' '+(t||'').slice(0,100));
                return;
              }
              // 参数错误换 v；405/404 换 path/base
              if (t.indexOf('参数')>=0 || t.indexOf('param')>=0) { vi++; next(); return; }
              if (code===405 || code===404 || code===0) { vi++; if (vi>=vList.length){ vi=0; pi++; } next(); return; }
              vi++; next();
            };
            xhr.ontimeout = function(){ vi++; next(); };
            xhr.onerror = function(){ vi++; next(); };
            xhr.send(body);
          } catch(e) {
            vi++; next();
          }
        }
        next();
      };

      try { CaHibt.onLog('注入完成，请浏览订单/资产以捕获 token/v'); } catch(e){}
      setTimeout(function(){ try{ window.__caRefreshAccount(); }catch(e){} }, 2000);
    })();
    """.trimIndent()
}
