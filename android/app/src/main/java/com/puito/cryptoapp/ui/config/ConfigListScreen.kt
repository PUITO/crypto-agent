package com.puito.cryptoapp.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puito.cryptoapp.data.AppRepository
import com.puito.cryptoapp.data.model.StrategyConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigListScreen(
    repo: AppRepository,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onNew: () -> Unit,
) {
    var list by remember { mutableStateOf(repo.loadConfigs()) }

    fun refresh() {
        list = repo.loadConfigs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("策略配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, contentDescription = "add")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(12.dp)) {
            items(list, key = { it.id }) { cfg ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(cfg.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (cfg.enabled) "已启用" else "未启用",
                                color = if (cfg.enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondary,
                            )
                        }
                        TextButton(onClick = {
                            val updated = list.map {
                                it.copy(enabled = it.id == cfg.id)
                            }
                            repo.saveConfigs(updated)
                            refresh()
                        }) { Text(if (cfg.enabled) "已启用" else "启用") }
                        TextButton(onClick = { onEdit(cfg.id) }) { Text("编辑") }
                    }
                }
            }
        }
    }
}
