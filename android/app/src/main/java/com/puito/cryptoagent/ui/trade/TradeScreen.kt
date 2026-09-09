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
import com.puito.cryptoagent.data.*
import java.util.UUID

@Composable
fun TradeScreen(repo: Repository) {
    var list by remember { mutableStateOf(repo.strategies()) }
    var editing by remember { mutableStateOf<StrategyConfig?>(null) }

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
        Text("同时仅一套启用 · 同侧多条件 OR", color = MaterialTheme.colorScheme.secondary)
        LazyColumn {
            items(list, key = { it.id }) { cfg ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cfg.title, style = MaterialTheme.typography.titleSmall)
                            Text(if (cfg.enabled) "已启用" else "未启用", color = MaterialTheme.colorScheme.secondary)
                        }
                        TextButton({
                            list = list.map { it.copy(enabled = it.id == cfg.id) }
                            repo.saveStrategies(list)
                        }) { Text(if (cfg.enabled) "已启用" else "启用") }
                        TextButton({ editing = cfg }) { Text("编辑") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditStrategy(cfg: StrategyConfig, onBack: () -> Unit, onSave: (StrategyConfig) -> Unit) {
    var title by remember { mutableStateOf(cfg.title) }
    var buy by remember { mutableStateOf(cfg.buyRules) }
    var sell by remember { mutableStateOf(cfg.sellRules) }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        TextButton(onBack) { Text("← 返回") }
        OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
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
