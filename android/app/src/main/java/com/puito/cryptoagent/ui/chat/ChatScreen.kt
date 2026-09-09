package com.puito.cryptoagent.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puito.cryptoagent.data.Repository
import kotlinx.coroutines.launch

data class Msg(val role: String, val text: String)

@Composable
fun ChatScreen(repo: Repository) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val msgs = remember {
        mutableStateListOf(
            Msg("assistant", "本地 Agent 对话。请在「设置」配置 OpenAI 兼容的 LLM Base URL 与 API Key 后使用。"),
        )
    }
    val state = rememberLazyListState()
    val s = repo.settings()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("LLM 对话", style = MaterialTheme.typography.titleMedium)
        Text(
            if (s.llmApiKey.isBlank()) "未配置 API Key" else "模型 ${s.llmModel}",
            color = MaterialTheme.colorScheme.secondary,
        )
        LazyColumn(Modifier.weight(1f), state = state) {
            items(msgs) { m ->
                val mine = m.role == "user"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Card {
                        Text(m.text, Modifier.padding(10.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f),
                placeholder = { Text("输入…") }, singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = input.isNotBlank() && !busy,
                onClick = {
                    val t = input.trim(); input = ""
                    msgs.add(Msg("user", t))
                    scope.launch {
                        busy = true
                        try {
                            msgs.add(Msg("assistant", repo.chat(t)))
                            state.animateScrollToItem(msgs.lastIndex)
                        } catch (e: Exception) {
                            msgs.add(Msg("assistant", "错误: ${e.message}"))
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "…" else "发送") }
        }
    }
}
