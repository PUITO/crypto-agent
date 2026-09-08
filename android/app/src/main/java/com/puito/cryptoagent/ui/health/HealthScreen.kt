package com.puito.cryptoagent.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.puito.cryptoagent.data.ServiceHealth
import com.puito.cryptoagent.net.AgentApi
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(api: AgentApi) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ServiceHealth>>(emptyList()) }
    var mobile by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            err = null
            try {
                list = api.healthAll()
                mobile = api.mobileHealth()
            } catch (e: Exception) {
                err = e.message
                list = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row {
            Text("微服务健康", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh() }, enabled = !loading) { Text("刷新") }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (mobile.isNotBlank()) {
            Text("Mobile API: $mobile", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 6.dp))
        }
        LazyColumn {
            items(list) { s ->
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(s.detail, maxLines = 2) },
                    trailingContent = {
                        Text(if (s.ok) "OK" else "DOWN", color = if (s.ok) Color(0xFF0ECB81) else Color(0xFFF6465D))
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
