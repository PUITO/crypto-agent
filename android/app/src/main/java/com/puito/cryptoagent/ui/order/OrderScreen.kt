package com.puito.cryptoagent.ui.order

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.puito.cryptoagent.data.Repository
import com.puito.cryptoagent.util.HibtBundleParser
import kotlinx.coroutines.launch

@Composable
fun OrderScreen(repo: Repository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(repo.settings()) }
    var h by remember { mutableStateOf(s.hibt) }
    var status by remember { mutableStateOf("未查询") }
    var balance by remember { mutableStateOf("-") }
    var positions by remember { mutableStateOf("-") }
    var busy by remember { mutableStateOf(false) }
    var parseMsg by remember { mutableStateOf<String?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteDraft by remember { mutableStateOf("") }
    var expandLog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun persist(nh: com.puito.cryptoagent.data.HibtSettings) {
        h = nh
        s = s.copy(hibt = nh)
        repo.saveSettings(s)
    }

    fun applyParsed(raw: String) {
        val r = HibtBundleParser.parse(raw, h)
        parseMsg = r.summary
        if (r.ok || r.settings.xAuthToken.isNotBlank()) {
            // 仅写入内存+本地配置供在线测试；不上传
            persist(r.settings)
            status = r.summary + " · 请点「查询余额/持仓」"
        }
    }

    fun readClipboard(): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
    }

    fun copyText(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hibt-test", text))
        toast = "已复制"
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("HiBT 在线测试 · ${s.interval}", style = MaterialTheme.typography.titleMedium)
        Text(
            "用途：填 token 后在线查余额与未平仓笔数、试下单。凭证仅存本机，不上传。",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val text = readClipboard()
                    if (text.isBlank()) {
                        parseMsg = "剪贴板为空"
                        showPasteDialog = true
                    } else applyParsed(text)
                },
                modifier = Modifier.weight(1f),
            ) { Text("剪贴板解析") }
            OutlinedButton(
                onClick = {
                    pasteDraft = readClipboard()
                    showPasteDialog = true
                },
                modifier = Modifier.weight(1f),
            ) { Text("粘贴解析") }
        }
        parseMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        OutlinedTextField(
            h.apiBase, { persist(h.copy(apiBase = it)) },
            label = { Text("API Base（常用 https://api.hibt0.com）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            h.xAuthToken, { persist(h.copy(xAuthToken = it, authToken = it.ifBlank { h.authToken })) },
            label = { Text("x-auth-token / Authorization") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(h.vParam, { persist(h.copy(vParam = it)) }, label = { Text("v 参数（下单常需要）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.bgetKey, { persist(h.copy(bgetKey = it)) }, label = { Text("bgetKey（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.bgetId, { persist(h.copy(bgetId = it)) }, label = { Text("bgetId（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.clientType, { persist(h.copy(clientType = it)) }, label = { Text("clientType web/h5") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    val r = repo.hibtTest()
                    status = buildString {
                        append(r.message)
                        if (!r.raw.isNullOrBlank()) append("\n---\n").append(r.raw)
                    }
                    balance = r.balance ?: "—"
                    positions = r.positions ?: "—"
                    expandLog = true
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "查询中…" else "查询余额 / 持仓") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("账户状态（在线）", style = MaterialTheme.typography.titleSmall)
                Text("余额：$balance", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("事件合约持仓：$positions", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("测试信息", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copyText(status) }) { Text("复制") }
                    TextButton(onClick = { expandLog = !expandLog }) {
                        Text(if (expandLog) "收起" else "展开")
                    }
                }
                if (expandLog) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开启自动化下单", Modifier.weight(1f))
            Switch(h.autoTrade, { persist(h.copy(autoTrade = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dry-Run（关=真实请求，慎用）", Modifier.weight(1f))
            Switch(h.dryRun, { persist(h.copy(dryRun = it)) })
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
            label = { Text("默认下单金额（≥交易所最小额）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                scope.launch {
                    busy = true
                    val r = repo.hibtPlace(true)
                    status = r.message + (r.raw?.let { "\n$it" } ?: "")
                    expandLog = true
                    busy = false
                }
            }, Modifier.weight(1f), enabled = !busy) { Text("测试买涨") }
            Button({
                scope.launch {
                    busy = true
                    val r = repo.hibtPlace(false)
                    status = r.message + (r.raw?.let { "\n$it" } ?: "")
                    expandLog = true
                    busy = false
                }
            }, Modifier.weight(1f), enabled = !busy) { Text("测试买跌") }
        }
        Text(
            "下单参数：symbol=btc_usdt/eth_usdt，timeUnit=行情周期分钟，需有效 v。参数错误时请复制测试信息对照。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
        toast?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴认证文本") },
            text = {
                OutlinedTextField(
                    value = pasteDraft,
                    onValueChange = { pasteDraft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("token / key=value / JSON") },
                )
            },
            confirmButton = {
                TextButton({
                    applyParsed(pasteDraft)
                    showPasteDialog = false
                }) { Text("解析") }
            },
            dismissButton = { TextButton({ showPasteDialog = false }) { Text("取消") } },
        )
    }
}
