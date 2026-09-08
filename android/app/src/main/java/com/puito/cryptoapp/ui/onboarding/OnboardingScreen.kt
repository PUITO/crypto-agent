package com.puito.cryptoapp.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puito.cryptoapp.data.AppRepository
import com.puito.cryptoapp.data.model.AppSettings
import com.puito.cryptoapp.data.model.Interval

@Composable
fun OnboardingScreen(repo: AppRepository, onDone: () -> Unit) {
    var limit by remember { mutableStateOf("1000") }
    var interval by remember { mutableStateOf("10m") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("欢迎使用 Crypto App", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("本地拉 K 线 · 指标信号 · 事件合约模拟", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(limit, { limit = it }, label = { Text("默认读取 K 线条数") })
        Spacer(Modifier.height(12.dp))
        var exp by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { exp = true }) { Text("默认事件时长: $interval") }
            DropdownMenu(exp, { exp = false }) {
                Interval.entries.forEach {
                    DropdownMenuItem(text = { Text(it.code) }, onClick = { interval = it.code; exp = false })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            val s = repo.loadSettings().copy(
                defaultLimit = limit.toIntOrNull() ?: 1000,
                defaultInterval = interval,
                onboardingDone = true,
            )
            repo.saveSettings(s)
            // ensure default strategy exists
            repo.loadConfigs()
            onDone()
        }, modifier = Modifier.fillMaxWidth()) { Text("开始使用") }
    }
}
