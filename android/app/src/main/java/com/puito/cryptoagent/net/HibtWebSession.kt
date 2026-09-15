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
            return PlaceOutcome(true, msg, dryRun = true)
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
          setTimeout(function(){
            var p = window.__caPendingPlace;
            if (p && !p.done && p.id===pendingId) {
              // 未捕获到响应：可能 UI 成功但接口路径未匹配，或按钮无效
              finish(false, '已点击平台下单，但未捕获 place 响应。请确认在事件合约页。步骤:'+steps.join('; '));
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
