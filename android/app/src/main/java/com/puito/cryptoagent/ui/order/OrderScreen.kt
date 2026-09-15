package com.puito.cryptoagent.ui.order

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.puito.cryptoagent.data.Repository
import com.puito.cryptoagent.net.HibtWebSession
import com.puito.cryptoagent.util.HibtBundleParser
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OrderScreen(repo: Repository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(repo.settings()) }
    var h by remember { mutableStateOf(s.hibt) }
    var status by remember { mutableStateOf("未操作") }
    var busy by remember { mutableStateOf(false) }
    var parseMsg by remember { mutableStateOf<String?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteDraft by remember { mutableStateOf("") }
    var expandLog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showWeb by remember { mutableStateOf(false) }

    val webUi by HibtWebSession.ui.collectAsState()

    // WebView 捕获 token 后刷新界面输入框（与原生设置同源）
    LaunchedEffect(webUi.nativeTokenSyncedAt, webUi.ready) {
        if (webUi.nativeTokenSyncedAt > 0 && webUi.ready) {
            s = repo.settings()
            h = s.hibt
            toast = "已用 WebView 会话更新原生 token/API"
        }
    }

    // 原生查询的余额/持仓（补充）；WebView 会话优先显示
    var nativeBal by remember { mutableStateOf("-") }
    var nativePos by remember { mutableStateOf("-") }

    fun persist(nh: com.puito.cryptoagent.data.HibtSettings) {
        h = nh
        s = s.copy(hibt = nh)
        repo.saveSettings(s)
    }

    fun formatOrderStatus(r: com.puito.cryptoagent.net.HibtClient.OrderResult): String {
        val raw = r.raw?.trim().orEmpty()
        if (raw.isEmpty()) return r.message
        if (r.message.contains(raw) || (raw.contains("form:") && r.message.contains("form:"))) {
            return r.message
        }
        return r.message + "\n" + raw
    }

    val displayBal = if (webUi.balance != "—" && webUi.balance.isNotBlank()) webUi.balance else nativeBal
    val displayPos = if (webUi.positions != "—" && webUi.positions.isNotBlank()) webUi.positions else nativePos

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("HiBT 下单 · 行情 ${s.interval} · 自动 ${h.autoIntervals.joinToString(",")}", style = MaterialTheme.typography.titleMedium)
            Text(
                "请登录后进入事件合约下单页并点「锁定当前为下单页」。隐藏只保活不回首页；自动下单前会自动恢复该页并由平台UI下单。",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
            )

            // —— WebView 管理 ——
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WebView 会话", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "状态：${webUi.status}",
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "就绪：${if (webUi.ready) "是" else "否"} · token：${webUi.tokenPreview.ifBlank { "—" }} · v：${if (webUi.hasV) "有" else "无"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (webUi.pageUrl.isNotBlank()) {
                        Text("页：${webUi.pageUrl}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (webUi.lastPlaceMsg.isNotBlank()) {
                        Text("最近下单：${webUi.lastPlaceMsg}", fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                HibtWebSession.obtain(ctx)
                                HibtWebSession.openSession(ctx, forceReload = false)
                                showWeb = true
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (webUi.loaded) "显示 WebView" else "打开 WebView 登录") }
                        OutlinedButton(
                            onClick = {
                                // 只隐藏界面，不销毁、不重新 load 首页
                                showWeb = false
                                HibtWebSession.detachFromParent()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("隐藏(保活)") }
                    }
                    Text(
                        "下单页锁定：${webUi.orderPageLocked.ifBlank { "未锁定（进事件合约后点锁定）" }}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { HibtWebSession.lockCurrentAsOrderPage(); toast = "已锁定当前页" },
                            modifier = Modifier.weight(1f),
                        ) { Text("锁定当前为下单页") }
                        OutlinedButton(
                            onClick = { HibtWebSession.goOrderPage(ctx); showWeb = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("进/恢复合约页") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { HibtWebSession.refreshAccount() },
                            modifier = Modifier.weight(1f),
                        ) { Text("刷新账户") }
                        OutlinedButton(
                            onClick = { HibtWebSession.reload() },
                            modifier = Modifier.weight(1f),
                        ) { Text("刷新页面") }
                        OutlinedButton(
                            onClick = {
                                HibtWebSession.clearSession(ctx)
                                toast = "会话已清"
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("清会话") }
                    }
                }
            }

            // —— 账户（Web 优先 + 原生补充）——
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("账户状态", style = MaterialTheme.typography.titleSmall)
                    Text("余额：$displayBal", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("持仓：$displayPos", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "来源：${if (webUi.balance != "—") "WebView" else "原生查询/未刷新"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val r = repo.hibtTest()
                            status = buildString {
                                append(r.message)
                                if (!r.raw.isNullOrBlank()) append("\n---\n").append(r.raw)
                            }
                            nativeBal = r.balance ?: "—"
                            nativePos = r.positions ?: "—"
                            expandLog = true
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "查询中…" else "原生查余额") }
            }

            HorizontalDivider()

            // —— 自动化开关 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开启自动化下单", Modifier.weight(1f))
                Switch(h.autoTrade, { persist(h.copy(autoTrade = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dry-Run（关=真实请求）", Modifier.weight(1f))
                Switch(h.dryRun, { persist(h.copy(dryRun = it)) })
            }
            Text("自动下单周期（可多选，与行情页全局周期无关）", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val options = listOf("5m", "10m", "30m", "1h")
                val selected = h.autoIntervals.map { it.lowercase() }.toSet()
                options.forEach { code ->
                    val on = code in selected
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = selected.toMutableSet()
                            if (on) {
                                if (next.size > 1) next.remove(code) // 至少保留一个
                            } else {
                                next.add(code)
                            }
                            persist(h.copy(autoIntervals = options.filter { it in next }))
                        },
                        label = { Text(code) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("防追单（大单边同向跳过）", Modifier.weight(1f), fontSize = 14.sp)
                Switch(h.antiChaseEnabled, { persist(h.copy(antiChaseEnabled = it)) })
            }
            if (h.antiChaseEnabled) {
                Text(
                    "近 ${h.antiChaseBars} 根 1m 若强势单边，同向信号不自动下单，避免死硬追涨杀跌。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("优先 WebView 下单", Modifier.weight(1f))
                // 固定推荐开启：用 settings 没有该字段时用 ready 状态提示
                Text(if (webUi.ready || webUi.loaded) "已启用" else "需先登录", fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用 AI 评估", Modifier.weight(1f))
                Switch(h.aiEvaluate, { persist(h.copy(aiEvaluate = it)) })
            }
            OutlinedTextField(
                h.aiMinWinRate.toString(),
                { it.toDoubleOrNull()?.let { v -> persist(h.copy(aiMinWinRate = v)) } },
                label = { Text("AI 胜率阈值(%)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                h.defaultAmount.toString(),
                { it.toDoubleOrNull()?.let { v -> persist(h.copy(defaultAmount = v)) } },
                label = { Text("默认下单金额") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                h.placeTimeoutSec.toString(),
                {
                    it.toIntOrNull()?.let { v ->
                        persist(h.copy(placeTimeoutSec = v.coerceIn(10, 180)))
                    }
                },
                label = { Text("WebView 下单超时(秒) 10–180，默认45") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    scope.launch {
                        busy = true
                        val r = repo.hibtPlace(true)
                        status = formatOrderStatus(r)
                        expandLog = true
                        busy = false
                    }
                }, Modifier.weight(1f), enabled = !busy) { Text("测试买涨") }
                Button({
                    scope.launch {
                        busy = true
                        val r = repo.hibtPlace(false)
                        status = formatOrderStatus(r)
                        expandLog = true
                        busy = false
                    }
                }, Modifier.weight(1f), enabled = !busy) { Text("测试买跌") }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("测试信息", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val blob = buildString {
                                appendLine(status)
                                if (webUi.lastPlaceMsg.isNotBlank()) {
                                    appendLine("---")
                                    appendLine(webUi.lastPlaceMsg)
                                }
                                appendLine("webStatus=${webUi.status}")
                                appendLine("token=${webUi.tokenPreview} hasV=${webUi.hasV}")
                            }
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("test_log", blob))
                            toast = "已复制测试信息（含 WebView 下单结果）"
                        }) { Text("复制") }
                        TextButton(onClick = { expandLog = !expandLog }) {
                            Text(if (expandLog) "收起" else "展开")
                        }
                    }
                    if (expandLog) {
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        if (webUi.lastPlaceMsg.isNotBlank()) {
                            Text("Web: ${webUi.lastPlaceMsg}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            HorizontalDivider()
            Text("兼容：剪贴板 token（可选）", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    if (text.isBlank()) {
                        showPasteDialog = true
                    } else {
                        val r = HibtBundleParser.parse(text, h)
                        parseMsg = r.summary
                        if (r.ok) persist(r.settings)
                    }
                }, Modifier.weight(1f)) { Text("剪贴板解析") }
            }
            parseMsg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(
                h.apiBase, { persist(h.copy(apiBase = it)) },
                label = { Text("API Base") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                h.xAuthToken, { persist(h.copy(xAuthToken = it, authToken = it.ifBlank { h.authToken })) },
                label = { Text("备用 x-auth-token（原生回退）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            toast?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
        }

        // 全屏 WebView：关闭=隐藏保活，不 destroy
        if (showWeb) {
            BackHandler {
                showWeb = false
                HibtWebSession.detachFromParent()
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("HiBT WebView（返回=隐藏保活）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = {
                            showWeb = false
                            HibtWebSession.detachFromParent()
                        }) { Text("隐藏") }
                    }
                    AndroidView(
                        factory = { c ->
                            val wv = HibtWebSession.obtain(c)
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            if (wv.url.isNullOrBlank()) {
                                val resume = HibtWebSession.lastOrderPageUrl
                                if (resume.isNotBlank()) wv.loadUrl(resume)
                                // 否则等 openSession / 用户导航，避免强行首页冲掉状态
                            }
                            wv
                        },
                        modifier = Modifier.fillMaxSize().weight(1f),
                    )
                }
            }
        }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴认证文本") },
            text = {
                OutlinedTextField(
                    value = pasteDraft,
                    onValueChange = { pasteDraft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
            },
            confirmButton = {
                TextButton({
                    val r = HibtBundleParser.parse(pasteDraft, h)
                    parseMsg = r.summary
                    if (r.ok) persist(r.settings)
                    showPasteDialog = false
                }) { Text("解析") }
            },
            dismissButton = { TextButton({ showPasteDialog = false }) { Text("取消") } },
        )
    }
}
