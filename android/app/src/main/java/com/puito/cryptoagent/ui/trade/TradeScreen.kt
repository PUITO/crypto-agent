package com.puito.cryptoagent.ui.trade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puito.cryptoagent.data.*
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun TradeScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(repo.strategies()) }
    var editing by remember { mutableStateOf<StrategyConfig?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<String?>(null) }

    if (editing != null) {
        EditStrategy(
            editing!!,
            onBack = { editing = null },
            onSave = { cfg ->
                val n = list.toMutableList()
                val i = n.indexOfFirst { it.id == cfg.id }
                if (i >= 0) n[i] = cfg else n.add(cfg)
                repo.saveStrategies(n)
                list = n
                editing = null
            },
            onLlmTune = { cfg ->
                // 先落盘再优化，避免编辑器未保存规则
                val n = list.toMutableList()
                val i = n.indexOfFirst { it.id == cfg.id }
                if (i >= 0) n[i] = cfg else n.add(cfg)
                repo.saveStrategies(n)
                list = n
                scope.launch {
                    busy = true
                    progress = "基于「${cfg.title}」LLM优化中…"
                    report = null
                    val r = repo.optimizeStrategyWithLlm(baseId = cfg.id, rounds = 2) { progress = it }
                    r.onSuccess {
                        list = repo.strategies()
                        report = it.report
                        editing = list.find { s -> s.id == it.strategy.id } ?: it.strategy
                    }.onFailure {
                        report = "失败: ${it.message}"
                    }
                    busy = false
                    progress = null
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("策略配置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({
                editing = StrategyConfig(UUID.randomUUID().toString(), "新策略", false)
            }) { Icon(Icons.Default.Add, null) }
        }
        Text("同时仅一套启用 · 同侧多条件 OR", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
        Text(
            "LLM可基于历史K线回测迭代优化；生成新策略或更新现有策略。",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        progress = "LLM 生成新策略…"
                        report = null
                        val r = repo.optimizeStrategyWithLlm(baseId = null, rounds = 2) { progress = it }
                        r.onSuccess {
                            list = repo.strategies()
                            report = it.report
                        }.onFailure {
                            report = "失败: ${it.message}"
                        }
                        busy = false
                        progress = null
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "优化中…" else "LLM生成新策略") }
        }
        progress?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        report?.let {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(10.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    Text("优化报告", style = MaterialTheme.typography.titleSmall)
                    Text(it, fontSize = 11.sp)
                }
            }
        }
        LazyColumn {
            items(list, key = { it.id }) { cfg ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cfg.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (cfg.enabled) "已启用" else "未启用",
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    "买${cfg.buyRules.size} / 卖${cfg.sellRules.size}",
                                    fontSize = 11.sp,
                                )
                            }
                            Switch(
                                checked = cfg.enabled,
                                onCheckedChange = { on ->
                                    val n = list.map {
                                        if (it.id == cfg.id) it.copy(enabled = on)
                                        else if (on) it.copy(enabled = false) else it
                                    }
                                    repo.saveStrategies(n)
                                    list = n
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton({ editing = cfg }) { Text("编辑") }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        progress = "优化「${cfg.title}」…"
                                        report = null
                                        val r = repo.optimizeStrategyWithLlm(baseId = cfg.id, rounds = 2) {
                                            progress = it
                                        }
                                        r.onSuccess {
                                            list = repo.strategies()
                                            report = it.report
                                        }.onFailure {
                                            report = "失败: ${it.message}"
                                        }
                                        busy = false
                                        progress = null
                                    }
                                },
                                enabled = !busy,
                            ) { Text("LLM优化") }
                            TextButton({
                                val n = list.filter { it.id != cfg.id }
                                repo.saveStrategies(n)
                                list = n
                            }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditStrategy(
    cfg: StrategyConfig,
    onBack: () -> Unit,
    onSave: (StrategyConfig) -> Unit,
    onLlmTune: (StrategyConfig) -> Unit,
) {
    var title by remember { mutableStateOf(cfg.title) }
    var buy by remember { mutableStateOf(cfg.buyRules) }
    var sell by remember { mutableStateOf(cfg.sellRules) }
    // refresh when LLM updates cfg
    LaunchedEffect(cfg.id, cfg.buyRules, cfg.sellRules, cfg.title) {
        title = cfg.title
        buy = cfg.buyRules
        sell = cfg.sellRules
    }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        TextButton(onBack) { Text("← 返回") }
        OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onLlmTune(cfg.copy(title = title, buyRules = buy, sellRules = sell)) }) {
                Text("LLM调优本策略")
            }
        }
        Text("买入（OR）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        buy.forEachIndexed { i, rule ->
            RuleEditor(rule) { nr -> buy = buy.toMutableList().also { it[i] = nr } }
        }
        TextButton({ buy = buy + Rule() }) { Text("+ 买入条件") }
        Text("卖出（OR）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        sell.forEachIndexed { i, rule ->
            RuleEditor(rule) { nr -> sell = sell.toMutableList().also { it[i] = nr } }
        }
        TextButton({ sell = sell + Rule(IndicatorType.RSI, CompareOp.GT, 70.0) }) { Text("+ 卖出条件") }
        Spacer(Modifier.height(16.dp))
        Button(
            { onSave(cfg.copy(title = title, buyRules = buy, sellRules = sell)) },
            Modifier.fillMaxWidth(),
        ) { Text("保存") }
    }
}

@Composable
private fun RuleEditor(rule: Rule, onChange: (Rule) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        EnumDrop(IndicatorType.entries, rule.indicator, { it.label }) { onChange(rule.copy(indicator = it)) }
        EnumDrop(CompareOp.entries, rule.op, { it.label }) { onChange(rule.copy(op = it)) }
        var t by remember(rule.value) { mutableStateOf(rule.value.toString()) }
        OutlinedTextField(
            t,
            {
                t = it
                it.toDoubleOrNull()?.let { v -> onChange(rule.copy(value = v)) }
            },
            Modifier.width(88.dp),
            singleLine = true,
        )
    }
}

@Composable
private fun <T> EnumDrop(items: List<T>, sel: T, label: (T) -> String, on: (T) -> Unit) {
    var e by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ e = true }) { Text(label(sel)) }
        DropdownMenu(e, { e = false }) {
            items.forEach { DropdownMenuItem({ Text(label(it)) }, { on(it); e = false }) }
        }
    }
}
