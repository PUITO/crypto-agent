package com.puito.cryptoagent.ui.trade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
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
    var showModelMgr by remember { mutableStateOf(false) }
    var modelMgrMsg by remember { mutableStateOf<String?>(null) }
    var evalMap by remember { mutableStateOf(repo.strategyEvalCache) }
    var goalDraft by remember { mutableStateOf("") }

    if (editing != null) {
        EditStrategy(
            repo,
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
                        baseId = cfg.id,
                        rounds = null, // 使用设置 llmOptimizeRounds
                        userGoal = goal,
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
            onTrainModel = { cfg ->
                scope.launch {
                    busy = true
                    progress = "训练+校准模型「${cfg.title}」…"
                    report = null
                    // 先保存当前编辑参数
                    val n0 = list.toMutableList()
                    val i0 = n0.indexOfFirst { it.id == cfg.id }
                    if (i0 >= 0) n0[i0] = cfg else n0.add(cfg)
                    repo.saveStrategies(n0)
                    repo.trainModelStrategy(cfg.id).fold(
                        onSuccess = {
                            list = repo.strategies()
                            report = it
                            editing = list.find { s -> s.id == cfg.id }
                            progress = null
                        },
                        onFailure = {
                            report = "训练失败: ${it.message}"
                            progress = null
                        },
                    )
                    busy = false
                }
            },
            onClearModel = { cfg ->
                repo.clearModelWeights(cfg.id)
                list = repo.strategies()
                editing = list.find { s -> s.id == cfg.id }
                report = "已清除模型权重"
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("策略配置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { showModelMgr = true }) { Text("模型管理") }
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
        Text(
            "统一模拟仓已移至「行情」页（1m确认+周期信号合并统计）。策略页专注配置。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    goalDraft = defaultTuneGoal(repo, null)
                    goalDialog = null to "生成新策略"
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "优化中…" else "LLM生成新策略") }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        progress = "评估策略胜率…"
                        report = null
                        runCatching {
                            repo.evaluateStrategiesWinRates(list) { progress = it }
                        }.onSuccess { m ->
                            evalMap = m
                            list = repo.strategies() // 读回已持久化的胜率
                            val lines = list.map { cfg ->
                                if (cfg.lastEvalTrades <= 0) "「${cfg.title}」无有效回测"
                                else "「${cfg.title}」${cfg.lastEvalTrades}笔 胜率${"%.1f".format(cfg.lastWinRatePct)}% ${cfg.lastEvalDetail}"
                            }
                            report = "胜率已写入策略（标题旁持久显示）\n" + lines.joinToString("\n")
                        }.onFailure {
                            report = "评估失败: ${it.message}"
                        }
                        busy = false
                        progress = null
                    }
                },
                enabled = !busy && list.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("评估胜率") }
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
        
    if (showModelMgr) {
        val cat = remember { repo.offlineModels.effectiveCatalog() }
        var tickMgr by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { showModelMgr = false },
            title = { Text("离线模型管理") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "下载完成后：策略列表「+」新建 → 类型选「模型」→ 点选「离线·xxx·已装」→ 保存 → 启用。融合包=分类器+金融因子(+可选在线AI)。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    modelMgrMsg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = {
                        scope.launch {
                            modelMgrMsg = "刷新目录…"
                            repo.offlineModels.refreshCatalog()
                                .onSuccess { modelMgrMsg = "目录已更新 ${it.models.size} 个模型"; tickMgr++ }
                                .onFailure { modelMgrMsg = "刷新失败: ${it.message}" }
                        }
                    }) { Text("从服务器刷新模型列表") }
                    key(tickMgr) {
                        cat.models.forEach { m ->
                            val installed = repo.offlineModels.isInstalled(m.id)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(m.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                    Text(
                                        if (installed) "已安装 · ${m.id}" else "未安装 · ${m.sizeBytes}B · ${m.sha256.take(8)}…",
                                        fontSize = 10.sp,
                                    )
                                }
                                if (installed) {
                                    TextButton(onClick = {
                                        repo.offlineModels.delete(m.id)
                                        modelMgrMsg = "已删除 ${m.name}"
                                        tickMgr++
                                    }) { Text("删除") }
                                } else {
                                    Button(onClick = {
                                        scope.launch {
                                            modelMgrMsg = "下载 ${m.name}…"
                                            repo.offlineModels.download(m.id)
                                                .onSuccess { modelMgrMsg = "已安装 ${it.name}"; tickMgr++ }
                                                .onFailure { modelMgrMsg = "失败: ${it.message}" }
                                        }
                                    }) { Text("下载") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Text("在线", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        cat.online?.desc ?: "配置 LLM 后可在策略中选「在线 AI 预测」",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelMgr = false }) { Text("关闭") }
            },
        )
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
                                    rounds = null,
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cfg.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                    if (cfg.lastEvalTrades > 0 && cfg.lastWinRatePct >= 0) {
                                        Text(
                                            "  ${"%.0f".format(cfg.lastWinRatePct)}%",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                val ivLabel = if (cfg.tradeIntervals.isEmpty()) "周期:跟随行情"
                                else "周期:" + cfg.tradeIntervals.joinToString(",")
                                Text(ivLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                if (cfg.lastEvalTrades > 0 && cfg.lastWinRatePct >= 0) {
                                    Text(
                                        "胜率 ${"%.1f".format(cfg.lastWinRatePct)}% · ${cfg.lastEvalTrades}笔 · 收益${"%.1f".format(cfg.lastEvalReturnPct)}%",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (cfg.lastEvalDetail.isNotBlank()) {
                                        Text(cfg.lastEvalDetail, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                                    }
                                } else {
                                    Text(
                                        "胜率未评估（点上方「评估胜率」后写入本策略）",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                Text(
                                    if (cfg.enabled) "已启用" else "未启用",
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    when (cfg.kind) {
                                        StrategyKind.MODEL -> "模型 · ${ModelIds.label(cfg.modelId)}"
                                        StrategyKind.ALGO -> "算法 · ${AlgoIds.label(cfg.algoId)}"
                                        else -> "买${cfg.buyRules.size} / 卖${cfg.sellRules.size}"
                                    },
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
                            if (cfg.kind == StrategyKind.MODEL) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            progress = "训练+校准「${cfg.title}」…"
                                            repo.trainModelStrategy(cfg.id).fold(
                                                onSuccess = {
                                                    list = repo.strategies()
                                                    report = it
                                                },
                                                onFailure = { report = "训练失败: ${it.message}" },
                                            )
                                            busy = false
                                            progress = null
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("训练") }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            progress = "校准参数「${cfg.title}」…"
                                            repo.calibrateModelStrategy(cfg.id).fold(
                                                onSuccess = {
                                                    list = repo.strategies()
                                                    report = it
                                                },
                                                onFailure = { report = "校准失败: ${it.message}" },
                                            )
                                            busy = false
                                            progress = null
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("校准") }
                            }
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
        StrategyKind.MODEL ->
            "优化「${cfg.title}」为高胜率少信号的 MODEL 策略。" +
                "必须提高或保持严格参数：threshold 0.62~0.72、cooldown 10~24、minEdge 0.03~0.08。" +
                "禁止 threshold<0.58 或 cooldown<8（会导致每根K都信号）。" +
                "不要输出 modelWeights。权重由 App 本地训练+校准。" +
                sim + "轮次3，目标胜率≥55%，笔数适中即可。"
        StrategyKind.ALGO ->
            "优化「${cfg.title}」算法${cfg.algoId} 参数${cfg.algoParams} ${cfg.algoNote}。" +
                sim + "直接调整 algoParams 数值，轮次4，目标胜率55%，最少15笔。"
        StrategyKind.RULES ->
            "优化「${cfg.title}」指标规则，必须保持 kind=RULES，只改 buyRules/sellRules 阈值与周期。" +
                "禁止改成 ALGO 或 MODEL。" + sim + "轮次按设置，目标胜率55%。"
        else ->
            "优化「${cfg.title}」。" + sim + "轮次按设置，目标胜率55%。"
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


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditStrategy(
    repo: Repository,
    cfg: StrategyConfig,
    onBack: () -> Unit,
    onSave: (StrategyConfig) -> Unit,
    onLlmTune: (StrategyConfig, String) -> Unit,
    onTrainModel: ((StrategyConfig) -> Unit)? = null,
    onClearModel: ((StrategyConfig) -> Unit)? = null,
) {
    var title by remember { mutableStateOf(cfg.title) }
    var kind by remember { mutableStateOf(cfg.kind) }
    var algoId by remember { mutableStateOf(cfg.algoId) }
    var algoNote by remember { mutableStateOf(cfg.algoNote) }
    var paramsText by remember {
        mutableStateOf(cfg.algoParams.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var modelId by remember { mutableStateOf(cfg.modelId.ifBlank { ModelIds.LOGREG_V1 }) }
    var modelNote by remember { mutableStateOf(cfg.modelNote) }
    var modelParamsText by remember {
        mutableStateOf(
            cfg.modelParams.ifEmpty {
                mapOf(
                    "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
                    "confirmBars" to 1.0, "lookback" to 5.0,
                )
            }.entries.joinToString("\n") { "${it.key}=${it.value}" },
        )
    }
    var modelReport by remember { mutableStateOf(cfg.modelTrainReport) }
    var modelEndpoint by remember { mutableStateOf(cfg.modelEndpoint) }
    var tradeIntervalsSel by remember {
        mutableStateOf(cfg.tradeIntervals.toSet().ifEmpty { emptySet() })
    }
    var buy by remember { mutableStateOf(cfg.buyRules) }
    var sell by remember { mutableStateOf(cfg.sellRules) }
    var tuneGoal by remember { mutableStateOf("") }
    LaunchedEffect(
        cfg.id, cfg.title, cfg.kind, cfg.algoId, cfg.algoNote, cfg.algoParams,
        cfg.buyRules, cfg.sellRules, cfg.modelId, cfg.modelParams, cfg.modelTrainReport, cfg.modelWeights,
    ) {
        title = cfg.title
        kind = cfg.kind
        algoId = cfg.algoId
        algoNote = cfg.algoNote
        paramsText = cfg.algoParams.entries.joinToString("\n") { "${it.key}=${it.value}" }
        modelId = cfg.modelId.ifBlank { ModelIds.LOGREG_V1 }
        modelNote = cfg.modelNote
        modelParamsText = cfg.modelParams.ifEmpty {
            mapOf(
                "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
                "confirmBars" to 1.0, "lookback" to 5.0,
            )
        }.entries.joinToString("\n") { "${it.key}=${it.value}" }
        modelReport = cfg.modelTrainReport
        modelEndpoint = cfg.modelEndpoint
        tradeIntervalsSel = cfg.tradeIntervals.toSet()
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
        modelId = modelId.ifBlank { ModelIds.LOGREG_V1 },
        modelNote = modelNote,
        modelParams = when {
            ModelIds.isOfflinePack(modelId) -> {
                repo.offlineModels.loadPack(modelId)?.params?.ifEmpty { null }
                    ?: parseParams(modelParamsText).ifEmpty {
                        mapOf(
                            "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
                            "confirmBars" to 1.0, "lookback" to 5.0,
                        )
                    }
            }
            ModelIds.isLocked(modelId) -> parseParams(modelParamsText).ifEmpty {
                mapOf(
                    "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
                    "confirmBars" to 1.0, "lookback" to 5.0,
                )
            }
            else -> parseParams(modelParamsText).ifEmpty {
                mapOf(
                    "threshold" to 0.64, "cooldown" to 12.0, "minEdge" to 0.04,
                    "confirmBars" to 1.0, "lookback" to 5.0,
                )
            }
        },
        modelTrainReport = modelReport,
        modelWeights = if (ModelIds.isOfflinePack(modelId)) {
            repo.offlineModels.loadPack(modelId)?.weights ?: emptyMap()
        } else cfg.modelWeights,
        financeId = if (ModelIds.isOfflinePack(modelId)) {
            repo.offlineModels.loadPack(modelId)?.financeId.orEmpty()
        } else cfg.financeId,
        financeParams = if (ModelIds.isOfflinePack(modelId)) {
            repo.offlineModels.loadPack(modelId)?.financeParams ?: emptyMap()
        } else cfg.financeParams,
        wClassifier = if (ModelIds.isOfflinePack(modelId)) {
            repo.offlineModels.loadPack(modelId)?.wClassifier ?: 1.0
        } else cfg.wClassifier,
        wFinance = if (ModelIds.isOfflinePack(modelId)) {
            repo.offlineModels.loadPack(modelId)?.wFinance ?: 0.0
        } else cfg.wFinance,
        wOnline = if (ModelIds.isOfflinePack(modelId)) {
            repo.offlineModels.loadPack(modelId)?.wOnline ?: 0.0
        } else cfg.wOnline,
        buyRules = buy.ifEmpty { listOf(Rule()) },
        sellRules = sell.ifEmpty { listOf(Rule(IndicatorType.RSI, CompareOp.GT, 70.0, 14)) },
        tradeIntervals = tradeIntervalsSel.toList().sortedBy {
            listOf("5m", "10m", "30m", "1h").indexOf(it).let { i -> if (i < 0) 99 else i }
        },
        modelEndpoint = modelEndpoint.trim(),
    )
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        TextButton(onBack) { Text("← 返回") }
        OutlinedTextField(
            title, { title = it },
            label = { Text("策略标题") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("交易周期（可多选；不选=仅跟随行情当前周期，避免全量噪音）", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("5m", "10m", "30m", "1h").forEach { iv ->
                FilterChip(
                    selected = iv in tradeIntervalsSel,
                    onClick = {
                        tradeIntervalsSel = if (iv in tradeIntervalsSel) tradeIntervalsSel - iv
                        else tradeIntervalsSel + iv
                    },
                    label = { Text(iv, fontSize = 12.sp) },
                )
            }
        }
        Text(
            if (tradeIntervalsSel.isEmpty()) "当前：跟随行情选中周期"
            else "当前：仅 ${tradeIntervalsSel.sorted().joinToString(",")}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text("类型", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == StrategyKind.ALGO, onClick = { kind = StrategyKind.ALGO }, label = { Text("算法配置") })
            FilterChip(selected = kind == StrategyKind.RULES, onClick = { kind = StrategyKind.RULES }, label = { Text("指标规则") })
            FilterChip(selected = kind == StrategyKind.MODEL, onClick = { kind = StrategyKind.MODEL }, label = { Text("模型") })
        }
        when (kind) {
            StrategyKind.MODEL -> {
                Text(
                    "使用步骤：1) 返回点「模型管理」下载离线包  2) 此处类型选「模型」  3) 点选下方离线融合包  4) 保存并启用",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text("模型（含已下载离线包，可换行滚动查看）", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                val catIds = repo.offlineModels.effectiveCatalog().models.map { it.id }
                val modelChoices = (ModelIds.core + catIds + repo.offlineModels.installedIds()).distinct()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    modelChoices.forEach { id ->
                        val offline = ModelIds.isOfflinePack(id)
                        val installed = !offline || repo.offlineModels.isInstalled(id)
                        val label = buildString {
                            append(ModelIds.label(id))
                            if (offline) append(if (installed) "·已装" else "·未下")
                        }
                        FilterChip(
                            selected = modelId == id,
                            onClick = {
                                if (offline && !installed) {
                                    // 仍允许选中以提示下载
                                    modelId = id
                                } else {
                                    modelId = id
                                }
                            },
                            enabled = true,
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                if (ModelIds.isOfflinePack(modelId)) {
                    if (!repo.offlineModels.isInstalled(modelId)) {
                        Text(
                            "「${ModelIds.label(modelId)}」尚未下载：返回策略列表点「模型管理」→ 下载后再保存",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    } else {
                        val pack = repo.offlineModels.loadPack(modelId)
                        Text(
                            "已选离线融合包：分类器 + ${pack?.financeId ?: "金融因子"}（参数锁定）",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (modelId == ModelIds.REMOTE_HTTP) {
                    OutlinedTextField(
                        modelEndpoint,
                        { modelEndpoint = it },
                        label = { Text("第三方模型 API URL（POST JSON）") },
                        placeholder = { Text("https://your-api.com/score") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "请求含 features/symbol/interval；响应 score 0~1 或 side=B/S + confidence",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (!ModelIds.isLocked(modelId)) {
                    OutlinedTextField(
                        modelParamsText,
                        { modelParamsText = it },
                        label = { Text("参数 threshold/cooldown/lookback（每行 key=value）") },
                        placeholder = { Text("threshold=0.64\ncooldown=12\nminEdge=0.04\nconfirmBars=1\nlookback=5") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                } else {
                    Text(
                        "此模型参数已锁定，不可在策略中修改（避免异常）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    // 展示锁定参数只读
                    val pack = if (ModelIds.isOfflinePack(modelId)) repo.offlineModels.loadPack(modelId) else null
                    if (pack != null) {
                        Text(
                            pack.params.entries.joinToString("  ") { "${it.key}=${it.value}" },
                            fontSize = 11.sp,
                        )
                    }
                }
                OutlinedTextField(
                    modelNote,
                    { modelNote = it },
                    label = { Text("模型说明") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (cfg.modelWeights.isEmpty()) "权重：未训练"
                    else "权重：已训练 ${cfg.modelWeights.size} 项",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (modelReport.isNotBlank()) {
                    Text(modelReport, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    if (modelId == ModelIds.LOGREG_V1) {
                        Button(onClick = { onTrainModel?.invoke(currentCfg()) }) { Text("训练模型") }
                    }
                    OutlinedButton(onClick = { onClearModel?.invoke(currentCfg()) }) { Text("清除权重") }
                }
            }
            StrategyKind.ALGO -> {
                Text(
                    "算法由引擎实现；可手改参数，或让 LLM 按需求选算法并调参。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text("算法", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
            StrategyKind.RULES -> {
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
        }
        OutlinedTextField(
            value = tuneGoal,
            onValueChange = { tuneGoal = it },
            label = { Text("LLM 调优需求（必填）") },
            placeholder = { Text("例：提高 threshold；或改为顺势算法") },
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
