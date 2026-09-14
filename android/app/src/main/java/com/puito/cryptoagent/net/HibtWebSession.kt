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
     * 在 WebView 内下单。无会话时返回 null，由调用方决定是否回退。
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
        if (lastToken.isBlank() && !_ui.value.ready) {
            // 仍尝试注入，可能页面已登录但未钩到
        }
        val id = placeSeq.incrementAndGet()
        val deferred = CompletableDeferred<PlaceOutcome>()
        placeWait = deferred
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
            // 后台/隐藏时 WebView 可能暂停 JS 与网络，先唤醒
            wakeWebView(wv)
            injectHooks(wv)
            _ui.value = _ui.value.copy(status = if (dryRun) "WebView DRY-RUN…" else "WebView 下单中…")
            main.postDelayed({
                wakeWebView(webView ?: return@postDelayed)
                webView?.evaluateJavascript(js, null)
            }, 400)
        }
        val waitMs = timeoutSec.coerceIn(10, 180) * 1000L
        val result = withTimeoutOrNull(waitMs) { deferred.await() }
        return result ?: PlaceOutcome(
            false,
            "WebView 下单超时（脚本未回调，已等 ${waitMs / 1000}s；可在下单页调大「下单超时」）",
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
        var base = st.apiBase || 'https://api.hibt0.com';
        var paths = [
          '/event/event-order/place',
          '/option/option-order/place'
        ];
        var body = Object.keys(payload).map(function(k){ return encodeURIComponent(k)+'='+encodeURIComponent(payload[k]); }).join('&');
        var hdr = authHeaders();
        function finish(ok, msg){
          CaHibt.onPlaceResult(JSON.stringify({ id: opt.id, ok: !!ok, dryRun: false, message: msg }));
        }
        function postOne(path, idx){
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
                finish(false, '405 Method Not Allowed 全部路径失败。请在 WebView 打开事件合约下单页再试（勿停在 API 地址）。path='+path);
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
