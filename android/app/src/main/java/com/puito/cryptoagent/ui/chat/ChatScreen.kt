package com.puito.cryptoagent.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puito.cryptoagent.data.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class Msg(val role: String, val text: String)

/** 模板行为 */
private enum class TplAction {
    SEND_CMD,
    SEND_MARKET,
    /** 选中后用户自写问题，发送时附带行情，不填输入框 */
    ASK_MARKET,
    /** 选中策略模式：输入框只写需求；发送时后台拼 prompt */
    STRATEGY_MODE,
}

private data class ChipTpl(
    val label: String,
    val action: TplAction,
    val payload: String = "",
)

private val templates = listOf(
    ChipTpl(
        "行情分析",
        TplAction.SEND_MARKET,
        "请根据附带的K线数据，分析当前趋势、关键高低点、波动与短线风险，并给出事件合约视角的注意点。",
    ),
    ChipTpl(
        "多空研判",
        TplAction.SEND_MARKET,
        "结合附带行情，判断偏多还是偏空？关键依据是什么？",
    ),
    ChipTpl(
        "支撑阻力",
        TplAction.SEND_MARKET,
        "根据附带K线指出可能的支撑与阻力区间，并说明理由。",
    ),
    ChipTpl("自定义行情问", TplAction.ASK_MARKET, "market"),
    ChipTpl("斐波那契", TplAction.SEND_CMD, "斐波那契"),
    ChipTpl("清除绘图", TplAction.SEND_CMD, "清除绘图"),
    ChipTpl("打开MA20", TplAction.SEND_CMD, "打开MA20"),
    ChipTpl("列出策略", TplAction.SEND_CMD, "列出策略"),
    ChipTpl("LLM优化当前策略", TplAction.STRATEGY_MODE, "optimize"),
    ChipTpl("LLM生成新策略", TplAction.STRATEGY_MODE, "generate"),
)

