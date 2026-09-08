package com.puito.cryptoapp.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puito.cryptoapp.data.AppRepository
import com.puito.cryptoapp.data.model.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditScreen(repo: AppRepository, id: String, onBack: () -> Unit) {
    val existing = remember(id) {
        if (id == "new") null else repo.loadConfigs().find { it.id == id }
    }
    var title by remember { mutableStateOf(existing?.title ?: "新策略") }
    var buyRules by remember {
        mutableStateOf(existing?.buyRules?.toMutableList() ?: mutableListOf(Rule(IndicatorType.RSI, CompareOp.LT, 30.0)))
    }
    var sellRules by remember {
        mutableStateOf(existing?.sellRules?.toMutableList() ?: mutableListOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("买入（条件之间 OR）", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            buyRules.forEachIndexed { index, _ ->
                key(index) {
                    RuleEditor(buyRules[index]) { nr ->
                        buyRules = buyRules.toMutableList().also { it[index] = nr }
                    }
                }
            }
            TextButton(onClick = {
                buyRules = buyRules.toMutableList().also { it.add(Rule()) }
            }) {
                Icon(Icons.Default.Add, null)
                Text("买入条件")
            }

            Spacer(Modifier.height(16.dp))
            Text("卖出（条件之间 OR）", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            sellRules.forEachIndexed { index, _ ->
                key("s$index") {
                    RuleEditor(sellRules[index]) { nr ->
                        sellRules = sellRules.toMutableList().also { it[index] = nr }
                    }
                }
            }
            TextButton(onClick = {
                sellRules = sellRules.toMutableList().also { it.add(Rule(IndicatorType.RSI, CompareOp.GT, 70.0)) }
            }) {
                Icon(Icons.Default.Add, null)
                Text("卖出条件")
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val list = repo.loadConfigs().toMutableList()
                    val cfg = StrategyConfig(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        title = title,
                        enabled = existing?.enabled ?: false,
                        buyRules = buyRules,
                        sellRules = sellRules,
                    )
                    val idx = list.indexOfFirst { it.id == cfg.id }
                    if (idx >= 0) list[idx] = cfg else list.add(cfg)
                    repo.saveConfigs(list)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
        }
    }
}

@Composable
private fun RuleEditor(rule: Rule, onChange: (Rule) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EnumDropdown(IndicatorType.entries, rule.indicator, { it.label }) {
            onChange(rule.copy(indicator = it))
        }
        EnumDropdown(CompareOp.entries, rule.op, { it.label }) {
            onChange(rule.copy(op = it))
        }
        var text by remember(rule.value) { mutableStateOf(rule.value.toString()) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                it.toDoubleOrNull()?.let { v -> onChange(rule.copy(value = v)) }
            },
            modifier = Modifier.width(88.dp),
            singleLine = true,
        )
    }
}

@Composable
private fun <T> EnumDropdown(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label(selected)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            items.forEach {
                DropdownMenuItem(text = { Text(label(it)) }, onClick = { onSelect(it); expanded = false })
            }
        }
    }
}

