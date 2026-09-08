package com.puito.cryptoapp.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puito.cryptoapp.data.AppRepository
import com.puito.cryptoapp.data.model.AppSettings
import com.puito.cryptoapp.data.model.Interval

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: AppRepository, onBack: () -> Unit) {
    val cur = remember { repo.loadSettings() }
    var baseUrl by remember { mutableStateOf(cur.binanceBaseUrl) }
    var limit by remember { mutableStateOf(cur.defaultLimit.toString()) }
    var symbol by remember { mutableStateOf(cur.defaultSymbol) }
    var interval by remember { mutableStateOf(cur.defaultInterval) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Binance Base URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(limit, { limit = it }, label = { Text("默认拉取条数") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(symbol, { symbol = it }, label = { Text("默认币种") }, modifier = Modifier.fillMaxWidth())
            var exp by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { exp = true }) { Text("默认周期: $interval") }
                DropdownMenu(exp, { exp = false }) {
                    Interval.entries.forEach {
                        DropdownMenuItem(text = { Text(it.code) }, onClick = { interval = it.code; exp = false })
                    }
                }
            }
            Button(onClick = {
                repo.saveSettings(
                    AppSettings(
                        binanceBaseUrl = baseUrl.trim(),
                        defaultLimit = limit.toIntOrNull() ?: 1000,
                        defaultSymbol = symbol.trim().uppercase(),
                        defaultInterval = interval,
                        onboardingDone = true,
                    )
                )
                onBack()
            }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
        }
    }
}
