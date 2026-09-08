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
import com.puito.cryptoagent.data.ChatMessage
import com.puito.cryptoagent.net.AgentApi
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(api: AgentApi) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf(
            ChatMessage("assistant", "我是 Crypto Agent 对话入口。请先在「设置」配置 Gateway，然后可问行情、指标或回测相关问题。"),
        )
    }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Agent 对话", style = MaterialTheme.typography.titleMedium)
        Text("POST /agent/api/v1/chat", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(messages) { m ->
                val mine = m.role == "user"
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Text(m.content, Modifier.padding(10.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = input.isNotBlank() && !sending,
                onClick = {
                    val text = input.trim()
                    input = ""
                    messages.add(ChatMessage("user", text))
                    scope.launch {
                        sending = true
                        try {
                            val reply = api.chat(text)
                            messages.add(ChatMessage("assistant", reply))
                            listState.animateScrollToItem(messages.lastIndex)
                        } catch (e: Exception) {
                            messages.add(ChatMessage("assistant", "错误: ${e.message}"))
                        } finally {
                            sending = false
                        }
                    }
                },
            ) { Text(if (sending) "…" else "发送") }
        }
    }
}
