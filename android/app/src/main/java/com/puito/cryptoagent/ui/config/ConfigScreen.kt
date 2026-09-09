package com.puito.cryptoagent.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.puito.cryptoagent.data.Repository
import com.puito.cryptoagent.service.MonitorService

@Composable
fun ConfigScreen(repo: Repository) {
    val ctx = LocalContext.current
    val cur = remember { repo.settings() }
    var binance by remember { mutableStateOf(cur.binanceBaseUrl) }
    var symbol by remember { mutableStateOf(cur.symbol) }
    var interval by remember { mutableStateOf(cur.interval) }
    var limit by remember { mutableStateOf(cur.klineLimit.toString()) }
    var llmUrl by remember { mutableStateOf(cur.llmBaseUrl) }
    var llmKey by remember { mutableStateOf(cur.llmApiKey) }
    var llmModel by remember { mutableStateOf(cur.llmModel) }
    var bg by remember { mutableStateOf(cur.backgroundEnabled) }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("本地完整运行设置", style = MaterialTheme.typography.titleMedium)
        Text("无需自建服务器：行情直连 Binance，策略与模拟在手机内完成。", color = MaterialTheme.colorScheme.secondary)

        Text("行情", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(binance, { binance = it }, label = { Text("Binance Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(symbol, { symbol = it }, label = { Text("交易对") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(interval, { interval = it }, label = { Text("周期 5m/10m/30m/1h") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(limit, { limit = it }, label = { Text("K 线条数(建议≤500)") }, modifier = Modifier.fillMaxWidth())

        Text("LLM（对话）", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(llmUrl, { llmUrl = it }, label = { Text("LLM Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(llmKey, { llmKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(llmModel, { llmModel = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())

        Text("后台", style = MaterialTheme.typography.titleSmall)
        Row {
            Text("默认静默后台监控", modifier = Modifier.weight(1f))
            Switch(bg, {
                bg = it
            })
        }

        Button({
            val s = cur.copy(
                binanceBaseUrl = binance.trim(),
                symbol = symbol.trim().uppercase(),
                interval = interval.trim(),
                klineLimit = limit.toIntOrNull()?.coerceIn(50, 1000) ?: 500,
                llmBaseUrl = llmUrl.trim(),
                llmApiKey = llmKey.trim(),
                llmModel = llmModel.trim(),
                backgroundEnabled = bg,
                onboardingDone = true,
            )
            repo.saveSettings(s)
            if (bg && s.strategyRunning) MonitorService.start(ctx) else if (!bg) MonitorService.stop(ctx)
            msg = "已保存"
        }, Modifier.fillMaxWidth()) { Text("保存") }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
