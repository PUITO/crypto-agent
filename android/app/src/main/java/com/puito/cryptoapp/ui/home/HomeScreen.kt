package com.puito.cryptoapp.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.puito.cryptoapp.data.AppRepository
import com.puito.cryptoapp.data.model.Interval
import com.puito.cryptoapp.service.StrategyMonitorService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Composable
fun HomeScreen(
    repo: AppRepository,
    onConfig: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { repo.loadSettings() }
    var symbol by remember { mutableStateOf(settings.defaultSymbol) }
    var interval by remember { mutableStateOf(Interval.fromCode(settings.defaultInterval)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(repo.strategyRunning) }
    var bgOn by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            try {
                repo.refreshCandles(symbol, interval, repo.loadSettings().defaultLimit)
                repo.restoreSim(interval)
                running = repo.strategyRunning
                tick++
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(symbol, interval) { reload() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SymbolPicker(symbol) { symbol = it }
            IntervalPicker(interval) { interval = it }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onConfig) { Text("配置") }
            TextButton(onClick = onSettings) { Text("设置") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (running) "策略：运行中" else "策略：已停止",
                color = if (running) Color(0xFF0ECB81) else MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(8.dp))
            if (!running) {
                Button(onClick = {
                    repo.startStrategy(symbol, interval).onSuccess {
                        running = true
                        tick++
                    }.onFailure { error = it.message }
                }) { Text("启动策略") }
            } else {
                OutlinedButton(onClick = {
                    repo.stopStrategy(interval)
                    running = false
                    if (bgOn) {
                        StrategyMonitorService.stop(context)
                        bgOn = false
                    }
                    tick++
                }) { Text("停止并清除") }
            }
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = bgOn,
                onClick = {
                    if (!bgOn) {
                        if (!running) {
                            error = "请先启动策略再开后台监控"
                            return@FilterChip
                        }
                        StrategyMonitorService.start(context)
                        bgOn = true
                    } else {
                        StrategyMonitorService.stop(context)
                        bgOn = false
                    }
                },
                label = { Text(if (bgOn) "后台开" else "后台关") },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload() }, enabled = !loading) {
                Text(if (loading) "加载中…" else "刷新")
            }
        }

        error?.let {
            Text(it, color = Color(0xFFF6465D), modifier = Modifier.padding(12.dp))
        }

        Text(
            "坐标轴 · 双指缩放 · 拖动平移 · 双击重置 · 绿B/红S",
            modifier = Modifier.padding(horizontal = 12.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
        )

        key(tick, symbol, interval) {
            ScrollableCandleChart(
                candles = repo.candles,
                signals = if (running) repo.signals else emptyList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(8.dp),
            )
        }

        // 统计 = 全部模拟成交（不是下面列表的 5 条）
        val allTrades = repo.trades
        val st = remember(tick, allTrades.size, running) {
            val wins = allTrades.count { it.win }
            val losses = allTrades.size - wins
            com.puito.cryptoapp.data.model.BacktestStats(
                signals = allTrades.size,
                wins = wins,
                losses = losses,
                winRate = if (allTrades.isEmpty()) 0.0 else wins.toDouble() / allTrades.size,
                totalReturnPct = allTrades.sumOf { it.pnlPct },
            )
        }
        Text(
            "统计（全部 ${allTrades.size} 笔模拟）：胜 ${st.wins} | 负 ${st.losses} | 胜率 ${"%.1f".format(st.winRate * 100)}% | 累计收益 ${"%.2f".format(st.totalReturnPct)}%",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 13.sp,
        )

        Text(
            "交易历史（${interval.code}，仅展示最近 5 / 共 ${allTrades.size} 笔）",
            modifier = Modifier.padding(horizontal = 12.dp),
            fontWeight = FontWeight.SemiBold,
        )
        val rows = allTrades.takeLast(5).reversed()
        LazyColumn(Modifier.padding(12.dp)) {
            items(rows, key = { it.id }) { t ->
                val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(fmt.format(Date(t.entryTime)), Modifier.weight(1.2f), fontSize = 12.sp)
                    Text(
                        t.side,
                        Modifier.weight(0.4f),
                        color = if (t.side == "B") Color(0xFF0ECB81) else Color(0xFFF6465D),
                    )
                    Text("%.1f".format(t.entryPrice), Modifier.weight(0.9f), fontSize = 12.sp)
                    Text("%.1f".format(t.exitPrice), Modifier.weight(0.9f), fontSize = 12.sp)
                    Text(
                        if (t.win) "盈" else "亏",
                        Modifier.weight(0.4f),
                        color = if (t.win) Color(0xFF0ECB81) else Color(0xFFF6465D),
                        fontSize = 12.sp,
                    )
                }
                HorizontalDivider()
            }
            if (rows.isEmpty()) {
                item {
                    Text("暂无模拟成交（启动策略后生成）", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun SymbolPicker(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value.replace("USDT", "/USDT")) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf("BTCUSDT", "ETHUSDT").forEach {
                DropdownMenuItem(text = { Text(it) }, onClick = { onChange(it); expanded = false })
            }
        }
    }
}

@Composable
private fun IntervalPicker(value: Interval, onChange: (Interval) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value.code) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Interval.entries.forEach {
                DropdownMenuItem(text = { Text(it.code) }, onClick = { onChange(it); expanded = false })
            }
        }
    }
}
