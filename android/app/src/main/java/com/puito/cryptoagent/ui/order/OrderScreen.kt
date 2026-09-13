package com.puito.cryptoagent.ui.order

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
    var status by remember { mutableStateOf("未测试") }
    var balance by remember { mutableStateOf("-") }
    var positions by remember { mutableStateOf("-") }
    var busy by remember { mutableStateOf(false) }
    var parseMsg by remember { mutableStateOf<String?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteDraft by remember { mutableStateOf("") }
    var expandLog by remember { mutableStateOf(false) }

    fun persist(nh: com.puito.cryptoagent.data.HibtSettings) {
        h = nh
        s = s.copy(hibt = nh)
        repo.saveSettings(s)
    }

    fun applyParsed(raw: String) {
        val r = HibtBundleParser.parse(raw, h)
        parseMsg = r.summary
        if (r.ok || r.settings.xAuthToken.isNotBlank() || r.settings.apiBase != h.apiBase) {
            persist(r.settings)
            status = r.summary
        }
    }

    fun readClipboard(): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("HiBT 自动化 · ${s.interval}", style = MaterialTheme.typography.titleMedium)
        Text(
            "周期与行情页头部一致。默认 Dry-Run，真实下单有资金风险，且非官方 API。",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
        )

        Text(
            "电脑书签抓取 → 复制 → 剪贴板解析。",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val text = readClipboard()
                    if (text.isBlank()) {
                        parseMsg = "剪贴板为空，可改用「粘贴解析」"
                        showPasteDialog = true
                    } else {
                        applyParsed(text)
                    }
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

        OutlinedTextField(h.apiBase, { persist(h.copy(apiBase = it)) }, label = { Text("API Base") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.xAuthToken, { persist(h.copy(xAuthToken = it)) }, label = { Text("x-auth-token（主）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.authToken, { persist(h.copy(authToken = it)) }, label = { Text("Authorization（可同 token）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.bgetKey, { persist(h.copy(bgetKey = it)) }, label = { Text("bgetKey") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.bgetId, { persist(h.copy(bgetId = it)) }, label = { Text("bgetId") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.langCode, { persist(h.copy(langCode = it)) }, label = { Text("langCode") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.clientType, { persist(h.copy(clientType = it)) }, label = { Text("clientType web/h5") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(h.vParam, { persist(h.copy(vParam = it)) }, label = { Text("v 参数(可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    val r = repo.hibtTest()
                    status = r.message
                    balance = r.balance ?: "—"
                    positions = r.positions ?: "—"
                    if (!r.ok && !r.raw.isNullOrBlank()) {
                        status = r.message + "\n" + r.raw!!.take(400)
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "测试中…" else "测试连通性") }

        // 账户摘要：固定两行，不挤布局
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("账户状态", style = MaterialTheme.typography.titleSmall)
                Text("余额：$balance", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("事件合约持仓：$positions", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // 测试日志：默认折叠一行，展开限高滚动
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("测试信息", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { expandLog = !expandLog }) {
                        Text(if (expandLog) "收起" else "展开")
                    }
                }
                if (expandLog) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    Text(
                        status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开启自动化下单", Modifier.weight(1f))
            Switch(h.autoTrade, { persist(h.copy(autoTrade = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dry-Run（推荐保持开启）", Modifier.weight(1f))
            Switch(h.dryRun, { persist(h.copy(dryRun = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用 AI 评估信号（可只通知不实盘）", Modifier.weight(1f))
            Switch(h.aiEvaluate, { persist(h.copy(aiEvaluate = it)) })
        }
        Text(
            "AI 评估用该周期行情估胜率；低于阈值不自动下单。需 LLM Key。",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
        )
        OutlinedTextField(
            h.aiMinWinRate.toString(),
            { it.toDoubleOrNull()?.let { v -> persist(h.copy(aiMinWinRate = v)) } },
            label = { Text("AI 胜率阈值(%)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            h.defaultAmount.toString(),
            { it.toDoubleOrNull()?.let { v -> persist(h.copy(defaultAmount = v)) } },
            label = { Text("默认下单金额") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                scope.launch {
                    busy = true
                    status = repo.hibtPlace(true).message
                    busy = false
                }
            }, Modifier.weight(1f), enabled = !busy) { Text("测试买涨") }
            Button({
                scope.launch {
                    busy = true
                    status = repo.hibtPlace(false).message
                    busy = false
                }
            }, Modifier.weight(1f), enabled = !busy) { Text("测试买跌") }
        }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴 HiBT 认证文本") },
            text = {
                OutlinedTextField(
                    value = pasteDraft,
                    onValueChange = { pasteDraft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("粘贴 key=value 或 JSON…") },
                )
            },
            confirmButton = {
                TextButton({
                    applyParsed(pasteDraft)
                    showPasteDialog = false
                }) { Text("解析并填入") }
            },
            dismissButton = {
                TextButton({ showPasteDialog = false }) { Text("取消") }
            },
        )
    }
}