@Composable
fun ChatScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var withMarket by remember { mutableStateOf(false) }
    var marketBars by remember { mutableIntStateOf(30) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    /** null | optimize | generate — 模板只选模式，不往输入框塞字 */
    var strategyMode by remember { mutableStateOf<String?>(null) }
    val msgs = remember {
        mutableStateListOf(
            Msg(
                "assistant",
                "模板说明：\n" +
                    "· 行情分析/多空/支撑阻力 → 直接发送（带K线，不改输入框）\n" +
                    "· 自定义行情问 → 只填入输入框，改完再点发送\n" +
                    "· 斐波那契等 → 本地命令，直接执行\n" +
                    "条数芯片只改「附带根数」，不会自动发请求。",
            ),
        )
    }
    val state = rememberLazyListState()
    val s = repo.settings()

    fun sendOnce(text: String, attach: Boolean) {
        val body = text.trim()
        if (body.isEmpty()) return
        // 进行中直接忽略，避免双击/重组导致多次请求与 mutation interrupted
        if (busy) return
        busy = true
        val modeSnapshot = strategyMode
        val showLabel = when (modeSnapshot) {
            "optimize" -> "[优化策略] "
            "generate" -> "[生成策略] "
            else -> ""
        }
        val show = if (attach) "📊[${marketBars}根K线] $showLabel$body" else "$showLabel$body"
        msgs.add(Msg("user", show))
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                val composed = when (modeSnapshot) {
                    "optimize" -> if (isOptimizeStrategyCmd(body.trim())) body.trim()
                    else "优化策略：" + body.trim()
                    "generate" -> if (isGenerateStrategyCmd(body.trim())) body.trim()
                    else "生成策略：" + body.trim()
                    else -> body.trim()
                }
                val trimmed = composed.trim()
                val reply = when {
                    isOptimizeStrategyCmd(trimmed) || modeSnapshot == "optimize" -> {
                        val hints = withDefaults(
                            parseOptimizeHints(
                                stripStrategyPrefix(trimmed, listOf("优化策略：", "优化策略:", "优化策略", "LLM优化策略")),
                            ),
                            defaultGoal = "在现有策略上提高胜率与稳定性；若当前是指标规则必须保持 RULES 只调阈值，若是算法则只调 algoParams",
                            defaultRounds = repo.settings().llmOptimizeRounds.coerceIn(1, 8),
                        )
                        val en = repo.strategies().find { it.enabled } ?: repo.strategies().firstOrNull()
                        if (en == null) {
                            "没有可优化的策略，请先在策略页新建。"
                        } else {
                            val head = buildString {
                                append("优化「${en.title}」· 轮次${hints.rounds}")
                                hints.minWinRatePct?.let { append(" · 目标胜率≥${"%.0f".format(it)}%") }
                                hints.minTrades?.let { append(" · 最少${it}笔") }
                                append("\n${hints.goal}")
                            }
                            msgs.add(Msg("assistant", head))
                            repo.optimizeStrategyWithLlm(
                                baseId = en.id,
                                rounds = hints.rounds ?: repo.settings().llmOptimizeRounds,
                                userGoal = hints.goal,
                                minWinRatePct = hints.minWinRatePct,
                                minTrades = hints.minTrades,
                            ).fold(
                                onSuccess = { "【策略优化完成】\n${it.report}" },
                                onFailure = { "优化失败: ${it.message}" },
                            )
                        }
                    }
                    isGenerateStrategyCmd(trimmed) || modeSnapshot == "generate" -> {
                        val hints = withDefaults(
                            parseOptimizeHints(
                                stripStrategyPrefix(trimmed, listOf("生成策略：", "生成策略:", "生成策略", "LLM生成策略")),
                            ),
                            defaultGoal = "设计事件合约策略，优先 ALGO 顺势单边或皮尔逊三曲线，兼顾胜率与笔数",
                            defaultRounds = repo.settings().llmOptimizeRounds.coerceIn(1, 8),
                        )
                        val head = buildString {
                            append("生成策略 · 轮次${hints.rounds}")
                            hints.minWinRatePct?.let { append(" · 目标胜率≥${"%.0f".format(it)}%") }
                            hints.minTrades?.let { append(" · 最少${it}笔") }
                            append("\n${hints.goal}")
                        }
                        msgs.add(Msg("assistant", head))
                        repo.optimizeStrategyWithLlm(
                            baseId = null,
                            rounds = hints.rounds ?: repo.settings().llmOptimizeRounds,
                            userGoal = hints.goal,
                            minWinRatePct = hints.minWinRatePct,
                            minTrades = hints.minTrades,
                        ).fold(
                            onSuccess = { "【新策略已创建】\n${it.report}" },
                            onFailure = { "生成失败: ${it.message}" },
                        )
                    }
                    else -> {
                        val bars = if (attach) marketBars else 0
                        repo.chat(body, attachMarketBars = bars)
                    }
                }
                msgs.add(Msg("assistant", reply))
                try {
                    state.animateScrollToItem(msgs.lastIndex)
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                if (!msg.contains("Mutation interrupted", ignoreCase = true) &&
                    !msg.contains("standalone coroutine was cancelled", ignoreCase = true)
                ) {
                    msgs.add(Msg("assistant", "错误: $msg"))
                }
            } finally {
                busy = false
                if (modeSnapshot != null) {
                    strategyMode = null
                }
            }
        }
    }

    fun onTemplate(tpl: ChipTpl) {
        if (busy) return
        when (tpl.action) {
            TplAction.SEND_CMD -> {
                strategyMode = null
                sendOnce(tpl.payload, attach = false)
            }
            TplAction.SEND_MARKET -> {
                strategyMode = null
                withMarket = true
                sendOnce(tpl.payload, attach = true)
            }
            TplAction.ASK_MARKET -> {
                strategyMode = null
                withMarket = true
                // 不填充输入框，用户自己写问题
            }
            TplAction.STRATEGY_MODE -> {
                withMarket = false
                strategyMode = tpl.payload // optimize | generate
                // 按当前策略动态填充可编辑需求（用户可改后发送）
                input = buildStrategyTuneDraft(repo, tpl.payload)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("LLM 对话", style = MaterialTheme.typography.titleMedium)
        Text(
            if (s.llmApiKey.isBlank()) {
                "命令模板可不配 Key；行情类需 API Key"
            } else {
                "模型 ${s.llmModel} · ${s.symbol} ${s.interval} · 缓存K线 ${repo.candles.size}"
            },
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
        )

        // 仅改条数 / 是否附带，不触发发送
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = withMarket,
                onClick = { withMarket = !withMarket },
                enabled = !busy,
                label = { Text(if (withMarket) "已附带行情" else "附带行情") },
            )
            listOf(20, 30, 50, 80).forEach { n ->
                FilterChip(
                    selected = marketBars == n,
                    onClick = { marketBars = n },
                    enabled = !busy,
                    label = { Text("${n}根") },
                )
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            templates.forEach { tpl ->
                val selected = when (tpl.action) {
                    TplAction.STRATEGY_MODE -> strategyMode == tpl.payload
                    TplAction.ASK_MARKET -> withMarket && strategyMode == null && tpl.payload == "market"
                    else -> false
                }
                val suffix = when (tpl.action) {
                    TplAction.SEND_MARKET -> "·发"
                    TplAction.ASK_MARKET -> "·问"
                    TplAction.STRATEGY_MODE -> if (selected) "·中" else "·选"
                    TplAction.SEND_CMD -> ""
                }
                FilterChip(
                    selected = selected,
                    onClick = { onTemplate(tpl) },
                    enabled = !busy,
                    label = { Text(tpl.label + suffix, fontSize = 12.sp) },
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = state,
        ) {
            items(msgs, key = { "${it.role}_${it.text.hashCode()}_${it.text.length}" }) { m ->
                val mine = m.role == "user"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Card(
                        Modifier
                            .fillMaxWidth(0.92f)
                            .widthIn(max = 520.dp),
                    ) {
                        // 长文：自动换行 + 限高可滚动，避免只能看到头部
                        Column(
                            Modifier
                                .padding(10.dp)
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = m.text,
                                softWrap = true,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = !busy,
                placeholder = {
                    Text(
                        when (strategyMode) {
                            "optimize" -> "直接写优化要求，如：顺势过滤假信号，轮次4，目标胜率55%"
                            "generate" -> "直接写生成要求，如：皮尔逊三曲线，轮次4，最少15笔"
                            else -> if (withMarket) "输入问题（将附带K线）" else "输入消息"
                        },
                    )
                },
                singleLine = false,
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = input.isNotBlank() && !busy,
                onClick = {
                    val t = input.trim()
                    input = ""
                    sendOnce(t, attach = withMarket)
                },
            ) { Text(if (busy) "…" else "发送") }
        }
        if (withMarket) {
            Text(
                "手动发送时将附带 ${s.symbol} ${s.interval} 最近 ${marketBars} 根K线",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 10.sp,
            )
        }
    }
}



private fun buildStrategyTuneDraft(repo: Repository, mode: String): String {
    val en = repo.strategies().find { it.enabled } ?: repo.strategies().firstOrNull()
    val st = repo.liveSimStats
    val consec = repo.consecutiveLosses()
    val simHint = if (st.trades > 0) {
        "参考实时模拟${st.trades}笔胜率${"%.1f".format(st.winRate * 100)}%连亏${consec}。"
    } else ""
    return if (mode == "generate") {
        "生成事件合约策略，优先 ALGO 顺势或皮尔逊，轮次4，目标胜率55%，最少15笔。" +
            simHint + "请输出可执行 algoParams 或指标规则。"
    } else {
        val base = en?.let { cfg ->
            when (cfg.kind) {
                com.puito.cryptoagent.data.StrategyKind.ALGO ->
                    "优化策略「${cfg.title}」算法${cfg.algoId} 参数${cfg.algoParams} ${cfg.algoNote}。"
                else ->
                    "优化策略「${cfg.title}」指标规则 买${cfg.buyRules.size}卖${cfg.sellRules.size}。"
            }
        } ?: "优化当前策略。"
        base + simHint + "直接改 algoParams/规则阈值，轮次4，目标胜率55%，最少15笔。"
    }
}

private fun isOptimizeStrategyCmd(t: String): Boolean {
    val s = t.trim()
    return s == "LLM优化策略" || s == "优化策略" || s == "优化策略：" || s == "优化策略:" ||
        s.startsWith("优化策略：") || s.startsWith("优化策略:") || s.startsWith("LLM优化策略")
}

private fun isGenerateStrategyCmd(t: String): Boolean {
    val s = t.trim()
    return s == "LLM生成策略" || s == "生成策略" || s == "生成策略：" || s == "生成策略:" ||
        s.startsWith("生成策略：") || s.startsWith("生成策略:") || s.startsWith("LLM生成策略")
}

private fun stripStrategyPrefix(body: String, prefixes: List<String>): String {
    var t = body.trim()
    for (p in prefixes.sortedByDescending { it.length }) {
        if (t.startsWith(p)) {
            t = t.removePrefix(p).trim()
            break
        }
    }
    for (m in listOf("需求：", "需求:")) {
        if (t.startsWith(m)) {
            t = t.removePrefix(m).trim()
            break
        }
        val i = t.indexOf("\n" + m)
        if (i >= 0) {
            t = (t.substring(0, i) + "\n" + t.substring(i + 1 + m.length)).trim()
        } else {
            val j = t.indexOf(m)
            if (j >= 0) t = (t.substring(0, j) + " " + t.substring(j + m.length)).trim()
        }
    }
    return t.trim()
}

data class OptimizeHints(
    val goal: String,
    /** null 表示未在文本中指定，应使用设置里的 llmOptimizeRounds */
    val rounds: Int? = null,
    val minWinRatePct: Double? = null,
    val minTrades: Int? = null,
)

/** 芯片一点即跑：无额外说明时用默认目标与训练参数 */
private fun withDefaults(
    h: OptimizeHints,
    defaultGoal: String,
    defaultRounds: Int,
): OptimizeHints {
    val blank = h.goal.isBlank()
    val rounds = (h.rounds ?: defaultRounds).coerceIn(1, 8)
    return OptimizeHints(
        goal = if (blank) defaultGoal else h.goal,
        rounds = rounds,
        minWinRatePct = h.minWinRatePct ?: if (blank) 55.0 else null,
        minTrades = h.minTrades ?: if (blank) 15 else null,
    )
}

private fun parseOptimizeHints(raw: String): OptimizeHints {
    var text = raw.trim()
    var rounds: Int? = null
    var minWr: Double? = null
    var minTrades: Int? = null

    fun take(pattern: String, ignoreCase: Boolean = true, on: (MatchResult) -> Unit) {
        val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val re = Regex(pattern, opts)
        // 支持多处匹配时取第一次；并清理所有匹配避免残留干扰
        val all = re.findAll(text).toList()
        if (all.isEmpty()) return
        on(all.first())
        all.asReversed().forEach { m ->
            text = text.replaceRange(m.range, " ")
        }
    }

    // 轮次4 / 迭代:4 / rounds=4 / 训练4轮 / 跑4轮
    take("""(?:轮次|迭代|rounds?|训练|跑)\s*[:=：]?\s*(\d+)\s*轮?""") {
        rounds = it.groupValues[1].toIntOrNull()?.coerceIn(1, 8)
    }
    take("""(?:目标胜率|胜率|minWinRate|winrate)\s*[≥>=:：]?\s*(\d+(?:\.\d+)?)\s*%?""") {
        minWr = it.groupValues[1].toDoubleOrNull()?.coerceIn(1.0, 99.0)
    }
    take("""最少\s*(\d+)\s*笔""") {
        minTrades = it.groupValues[1].toIntOrNull()?.coerceIn(1, 500)
    }
    take("""(?:最少|最低)?(?:交易)?笔数\s*[≥>=:：]?\s*(\d+)""") {
        minTrades = it.groupValues[1].toIntOrNull()?.coerceIn(1, 500)
    }
    take("""minTrades\s*[:=]?\s*(\d+)""") {
        minTrades = it.groupValues[1].toIntOrNull()?.coerceIn(1, 500)
    }

    val goal = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
    return OptimizeHints(goal = goal, rounds = rounds, minWinRatePct = minWr, minTrades = minTrades)
}
