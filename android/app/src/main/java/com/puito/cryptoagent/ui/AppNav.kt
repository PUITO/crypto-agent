package com.puito.cryptoagent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.*
import com.puito.cryptoagent.data.Repository
import com.puito.cryptoagent.ui.chat.ChatScreen
import com.puito.cryptoagent.ui.config.ConfigScreen
import com.puito.cryptoagent.ui.home.HomeScreen
import com.puito.cryptoagent.ui.trade.TradeScreen

@Composable
fun AppNav(repo: Repository) {
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route ?: "home"
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("home", "行情", Icons.Default.ShowChart),
                    Triple("trade", "策略", Icons.Default.Tune),
                    Triple("chat", "对话", Icons.Default.Chat),
                    Triple("config", "设置", Icons.Default.Settings),
                ).forEach { (r, label, icon) ->
                    NavigationBarItem(
                        selected = route == r,
                        onClick = { nav.navigate(r) { launchSingleTop = true } },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, "home", Modifier.padding(pad)) {
            composable("home") { HomeScreen(repo) }
            composable("trade") { TradeScreen(repo) }
            composable("chat") { ChatScreen(repo) }
            composable("config") { ConfigScreen(repo) }
        }
    }
}
