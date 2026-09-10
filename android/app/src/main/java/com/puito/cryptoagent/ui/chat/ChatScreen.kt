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
import com.puito.cryptoagent.data.Repository
import kotlinx.coroutines.launch

data class Msg(val role: String, val text: String)

private val templates = listOf(
    "斐波那契" to "在当前可见区间绘制斐波那契",
    "清除绘图" to "清除临时斐波那契等绘图",
    "打开MA20" to "图表叠加 MA20",
    "打开MA7" to "图表叠加 MA7",
    "列出策略" to "查看已保存策略文件",
    "添加策略：Chat策略" to "新增一条策略配置",
    "当前胜率如何？" to "LLM 分析（需配置 Key）",
)

@Composable
fun ChatScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val msgs = remember {
        mutableStateListOf(
            Msg("assistant", "本地 Agent 对话。可用上方模板，或配置 LLM API Key。命令：斐波那契 / 清除绘图 / 打开MAn / 列出策略 / 添加策略：名称"),
        )
    }
    val state = rememberLazyListState()
    val s = repo.settings()

    fun send(text: String) {
        if (text.isBlank() || busy) return
        msgs.add(Msg("user", text))
        scope.launch {
            busy = true
            try {
                msgs.add(Msg("assistant", repo.chat(text)))
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
            if (s.llmApiKey.isBlank()) "模板命令可不配 Key；自由对话需 API Key" else "模型 ${s.llmModel}",
            color = MaterialTheme.colorScheme.secondary,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            templates.forEach { (label, _) ->
                AssistChip(onClick = { send(label) }, label = { Text(label) })
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
                input, { input = it }, Modifier.weight(1f),
                placeholder = { Text("输入或点模板…") }, singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = input.isNotBlank() && !busy, onClick = {
                val t = input.trim(); input = ""; send(t)
            }) { Text(if (busy) "…" else "发送") }
        }
    }
}
