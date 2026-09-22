package com.puito.cryptoagent.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.puito.cryptoagent.data.Repository
import com.puito.cryptoagent.net.HibtWebSession
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
    var llmTimeout by remember { mutableStateOf(cur.llmTimeoutSec.toString()) }
    var llmMaxTokens by remember { mutableStateOf(cur.llmMaxTokens.toString()) }
    var llmTemp by remember { mutableStateOf(cur.llmTemperature.toString()) }
    var llmEvalBars by remember { mutableStateOf(cur.llmEvalMaxBars.toString()) }
    var llmThinking by remember { mutableStateOf(cur.llmThinkingEnabled) }
    var bg by remember { mutableStateOf(cur.backgroundEnabled) }
    var vibrate by remember { mutableStateOf(cur.notifyVibrate) }
    var mode1m by remember { mutableStateOf(cur.signalMode1mConfirm) }
    var modeHt by remember { mutableStateOf(cur.signalModeHtNative) }
    var liveSim by remember { mutableStateOf(cur.liveSimEnabled) }
    var liveSimAuto by remember { mutableStateOf(cur.liveSimAutoRetune) }
    var liveSimWr by remember { mutableStateOf(cur.liveSimMinWinRatePct.toString()) }
    var liveSimLoss by remember { mutableStateOf(cur.liveSimMaxConsecutiveLosses.toString()) }
    var liveSimMinTrades by remember { mutableStateOf(cur.liveSimMinTradesBeforeRetune.toString()) }
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
        OutlinedTextField(limit, { limit = it }, label = { Text("K 线条数(建议 500～1000，过小易失真)") }, modifier = Modifier.fillMaxWidth())

        Text("信号模式（可双开，信号更多）", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模式1：1m触发+高周期确认", modifier = Modifier.weight(1f))
            Switch(mode1m, { mode1m = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模式2：周期原生策略(5m/10m/30m/1h)", modifier = Modifier.weight(1f))
            Switch(modeHt, { modeHt = it })
        }
        Text(
            "双开时合并两套信号去重。都关则自动双开。模式1低延迟；模式2周期独立出信号更多。",
            color = MaterialTheme.colorScheme.secondary,
        )

        Text("LLM（对话）", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(llmUrl, { llmUrl = it }, label = { Text("LLM Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(llmKey, { llmKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(llmModel, { llmModel = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            llmTimeout,
            { llmTimeout = it },
            label = { Text("AI/对话超时(秒)，默认60") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            llmMaxTokens,
            { llmMaxTokens = it },
            label = { Text("max_tokens(原版默认512，可64～2048)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            llmTemp,
            { llmTemp = it },
            label = { Text("temperature(原版默认0.3)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            llmEvalBars,
            { llmEvalBars = it },
            label = { Text("评估K线条数(原版默认40，范围6～48)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用思考链thinking(默认关，开更贵)", modifier = Modifier.weight(1f))
            Switch(llmThinking, { llmThinking = it })
        }
        Text(
            "默认已恢复原版评估：prompt完整 + temp0.3 + tokens512 + K线40。仍可在此调小省钱。",
            color = MaterialTheme.colorScheme.secondary,
        )

        Text("缓存清理（不删配置）", style = MaterialTheme.typography.titleSmall)
        Text(
            "普通缓存：模拟成交/统计、内存行情信号、应用临时文件。" +
                " WebView 缓存：页面磁盘缓存；默认保留登录 Cookie。配置与策略不会删除。",
            color = MaterialTheme.colorScheme.secondary,
        )
        OutlinedButton(
            onClick = {
                HibtWebSession.clearLogs()
                msg = repo.clearDataCache()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清除普通数据缓存") }
        OutlinedButton(
            onClick = {
                msg = HibtWebSession.clearWebViewCache(keepLogin = true)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清除 WebView 缓存（保留登录）") }
        OutlinedButton(
            onClick = {
                val a = repo.clearDataCache()
                val b = HibtWebSession.clearWebViewCache(keepLogin = true)
                msg = a + "\n" + b
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("一键清除全部缓存（保留配置与登录）") }
        OutlinedButton(
            onClick = {
                msg = HibtWebSession.clearWebViewCache(keepLogin = false)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清除WV缓存并退出登录") }

        Text("实时模拟与自动调优", style = MaterialTheme.typography.titleSmall)
        Row {
            Text("启用实时模拟（策略信号）", modifier = Modifier.weight(1f))
            Switch(liveSim, { liveSim = it })
        }
        Text(
            "AI评估开：仅过阈值进模拟；AI评估关：全部信号模拟。结果在策略面板查看。",
            color = MaterialTheme.colorScheme.secondary,
        )
        Row {
            Text("模拟不佳时自动 LLM 调优", modifier = Modifier.weight(1f))
            Switch(liveSimAuto, { liveSimAuto = it })
        }
        OutlinedTextField(
            value = liveSimWr,
            onValueChange = { liveSimWr = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("调优触发：胜率低于(%)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = liveSimLoss,
            onValueChange = { liveSimLoss = it.filter { c -> c.isDigit() } },
            label = { Text("调优触发：连续亏损笔数") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = liveSimMinTrades,
            onValueChange = { liveSimMinTrades = it.filter { c -> c.isDigit() } },
            label = { Text("至少模拟几笔后才允许自动调优") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text("后台", style = MaterialTheme.typography.titleSmall)
        Row {
            Text("默认静默后台监控", modifier = Modifier.weight(1f))
            Switch(bg, { bg = it })
        }
        Row {
            Text("信号通知震动（默认开）", modifier = Modifier.weight(1f))
            Switch(vibrate, { vibrate = it })
        }
        Text(
            "开启后信号通知使用震动节奏；可在系统设置中允许本应用通知与震动。",
            color = MaterialTheme.colorScheme.secondary,
        )

        Button({
            val s = cur.copy(
                binanceBaseUrl = binance.trim(),
                symbol = symbol.trim().uppercase(),
                interval = interval.trim(),
                klineLimit = limit.toIntOrNull()?.coerceIn(200, 1000) ?: 1000,
                llmBaseUrl = llmUrl.trim(),
                llmApiKey = llmKey.trim(),
                llmModel = llmModel.trim(),
                llmTimeoutSec = llmTimeout.toIntOrNull()?.coerceIn(10, 300) ?: 60,
                llmMaxTokens = llmMaxTokens.toIntOrNull()?.coerceIn(64, 2048) ?: 512,
                llmTemperature = llmTemp.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.3f,
                llmEvalMaxBars = llmEvalBars.toIntOrNull()?.coerceIn(6, 48) ?: 40,
                llmThinkingEnabled = llmThinking,
                backgroundEnabled = bg,
                notifyVibrate = vibrate,
                liveSimEnabled = liveSim,
                liveSimAutoRetune = liveSimAuto,
                liveSimMinWinRatePct = liveSimWr.toDoubleOrNull()?.coerceIn(1.0, 99.0) ?: 48.0,
                liveSimMaxConsecutiveLosses = liveSimLoss.toIntOrNull()?.coerceIn(2, 10) ?: 3,
                liveSimMinTradesBeforeRetune = liveSimMinTrades.toIntOrNull()?.coerceIn(3, 50) ?: 5,
                onboardingDone = true,
            )
            repo.saveSettings(s)
            if (bg && s.strategyRunning) MonitorService.start(ctx) else if (!bg) MonitorService.stop(ctx)
            msg = "已保存"
        }, Modifier.fillMaxWidth()) { Text("保存") }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
