package com.puito.cryptoagent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.puito.cryptoagent.data.PrefsStore
import com.puito.cryptoagent.net.AgentApi
import com.puito.cryptoagent.ui.chat.ChatScreen
import com.puito.cryptoagent.ui.config.ConfigScreen
import com.puito.cryptoagent.ui.health.HealthScreen
import com.puito.cryptoagent.ui.home.HomeScreen

@Composable
fun AppNav(api: AgentApi, prefs: PrefsStore) {
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route ?: "home"

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = route == "home",
                    onClick = { nav.navigate("home") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("行情") },
                )
                NavigationBarItem(
                    selected = route == "chat",
                    onClick = { nav.navigate("chat") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Chat, null) },
                    label = { Text("对话") },
                )
                NavigationBarItem(
                    selected = route == "health",
                    onClick = { nav.navigate("health") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Favorite, null) },
                    label = { Text("健康") },
                )
                NavigationBarItem(
                    selected = route == "config",
                    onClick = { nav.navigate("config") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", Modifier.padding(pad)) {
            composable("home") { HomeScreen(api, prefs) }
            composable("chat") { ChatScreen(api) }
            composable("health") { HealthScreen(api) }
            composable("config") { ConfigScreen(api, prefs) }
        }
    }
}
