package com.puito.cryptoapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.puito.cryptoapp.data.AppRepository
import com.puito.cryptoapp.ui.config.ConfigEditScreen
import com.puito.cryptoapp.ui.config.ConfigListScreen
import com.puito.cryptoapp.ui.home.HomeScreen
import com.puito.cryptoapp.ui.onboarding.OnboardingScreen
import com.puito.cryptoapp.ui.settings.SettingsScreen

@Composable
fun AppNav(repo: AppRepository) {
    val nav = rememberNavController()
    val start = remember {
        if (repo.loadSettings().onboardingDone) "home" else "onboarding"
    }
    NavHost(navController = nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(repo) { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } }
        }
        composable("home") {
            HomeScreen(
                repo = repo,
                onConfig = { nav.navigate("config") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("config") {
            ConfigListScreen(
                repo = repo,
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("config_edit/$id") },
                onNew = { nav.navigate("config_edit/new") },
            )
        }
        composable("config_edit/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: "new"
            ConfigEditScreen(repo, id, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(repo, onBack = { nav.popBackStack() })
        }
    }
}
