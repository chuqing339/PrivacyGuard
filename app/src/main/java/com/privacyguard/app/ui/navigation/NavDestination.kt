package com.privacyguard.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "防护", Icons.Outlined.Security)
    object Rules : Screen("rules", "规则", Icons.Outlined.Shield)
    object Intercept : Screen("intercept", "监控", Icons.Outlined.BarChart)
    object System : Screen("system", "体检", Icons.Outlined.HealthAndSafety)
    object Settings : Screen("settings", "设置", Icons.Outlined.Settings)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Rules,
    Screen.Intercept,
    Screen.System,
    Screen.Settings
)
