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
import kotlinx.coroutines.launch

data class Msg(val role: String, val text: String)

private data class ChipTpl(val label: String, val fill: String, val withMarket: Boolean = false)

private val templates = listOf(
    ChipTpl("行情分析", "请根据附带的K线数据，分析当前趋势、关键高低点、波动与短线风险，并给出事件合约视角的注意点。", withMarket = true),
    ChipTpl("多空研判", "结合附带行情，判断偏多还是偏空？关键依据是什么？", withMarket = true),
    ChipTpl("支撑阻力", "根据附带K线指出可能的支撑与阻力区间，并说明理由。", withMarket = true),
    ChipTpl("斐波那契", "在当前可见区间绘制斐波那契", withMarket = false),
    ChipTpl("清除绘图", "清除临时斐波那契等绘图", withMarket = false),
    ChipTpl("打开MA20", "图表叠加 MA20", withMarket = false),
    ChipTpl("列出策略", "查看已保存策略文件", withMarket = false),
)

@Composable
fun ChatScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var withMarket by remember { mutableStateOf(false) }
    var marketBars by remember { mutableIntStateOf(30) }
    val msgs = remember {
        mutableStateListOf(
            Msg(
                "assistant",
                "本地 Agent 对话。点「行情分析/多空研判/支撑阻力」会携带最近 N 根K线给 LLM；也可点下方条数后自由提问。命令类模板不需 API Key。",
            ),
        )
    }
    val state = rememberLazyListState()
    val s = repo.settings()

    fun send(text: String, attach: Boolean = withMarket) {
        if (text.isBlank() || busy) return
        val show = if (attach) "📊[${marketBars}根K线] $text" else text
        msgs.add(Msg("user", show))
        scope.launch {
            busy = true
            try {
                val bars = if (attach) marketBars else 0
                msgs.add(Msg("assistant", repo.chat(text, attachMarketBars = bars)))
                state.animateScrollToItem(msgs.lastIndex)
            } catch (e: Exception) {
                msgs.add(Msg("assistant", "错误: ${e.message}"))
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("LLM 对话", style = MaterialTheme.typography.titleMedium)
        Text(
            if (s.llmApiKey.isBlank()) {
                "模板命令可不配 Key；行情分析需 API Key"
            } else {
                "模型 ${s.llmModel} · ${s.symbol} ${s.interval} · 缓存K线 ${repo.candles.size}"
            },
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
        )

        // 携带行情条数
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = withMarket,
                onClick = { withMarket = !withMarket },
                label = { Text(if (withMarket) "已附带行情" else "附带行情") },
            )
            listOf(20, 30, 50, 80).forEach { n ->
                FilterChip(
                    selected = marketBars == n,
                    onClick = {
                        marketBars = n
                        withMarket = true
                    },
                    label = { Text("${n}根") },
                )
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            templates.forEach { tpl ->
                AssistChip(
                    onClick = {
                        if (tpl.withMarket) {
                            withMarket = true
                            input = tpl.fill
                            // 直接发送模板问题 + 行情
                            send(tpl.fill, attach = true)
                        } else {
                            send(tpl.label, attach = false)
                        }
                    },
                    label = { Text(tpl.label) },
                )
            }
        }

        LazyColumn(Modifier.weight(1f), state = state) {
            items(msgs) { m ->
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
                input,
                { input = it },
                Modifier.weight(1f),
                placeholder = {
                    Text(if (withMarket) "提问（将附带最近${marketBars}根K线）…" else "输入或点模板…")
                },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = input.isNotBlank() && !busy,
                onClick = {
                    val t = input.trim()
                    input = ""
                    send(t, attach = withMarket)
                },
            ) { Text(if (busy) "…" else "发送") }
        }
        if (withMarket) {
            Text(
                "发送时将附带 ${s.symbol} ${s.interval} 最近 ${marketBars} 根K线摘要给模型",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 10.sp,
            )
        }
    }
}
