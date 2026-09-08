package com.puito.cryptoagent.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puito.cryptoagent.data.PrefsStore
import com.puito.cryptoagent.net.AgentApi
import kotlinx.coroutines.launch

@Composable
fun ConfigScreen(api: AgentApi, prefs: PrefsStore) {
    val scope = rememberCoroutineScope()
    val cur = remember { prefs.load() }
    var base by remember { mutableStateOf(cur.gatewayBaseUrl) }
    var symbol by remember { mutableStateOf(cur.symbol) }
    var interval by remember { mutableStateOf(cur.interval) }
    var limit by remember { mutableStateOf(cur.limit.toString()) }
    var remoteCfg by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("连接设置", style = MaterialTheme.typography.titleMedium)
        Text("本 App 是 crypto-agent 的安卓客户端，通过 Gateway 访问微服务。", color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(
            base, { base = it },
            label = { Text("Gateway Base URL") },
            supportingText = { Text("模拟器默认 10.0.2.2:8000；真机填局域网 IP") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(symbol, { symbol = it }, label = { Text("默认交易对") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(interval, { interval = it }, label = { Text("默认周期") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(limit, { limit = it }, label = { Text("K 线条数") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                val p = cur.copy(
                    gatewayBaseUrl = base.trim(),
                    symbol = symbol.trim().uppercase(),
                    interval = interval.trim(),
                    limit = limit.toIntOrNull() ?: 200,
                )
                prefs.save(p)
                api.updateBase(p.gatewayBaseUrl)
                msg = "已保存"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("远端 Config 预览", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = {
            scope.launch {
                remoteCfg = try {
                    api.updateBase(base.trim())
                    api.configPublic()
                } catch (e: Exception) {
                    e.message ?: "error"
                }
            }
        }) { Text("拉取 /config") }
        if (remoteCfg.isNotBlank()) {
            Text(remoteCfg, style = MaterialTheme.typography.bodySmall)
        }
    }
}
