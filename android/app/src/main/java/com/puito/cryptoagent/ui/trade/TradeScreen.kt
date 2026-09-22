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
import androidx.compose.ui.focus.onFocusChanged
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
    var goalDialog by remember { mutableStateOf<Pair<String?, String>?>(null) } // baseId to title
    var goalDraft by remember { mutableStateOf("") }

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
            onLlmTune = { cfg, goal ->
                val n = list.toMutableList()
                val i = n.indexOfFirst { it.id == cfg.id }
                if (i >= 0) n[i] = cfg else n.add(cfg)
                repo.saveStrategies(n)
                list = n
                scope.launch {
                    busy = true
                    progress = "基于「${cfg.title}」LLM优化中…"
                    report = null
                    val r = repo.optimizeStrategyWithLlm(
                        baseId = cfg.id, rounds = 2, userGoal = goal,
                    ) { progress = it }
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
        // 实时模拟（过 AI 阈值；AI 关则全信号）
        val ls = repo.liveSimStats
        val consec = repo.consecutiveLosses()
        val app = repo.settings()
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("实时模拟（策略信号）", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (ls.trades == 0) "暂无已平仓模拟。开仓后需等该周期 K 线收盘才结算。"
                    else "${ls.trades}笔 胜${ls.wins}负${ls.losses} 胜率${"%.1f".format(ls.winRate * 100)}% " +
                        "收益${"%.2f".format(ls.totalReturnPct)}% 连亏$consec",
                    fontSize = 12.sp,
                )
                Text(
                    "规则: 信号周期独立结算 · 下一根开盘开仓 · 该根收盘平仓（未收盘不计盈亏）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "调优阈值: 胜率<${"%.0f".format(app.liveSimMinWinRatePct)}% 或连亏≥${app.liveSimMaxConsecutiveLosses} " +
                        (if (app.liveSimAutoRetune) "· 自动调优开" else "· 自动调优关"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (repo.liveSimTrades.isNotEmpty()) {
                    Text("最近模拟", fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    repo.liveSimTrades.takeLast(5).asReversed().forEach { t ->
                        Text(
                            "${if (t.win) "✓" else "✗"} ${t.side} ${t.interval} ${"%.2f".format(t.pnlPct)}%",
                            fontSize = 11.sp,
                        )
                    }
                }
                TextButton(
                    onClick = { repo.clearLiveSim() },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("清空模拟记录", fontSize = 12.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    goalDraft = defaultTuneGoal(repo, null)
                    goalDialog = null to "生成新策略"
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
        if (goalDialog != null) {
            val (baseId, title) = goalDialog!!
            AlertDialog(
                onDismissRequest = { if (!busy) goalDialog = null },
                title = { Text(if (baseId == null) "生成策略 · 填写需求" else "优化「$title」· 填写需求") },
                text = {
                    Column {
                        Text("请说明市场偏好、指标、风格等（必填，避免默认 RSI+KDJ）", fontSize = 12.sp)
                        OutlinedTextField(
                            value = goalDraft,
                            onValueChange = { goalDraft = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            placeholder = {
                                Text("例：震荡布林+RSI；或趋势 MA 金叉死叉；减少假信号…")
                            },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && goalDraft.trim().length >= 4,
                        onClick = {
                            val goal = goalDraft.trim()
                            val id = baseId
                            goalDialog = null
                            scope.launch {
                                busy = true
                                progress = if (id == null) "生成中…" else "优化中…"
                                report = null
                                val r = repo.optimizeStrategyWithLlm(
                                    baseId = id,
                                    rounds = 2,
                                    userGoal = goal,
                                ) { progress = it }
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
                    ) { Text("开始") }
                },
                dismissButton = {
                    TextButton(onClick = { goalDialog = null }, enabled = !busy) { Text("取消") }
                },
            )
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
                                Text(
                                    summarizeRules(cfg),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 3,
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
                                    goalDraft = defaultTuneGoal(repo, cfg)
                                    goalDialog = cfg.id to cfg.title
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


private fun defaultTuneGoal(repo: Repository, cfg: StrategyConfig?): String {
    val st = repo.liveSimStats
    val consec = repo.consecutiveLosses()
    val sim = if (st.trades > 0)
        "参考实时模拟${st.trades}笔胜率${"%.1f".format(st.winRate * 100)}%连亏$consec。"
    else ""
    if (cfg == null) {
        return "生成事件合约策略，优先 ALGO 顺势/皮尔逊，轮次4，目标胜率55%，最少15笔。" + sim
    }
    return when (cfg.kind) {
        StrategyKind.ALGO ->
            "优化「${cfg.title}」算法${cfg.algoId} 参数${cfg.algoParams} ${cfg.algoNote}。" +
                sim + "直接调整 algoParams 数值，轮次4，目标胜率55%，最少15笔。"
        else ->
            "优化「${cfg.title}」指标规则（买${cfg.buyRules.size}/卖${cfg.sellRules.size}）。" +
                sim + "可改为 ALGO 或改阈值，轮次4，目标胜率55%，最少15笔。"
    }
}

private fun summarizeRules(cfg: StrategyConfig): String {
    if (cfg.kind == StrategyKind.ALGO) {
        val p = cfg.algoParams.entries.take(6).joinToString(" ") { "${it.key}=${fmtNum(it.value)}" }
        return "算法:${AlgoIds.label(cfg.algoId)} $p\n${cfg.algoNote.take(60)}"
    }
    fun one(r: Rule) = "${r.indicator.label}${r.op.label}${fmtNum(r.value)}(p${r.period})"
    val b = cfg.buyRules.take(3).joinToString(" | ") { one(it) }
    val s = cfg.sellRules.take(3).joinToString(" | ") { one(it) }
    return "买: $b\n卖: $s"
}


@Composable
private fun EditStrategy(
    cfg: StrategyConfig,
    onBack: () -> Unit,
    onSave: (StrategyConfig) -> Unit,
    onLlmTune: (StrategyConfig, String) -> Unit,
) {
    var title by remember { mutableStateOf(cfg.title) }
    var kind by remember { mutableStateOf(cfg.kind) }
    var algoId by remember { mutableStateOf(cfg.algoId) }
    var algoNote by remember { mutableStateOf(cfg.algoNote) }
    var paramsText by remember {
        mutableStateOf(cfg.algoParams.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var buy by remember { mutableStateOf(cfg.buyRules) }
    var sell by remember { mutableStateOf(cfg.sellRules) }
    var tuneGoal by remember { mutableStateOf("") }
    LaunchedEffect(cfg.id, cfg.title, cfg.kind, cfg.algoId, cfg.algoNote, cfg.algoParams, cfg.buyRules, cfg.sellRules) {
        title = cfg.title
        kind = cfg.kind
        algoId = cfg.algoId
        algoNote = cfg.algoNote
        paramsText = cfg.algoParams.entries.joinToString("\n") { "${it.key}=${it.value}" }
        buy = cfg.buyRules
        sell = cfg.sellRules
    }
    fun parseParams(text: String): Map<String, Double> {
        val m = linkedMapOf<String, Double>()
        text.lines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || !t.contains("=")) return@forEach
            val i = t.indexOf("=")
            val k = t.substring(0, i).trim()
            val v = t.substring(i + 1).trim().toDoubleOrNull() ?: return@forEach
            if (k.isNotEmpty()) m[k] = v
        }
        return m
    }
    fun currentCfg() = cfg.copy(
        title = title.ifBlank { cfg.title },
        kind = kind,
        algoId = algoId,
        algoNote = algoNote,
        algoParams = parseParams(paramsText),
        buyRules = buy.ifEmpty { listOf(Rule()) },
        sellRules = sell.ifEmpty { listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14)) },
    )
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        TextButton(onBack) { Text("← 返回") }
        OutlinedTextField(
            title, { title = it },
            label = { Text("策略标题") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("类型", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == StrategyKind.ALGO, onClick = { kind = StrategyKind.ALGO }, label = { Text("算法配置") })
            FilterChip(selected = kind == StrategyKind.RULES, onClick = { kind = StrategyKind.RULES }, label = { Text("指标规则") })
        }
        if (kind == StrategyKind.ALGO) {
            Text(
                "算法由引擎实现；可手改参数，或让 LLM 按需求选算法并调参（顺势/皮尔逊三曲线/突破）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                AlgoIds.all.forEach { id ->
                    FilterChip(
                        selected = algoId == id,
                        onClick = { algoId = id },
                        label = { Text(AlgoIds.label(id), fontSize = 11.sp) },
                    )
                }
            }
            OutlinedTextField(
                algoNote, { algoNote = it },
                label = { Text("算法说明") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                paramsText, { paramsText = it },
                label = { Text("参数 每行 key=value") },
                placeholder = {
                    Text(
                        when (algoId) {
                            AlgoIds.PEARSON_TRIPLE -> "p1=5\np2=10\np3=20\nwindow=30\nminCorr=0.55\ncooldown=2"
                            AlgoIds.BREAKOUT -> "lookback=20\ncooldown=5"
                            else -> "fast=12\nslow=26\nconsecutive=3\ncooldown=3"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
        } else {
            Text(
                "指标规则：同侧多条件 OR。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text("买入（OR）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            buy.forEachIndexed { i, rule ->
                RuleEditor(
                    index = i + 1,
                    rule = rule,
                    onChange = { nr -> buy = buy.toMutableList().also { it[i] = nr } },
                    onDelete = { buy = buy.toMutableList().also { it.removeAt(i) } },
                )
            }
            TextButton({ buy = buy + Rule() }) { Text("+ 买入条件") }
            Text("卖出（OR）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            sell.forEachIndexed { i, rule ->
                RuleEditor(
                    index = i + 1,
                    rule = rule,
                    onChange = { nr -> sell = sell.toMutableList().also { it[i] = nr } },
                    onDelete = { sell = sell.toMutableList().also { it.removeAt(i) } },
                )
            }
            TextButton({ sell = sell + Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14) }) { Text("+ 卖出条件") }
        }
        OutlinedTextField(
            value = tuneGoal,
            onValueChange = { tuneGoal = it },
            label = { Text("LLM 调优需求（必填）") },
            placeholder = { Text("例：单边行情顺势；或皮尔逊三曲线提高相关阈值") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            minLines = 2,
        )
        OutlinedButton(
            onClick = {
                if (tuneGoal.trim().length >= 4) onLlmTune(currentCfg(), tuneGoal.trim())
            },
            enabled = tuneGoal.trim().length >= 4,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("LLM 编写/优化本策略") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSave(currentCfg()) }, modifier = Modifier.fillMaxWidth()) { Text("保存策略") }
    }
}

@Composable
private fun RuleEditor(
    index: Int,
    rule: Rule,
    onChange: (Rule) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("条件 #$index", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("删除") }
            }
            Text(
                "摘要: ${rule.indicator.label} ${rule.op.label} ${fmtNum(rule.value)} · period=${rule.period}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1.2f)) {
                    EnumDrop(IndicatorType.entries, rule.indicator, { it.label }) {
                        onChange(rule.copy(indicator = it))
                    }
                }
                Box(Modifier.weight(1f)) {
                    EnumDrop(CompareOp.entries, rule.op, { it.label }) {
                        onChange(rule.copy(op = it))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SoftDoubleField(
                    value = rule.value,
                    onCommit = { onChange(rule.copy(value = it)) },
                    label = "阈值",
                    modifier = Modifier.weight(1f),
                )
                SoftIntField(
                    value = rule.period,
                    onCommit = { onChange(rule.copy(period = it.coerceIn(2, 200))) },
                    label = "周期period",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 数字输入：允许清空/中间态，不强制回填默认值；合法数字才回写 */
@Composable
private fun SoftDoubleField(
    value: Double,
    onCommit: (Double) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(fmtNum(value)) }
    LaunchedEffect(value, focused) {
        if (!focused) text = fmtNum(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || it == '.' || it == '-' }
            // 最多一个小数点、一个负号且仅在开头
            val norm = buildString {
                var dot = false
                filtered.forEachIndexed { i, c ->
                    when (c) {
                        '-' -> if (i == 0 && isEmpty()) append(c)
                        '.' -> if (!dot) { append(c); dot = true }
                        else -> append(c)
                    }
                }
            }
            text = norm
            norm.toDoubleOrNull()?.let { onCommit(it) }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}

@Composable
private fun SoftIntField(
    value: Int,
    onCommit: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val norm = raw.filter { it.isDigit() }
            text = norm
            norm.toIntOrNull()?.let { onCommit(it) }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}

private fun fmtNum(v: Double): String {
    return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

@Composable
private fun <T> EnumDrop(items: List<T>, sel: T, label: (T) -> String, on: (T) -> Unit) {
    var e by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { e = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) { Text(label(sel), maxLines = 1) }
        DropdownMenu(e, { e = false }) {
            items.forEach {
                DropdownMenuItem(
                    text = { Text(label(it)) },
                    onClick = { on(it); e = false },
                )
            }
        }
    }
}
