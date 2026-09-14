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
     * 下单策略：
     * 1) 优先用 WebView 拦截到的 token + 真实 v，经 **原生 OkHttp POST**（避免页面跨域 405）
     * 2) 若无 v，再尝试 WebView 内 XHR（可能 405/后台超时）
     * 3) 下单前 resumeTimers，缓解后台挂起
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

        // —— 路径 A：原生 POST + WebView 会话（推荐，规避 CORS 405）——
        val token = lastToken.trim()
        val vCap = lastV.trim()
        if (token.isNotBlank()) {
            if (dryRun) {
                val msg = "WEB-SESS-DRY-RUN amount=$amountStr dir=$dir symbol=$sym tu=$unit v=${if (vCap.isBlank()) "(无,实盘需先触发页面请求)" else vCap.take(12)+"…"} base=$lastApiBase"
                _ui.value = _ui.value.copy(lastPlaceMsg = msg, status = msg.take(80))
                return PlaceOutcome(true, msg, dryRun = true)
            }
            if (vCap.isBlank()) {
                // 尝试让页面发一次请求以刷新 v
                main.post {
                    wakeWebView(wv)
                    injectHooks(wv)
                    wv.evaluateJavascript("window.__caRefreshAccount && window.__caRefreshAccount()", null)
                }
                kotlinx.coroutines.delay(800)
            }
            val vUse = lastV.trim()
            if (vUse.isNotBlank()) {
                val cfg = HibtSettings(
                    apiBase = lastApiBase.ifBlank { "https://api.hibt0.com" },
                    authToken = token,
                    xAuthToken = token,
                    vParam = vUse,
                    vAutoTimestamp = false,
                    dryRun = false,
                    autoTrade = true,
                )
                _ui.value = _ui.value.copy(status = "原生POST下单(Web会话)…")
                val native = HibtClient()
                val r = try {
                    native.placeEventOrder(cfg, symbol, directionUp, amount, unit)
                } catch (e: Exception) {
                    HibtClient.OrderResult(false, "原生下单异常: ${e.message}", dryRun = false)
                }
                // 若 405，再试 WebView 路径；否则直接返回
                val is405 = (r.message + (r.raw ?: "")).contains("405")
                if (!is405) {
                    val msg = "[会话POST] ${r.message}"
                    _ui.value = _ui.value.copy(lastPlaceMsg = msg, status = msg.take(100))
                    return PlaceOutcome(r.ok, msg, dryRun = r.dryRun)
                }
                _ui.value = _ui.value.copy(status = "原生405，改试页面内XHR…")
            } else if (!dryRun) {
                // 无 v 时仍可试原生（部分环境），但多数会参数错误；继续走 XHR
                _ui.value = _ui.value.copy(status = "无捕获v，尝试页面XHR…")
            }
        }

        // —— 路径 B：WebView 内脚本（可能跨域 405 / 后台超时）——
        val id = placeSeq.incrementAndGet()
        val deferred = CompletableDeferred<PlaceOutcome>()
        placeWait = deferred
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
                    dryRun: ${if (dryRun) "true" else "false"}
                  });
                } else {
                  CaHibt.onPlaceResult(JSON.stringify({
                    id: $id, ok: false, dryRun: ${if (dryRun) "true" else "false"},
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
            _ui.value = _ui.value.copy(status = if (dryRun) "WebView DRY-RUN…" else "WebView XHR下单…")
            main.postDelayed({
                wakeWebView(webView ?: return@postDelayed)
                webView?.evaluateJavascript(js, null)
            }, 450)
        }
        val waitMs = timeoutSec.coerceIn(10, 180) * 1000L
        val result = withTimeoutOrNull(waitMs) { deferred.await() }
        return result ?: PlaceOutcome(
            false,
            "WebView 下单超时（脚本未回调，已等 ${waitMs / 1000}s）。后台时请保持监控通知栏、可调大超时；建议先在合约页点一下资产刷新 v。",
            dryRun,
        )
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
                if (token.isNotBlank()) lastToken = token
                if (v.isNotBlank()) {
                    lastV = v
                    _ui.value = _ui.value.copy(hasV = true, lastVAt = System.currentTimeMillis())
                }
                if (api.startsWith("http")) lastApiBase = api.trimEnd('/')
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
      var st = { token: '', v: '', apiBase: 'https://api.hibt0.com' };
      function T(x){ return x==null?'':String(x).trim(); }
      function emitSession(){
        try {
          CaHibt.onSession(JSON.stringify({
            token: st.token, v: st.v, apiBase: st.apiBase, xAuthToken: st.token
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
          if (vv) st.v = vv;
          st.apiBase = pickApi(url);
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
      function authHeaders(){
        var h = {
          'accept': 'application/json, text/plain, */*',
          'content-type': 'application/x-www-form-urlencoded',
          'client-type': 'web',
          'platform': 'PC',
          'hc-platform': 'web',
          'future_source': '1',
          'lang': 'zh_CN',
          'hc-language': 'zh_CN'
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
        if (dry) {
          CaHibt.onPlaceResult(JSON.stringify({
            id: opt.id, ok: true, dryRun: true,
            message: 'WEB-DRY-RUN '+JSON.stringify(payload)+' v='+(st.v?st.v.slice(0,12)+'…':'(无)')+' base='+st.apiBase
          }));
          return;
        }
        if (!st.token) {
          CaHibt.onPlaceResult(JSON.stringify({ id:opt.id, ok:false, dryRun:false, message:'无 token：请在 WebView 登录并点击订单/资产页以捕获会话' }));
          return;
        }
        // 固定 API 主域，避免 apiBase 被钩成官网前端域导致 405
        var bases = ['https://api.hibt0.com'];
        if (st.apiBase && st.apiBase.indexOf('api')>=0 && bases.indexOf(st.apiBase)<0) bases.push(st.apiBase);
        var paths = [
          '/option/option-order/place',
          '/event/event-order/place'
        ];
        var body = Object.keys(payload).map(function(k){ return encodeURIComponent(k)+'='+encodeURIComponent(payload[k]); }).join('&');
        var hdr = authHeaders();
        function finish(ok, msg){
          CaHibt.onPlaceResult(JSON.stringify({ id: opt.id, ok: !!ok, dryRun: false, message: msg }));
        }
        var baseIdx = 0;
        function postOne(path, idx){
          var base = bases[Math.min(baseIdx, bases.length-1)] || 'https://api.hibt0.com';
          var url = base + path + (st.v ? ('?v='+encodeURIComponent(st.v)) : '');
          try {
            var xhr = new XMLHttpRequest();
            xhr.open('POST', url, true);
            xhr.withCredentials = true;
            Object.keys(hdr).forEach(function(k){ try{ xhr.setRequestHeader(k, hdr[k]); }catch(e){} });
            xhr.timeout = 20000;
            xhr.onreadystatechange = function(){
              if (xhr.readyState !== 4) return;
              var code = xhr.status, t = xhr.responseText || '';
              if (code === 405) {
                if (idx + 1 < paths.length) {
                  CaHibt.onLog('405 '+path+'，尝试下一路径');
                  postOne(paths[idx+1], idx+1);
                  return;
                }
                if (baseIdx + 1 < bases.length) {
                  baseIdx++;
                  CaHibt.onLog('405 换 API 域 '+bases[baseIdx]);
                  postOne(paths[0], 0);
                  return;
                }
                finish(false, '405 POST 被拒(常为跨域)。请确保已捕获 v；App 会优先用原生会话POST。path='+path+' base='+base);
                return;
              }
              var ok = code>=200 && code<300 && t.indexOf('参数错误')<0 && t.indexOf('"code":500')<0 && t.indexOf('"code":401')<0 && t.indexOf('未登录')<0;
              try {
                var j = JSON.parse(t);
                if (j.code===0 || j.code===200 || j.success===true) ok = true;
                if (j.code && j.code!==0 && j.code!==200) ok = false;
              } catch(e){}
              if (!ok && idx + 1 < paths.length && (code===404 || code===405 || code===0)) {
                postOne(paths[idx+1], idx+1);
                return;
              }
              finish(ok, (ok?'下单成功 ':'下单失败 ')+code+' '+path+' '+(t||'').slice(0,140));
            };
            xhr.ontimeout = function(){
              if (idx + 1 < paths.length) postOne(paths[idx+1], idx+1);
              else finish(false, 'XHR 超时 '+path);
            };
            xhr.onerror = function(){
              if (idx + 1 < paths.length) postOne(paths[idx+1], idx+1);
              else finish(false, 'XHR 网络错误 '+path);
            };
            xhr.send(body);
          } catch(e) {
            if (idx + 1 < paths.length) postOne(paths[idx+1], idx+1);
            else finish(false, String(e));
          }
        }
        postOne(paths[0], 0);
      };
      try { CaHibt.onLog('注入完成，请浏览订单/资产以捕获 token/v'); } catch(e){}
      setTimeout(function(){ try{ window.__caRefreshAccount(); }catch(e){} }, 2000);
    })();
    """.trimIndent()
}
