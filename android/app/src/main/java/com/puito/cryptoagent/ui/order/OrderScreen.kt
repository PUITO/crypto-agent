package com.puito.cryptoagent.ui.order

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puito.cryptoagent.data.Repository
import kotlinx.coroutines.launch

@Composable
fun OrderScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(repo.settings()) }
    var h by remember { mutableStateOf(s.hibt) }
    var status by remember { mutableStateOf("未测试") }
    var balance by remember { mutableStateOf("-") }
    var positions by remember { mutableStateOf("-") }
    var busy by remember { mutableStateOf(false) }

    fun persist(nh: com.puito.cryptoagent.data.HibtSettings) {
        h = nh
        s = s.copy(hibt = nh)
        repo.saveSettings(s)
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("HiBT 自动化 · ${s.interval}", style = MaterialTheme.typography.titleMedium)
        Text(
            "周期与行情页头部一致。默认 Dry-Run，真实下单有资金风险，且非官方 API。",
            color = MaterialTheme.colorScheme.secondary,
        )

        OutlinedTextField(h.apiBase, { persist(h.copy(apiBase = it)) }, label = { Text("API Base") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(h.authToken, { persist(h.copy(authToken = it)) }, label = { Text("Authorization") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(h.xAuthToken, { persist(h.copy(xAuthToken = it)) }, label = { Text("x-auth-token") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(h.vParam, { persist(h.copy(vParam = it)) }, label = { Text("v 参数(可选)") }, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    val r = repo.hibtTest()
                    status = r.message
                    balance = r.balance ?: "-"
                    positions = r.positions ?: "-"
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "测试中…" else "测试连通性") }

        Text("状态: $status")
        Text("余额: $balance")
        Text("持仓: $positions")

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
            "开启后：新信号会先走 AI 评估，结果写入通知；与「自动化下单」独立，可只评估不实盘。",
            color = MaterialTheme.colorScheme.secondary,
        )
        OutlinedTextField(
            h.aiMinWinRate.toString(),
            { it.toDoubleOrNull()?.let { v -> persist(h.copy(aiMinWinRate = v)) } },
            label = { Text("AI 胜率阈值(%) · 通知与下单共用") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            h.defaultAmount.toString(),
            { it.toDoubleOrNull()?.let { v -> persist(h.copy(defaultAmount = v)) } },
            label = { Text("默认下单金额") },
            modifier = Modifier.fillMaxWidth(),
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
}
