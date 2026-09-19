package com.puito.cryptoagent.ui.chat

import androidx.compose.foundation.horizontalScroll
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

/** 模板行为：直接发送 / 仅填入输入框待用户编辑发送 */
private enum class TplAction {
    /** 本地命令，立即发送，不带行情、不填输入框 */
    SEND_CMD,
    /** 带行情立即发送固定问题，不填输入框 */
    SEND_MARKET,
    /** 只写入输入框，打开附带行情，由用户改完再点发送 */
    FILL_MARKET,
}

private data class ChipTpl(
    val label: String,
    val action: TplAction,
    /** 真正发给后端/LLM 的文本；FILL 时作为输入框初值 */
    val payload: String,
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
    ChipTpl(
        "自定义行情问",
        TplAction.FILL_MARKET,
        "请结合附带K线分析：",
    ),
    ChipTpl("斐波那契", TplAction.SEND_CMD, "斐波那契"),
    ChipTpl("清除绘图", TplAction.SEND_CMD, "清除绘图"),
    ChipTpl("打开MA20", TplAction.SEND_CMD, "打开MA20"),
    ChipTpl("列出策略", TplAction.SEND_CMD, "列出策略"),
    ChipTpl(
        "LLM优化当前策略",
        TplAction.FILL_MARKET,
        "优化策略：\n需求：（例如：更少假信号、加强趋势过滤、保留现有RSI思路）",
    ),
    ChipTpl(
        "LLM生成新策略",
        TplAction.FILL_MARKET,
        "生成策略：\n需求：（例如：震荡市布林带+RSI；或趋势市MA金叉死叉；不要默认只做RSI+KDJ）",
    ),
)

@Composable
fun ChatScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var withMarket by remember { mutableStateOf(false) }
    var marketBars by remember { mutableIntStateOf(30) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
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
        val show = if (attach) "📊[${marketBars}根K线] $body" else body
        msgs.add(Msg("user", show))
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                val trimmed = body.trim()
                val reply = when {
                    trimmed == "LLM优化策略" || trimmed == "优化策略" || trimmed == "优化策略：" ||
                        trimmed.startsWith("优化策略：") || trimmed.startsWith("优化策略:") ||
                        trimmed.startsWith("LLM优化策略") -> {
                        val goal = extractStrategyGoal(trimmed, listOf("优化策略：", "优化策略:", "优化策略", "LLM优化策略"))
                        if (goal.isBlank() || goal.startsWith("（") || goal.startsWith("(") || "例如" in goal && goal.length < 40) {
                            "请先写清优化需求再发送。\n模板示例：\n优化策略：减少假突破，加入均线趋势过滤，目标胜率更高"
                        } else {
                            val en = repo.strategies().find { it.enabled } ?: repo.strategies().firstOrNull()
                            if (en == null) {
                                "没有可优化的策略，请先在策略页新建。"
                            } else {
                                msgs.add(Msg("assistant", "按需求优化「${en.title}」…\n需求：$goal"))
                                repo.optimizeStrategyWithLlm(baseId = en.id, rounds = 2, userGoal = goal).fold(
                                    onSuccess = { "【策略优化完成】\n${it.report}" },
                                    onFailure = { "优化失败: ${it.message}" },
                                )
                            }
                        }
                    }
                    trimmed == "LLM生成策略" || trimmed == "生成策略" || trimmed == "生成策略：" ||
                        trimmed.startsWith("生成策略：") || trimmed.startsWith("生成策略:") ||
                        trimmed.startsWith("LLM生成策略") -> {
                        val goal = extractStrategyGoal(trimmed, listOf("生成策略：", "生成策略:", "生成策略", "LLM生成策略"))
                        if (goal.isBlank() || goal.startsWith("（") || goal.startsWith("(") ||
                            ("例如" in goal && "需求" in trimmed && goal.length < 48)
                        ) {
                            "请先写清策略需求再发送（不要空着默认跑）。\n模板示例：\n生成策略：震荡市用布林带中轨+RSI超卖超买；避免只做RSI+KDJ\n生成策略：趋势跟踪，MA20/MA60 金叉死叉为主"
                        } else {
                            msgs.add(Msg("assistant", "按需求生成策略…\n需求：$goal"))
                            repo.optimizeStrategyWithLlm(baseId = null, rounds = 2, userGoal = goal).fold(
                                onSuccess = { "【新策略已创建】\n${it.report}" },
                                onFailure = { "生成失败: ${it.message}" },
                            )
                        }
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
            }
        }
    }

    fun onTemplate(tpl: ChipTpl) {
        if (busy) return
        when (tpl.action) {
            TplAction.SEND_CMD -> {
                // 不填输入框、不带行情
                sendOnce(tpl.payload, attach = false)
            }
            TplAction.SEND_MARKET -> {
                // 不填输入框，直接带行情发送
                withMarket = true
                sendOnce(tpl.payload, attach = true)
            }
            TplAction.FILL_MARKET -> {
                // 只填充，不发送；策略需求类不强制附带行情（优化函数会自己拉K线）
                val isStrategy = tpl.payload.startsWith("生成策略") || tpl.payload.startsWith("优化策略")
                withMarket = !isStrategy
                input = tpl.payload
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
                val suffix = when (tpl.action) {
                    TplAction.SEND_MARKET -> "·发"
                    TplAction.FILL_MARKET -> "·填"
                    TplAction.SEND_CMD -> ""
                }
                AssistChip(
                    onClick = { onTemplate(tpl) },
                    enabled = !busy,
                    label = { Text(tpl.label + suffix) },
                )
            }
        }

        LazyColumn(Modifier.weight(1f), state = state) {
            items(msgs, key = { it.hashCode().toString() + it.text.take(24) }) { m ->
                val mine = m.role == "user"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Card { Text(m.text, Modifier.padding(10.dp)) }
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
                        when {
                            withMarket -> "编辑后发送（附带${marketBars}根K线）…"
                            else -> "输入消息…"
                        },
                    )
                },
                singleLine = true,
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


private fun extractStrategyGoal(body: String, prefixes: List<String>): String {
    var t = body.trim()
    for (p in prefixes.sortedByDescending { it.length }) {
        if (t.startsWith(p)) {
            t = t.removePrefix(p).trim()
            break
        }
    }
    val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
    var goal = lines.joinToString(" ")
    val keyCn = "需求："
    val keyCn2 = "需求:"
    val idxCn = t.indexOf(keyCn)
    val idxCn2 = t.indexOf(keyCn2)
    val idx = when {
        idxCn >= 0 && idxCn2 >= 0 -> minOf(idxCn, idxCn2)
        idxCn >= 0 -> idxCn
        idxCn2 >= 0 -> idxCn2
        else -> -1
    }
    if (idx >= 0) {
        val keyLen = if (idxCn >= 0 && idx == idxCn) keyCn.length else keyCn2.length
        goal = t.substring(idx + keyLen).trim()
    }
    // 去掉括号占位提示
    val cut = goal.indexOf("（例如")
    if (cut >= 0) goal = goal.substring(0, cut).trim()
    val cut2 = goal.indexOf("(例如")
    if (cut2 >= 0) goal = goal.substring(0, cut2).trim()
    return goal.trim()
}
