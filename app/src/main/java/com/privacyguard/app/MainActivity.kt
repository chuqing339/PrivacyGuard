package com.privacyguard.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.core.root.SuShellExecutor
import com.privacyguard.app.core.service.ProtectionForegroundService
import com.privacyguard.app.data.local.entity.RuleEntity
import com.privacyguard.app.data.model.EngineStatus
import com.privacyguard.app.data.preference.GuardConfig
import com.privacyguard.app.ui.navigation.Screen
import com.privacyguard.app.ui.navigation.bottomNavItems
import com.privacyguard.app.ui.screens.home.HomeScreen
import com.privacyguard.app.ui.screens.intercept.InterceptScreen
import com.privacyguard.app.ui.screens.rules.RulesScreen
import com.privacyguard.app.ui.screens.settings.SettingsScreen
import com.privacyguard.app.ui.screens.system.SystemPrivacyScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as PrivacyGuardApp

        setContent {
            MiuixTheme {
                val coroutineScope = rememberCoroutineScope()

                // 当前激活底栏页面
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                // 订阅全局状态
                val dashboardState by app.protectionRepository.dashboardState.collectAsState()
                val guardConfig by app.settingsDataStore.configFlow.collectAsState(initial = GuardConfig())
                val segments by app.ruleRepository.allSegments.collectAsState(initial = emptyList())
                val allRules by app.ruleRepository.allRules.collectAsState(initial = emptyList())
                val whitelist by app.ruleRepository.allWhitelist.collectAsState(initial = emptyList())
                val interceptLogs by app.interceptLogRepository.recentLogs.collectAsState(initial = emptyList())
                val topApps by app.interceptLogRepository.topApps.collectAsState(initial = emptyList())
                val privacyScanItems by app.privacyRepository.items.collectAsState()
                val runningProcs by app.privacyRepository.runningProcs.collectAsState()

                var diagnosticResult by remember { mutableStateOf("") }

                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            bottomNavItems.forEach { screen ->
                                NavigationBarItem(
                                    selected = currentScreen == screen,
                                    onClick = { currentScreen = screen },
                                    icon = {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = screen.title
                                        )
                                    },
                                    label = { Text(screen.title, fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF0A59F7),
                                        selectedTextColor = Color(0xFF0A59F7),
                                        indicatorColor = Color(0xFF0A59F7).copy(alpha = 0.12f)
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.Home -> {
                                HomeScreen(
                                    dashboardState = dashboardState,
                                    guardConfig = guardConfig,
                                    onToggleMaster = {
                                        coroutineScope.launch {
                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.stopProtection()
                                                app.settingsDataStore.setMasterEnabled(false)
                                                ProtectionForegroundService.stop(this@MainActivity)
                                                Toast.makeText(this@MainActivity, "隐私防护已停止", Toast.LENGTH_SHORT).show()
                                            } else {
                                                ProtectionForegroundService.start(this@MainActivity)
                                                app.settingsDataStore.setMasterEnabled(true)
                                                val ok = app.protectionRepository.startProtection(guardConfig.copy(masterEnabled = true))
                                                if (ok) {
                                                    Toast.makeText(this@MainActivity, "全机隐私防御已开启！", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(this@MainActivity, "防护启动失败，请检查 Root 权限", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    onRefreshRules = {
                                        coroutineScope.launch {
                                            Toast.makeText(this@MainActivity, "正在重新解析与构建规则...", Toast.LENGTH_SHORT).show()
                                            app.protectionRepository.startProtection(guardConfig)
                                            Toast.makeText(this@MainActivity, "规则重构完成", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onNavigateToSystem = {
                                        currentScreen = Screen.System
                                    }
                                )
                            }
                            Screen.Rules -> {
                                RulesScreen(
                                    segments = segments,
                                    customRules = allRules.filter { it.isCustom },
                                    whitelist = whitelist,
                                    onToggleSegment = { key, isEnabled ->
                                        coroutineScope.launch {
                                            app.ruleRepository.setSegmentEnabled(key, isEnabled)
                                            // 立即同步更新 iptables 与 hosts
                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.startProtection(guardConfig)
                                            }
                                        }
                                    },
                                    onAddCustomRule = { domain, comment ->
                                        coroutineScope.launch {
                                            app.ruleRepository.addCustomRule(domain, comment)
                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.startProtection(guardConfig)
                                            }
                                        }
                                    },
                                    onDeleteCustomRule = { domain ->
                                        coroutineScope.launch {
                                            app.ruleRepository.deleteCustomRule(domain)
                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.startProtection(guardConfig)
                                            }
                                        }
                                    },
                                    onAddWhitelist = { domain, comment ->
                                        coroutineScope.launch {
                                            app.ruleRepository.addWhitelist(domain, comment)
                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.startProtection(guardConfig)
                                            }
                                        }
                                    },
                                    onDeleteWhitelist = { domain ->
                                        coroutineScope.launch {
                                            app.ruleRepository.deleteWhitelist(domain)
                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.startProtection(guardConfig)
                                            }
                                        }
                                    }
                                )
                            }
                            Screen.Intercept -> {
                                InterceptScreen(
                                    logs = interceptLogs,
                                    topApps = topApps,
                                    onClearLogs = {
                                        coroutineScope.launch {
                                            app.interceptLogRepository.clearLogs()
                                            Toast.makeText(this@MainActivity, "已清空拦截历史", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                            Screen.System -> {
                                SystemPrivacyScreen(
                                    privacyScore = dashboardState.privacyScore,
                                    scanItems = privacyScanItems,
                                    runningProcs = runningProcs,
                                    onRescan = {
                                        coroutineScope.launch {
                                            val score = app.privacyRepository.performScan()
                                            app.protectionRepository.updateStats(
                                                total = dashboardState.totalInterceptCount,
                                                today = dashboardState.todayInterceptCount,
                                                score = score
                                            )
                                        }
                                    },
                                    onOneClickOptimize = {
                                        coroutineScope.launch {
                                            Toast.makeText(this@MainActivity, "正在安全冻结高危探针...", Toast.LENGTH_SHORT).show()
                                            app.privacyRepository.oneClickOptimize()
                                            Toast.makeText(this@MainActivity, "已成功冻结小米统计与运营商探针", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onTogglePackage = { pkg, state ->
                                        coroutineScope.launch {
                                            app.privacyRepository.togglePackage(pkg, state)
                                        }
                                    }
                                )
                            }
                            Screen.Settings -> {
                                SettingsScreen(
                                    guardConfig = guardConfig,
                                    onUpdateConfig = { newConfig ->
                                        coroutineScope.launch {
                                            app.settingsDataStore.setHostsEnabled(newConfig.hostsEnabled)
                                            app.settingsDataStore.setIpv4Enabled(newConfig.ipv4Enabled)
                                            app.settingsDataStore.setIpv6Enabled(newConfig.ipv6Enabled)
                                            app.settingsDataStore.setDnsGuardEnabled(newConfig.dnsGuardEnabled)
                                            app.settingsDataStore.setWhitelistEnabled(newConfig.whitelistEnabled)
                                            app.settingsDataStore.setBootAutoStart(newConfig.bootAutoStart)
                                            app.settingsDataStore.setModuleSyncMode(newConfig.moduleSyncMode)
                                            app.settingsDataStore.setLogEnabled(newConfig.logEnabled)

                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.startProtection(newConfig)
                                            }
                                        }
                                    },
                                    onRunDiagnostic = {
                                        coroutineScope.launch {
                                            diagnosticResult = "正在诊断中..."
                                            val diagText = withContext(Dispatchers.IO) {
                                                runDiagnosticCheck()
                                            }
                                            diagnosticResult = diagText
                                        }
                                    },
                                    diagnosticOutput = diagnosticResult
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun runDiagnosticCheck(): String {
        val sb = StringBuilder()
        sb.append("=== 隐私守卫环境自检 ===\n")

        val root = SuShellExecutor.checkRoot()
        sb.append("ROOT 权限: ${if (root) "已授权 (uid=0)" else "未检测到"}\n")

        val flavor = SuShellExecutor.detectRootFlavor()
        sb.append("Root 方案: $flavor\n")

        val iptCheck = SuShellExecutor.execute("iptables -V 2>&1").outputText
        sb.append("iptables: $iptCheck\n")

        val chainCheck = SuShellExecutor.execute("iptables -w 5 -S fj_blk_out 2>&1").stdout.take(3).joinToString("; ")
        sb.append("IPv4 防护链: ${if (chainCheck.isNotBlank()) "已挂载 ($chainCheck)" else "未挂载"}\n")

        val selinux = SuShellExecutor.execute("getenforce 2>&1").outputText
        sb.append("SELinux 模式: $selinux\n")

        val hostsBind = SuShellExecutor.execute("mount | grep -E '/system/etc/hosts|/etc/hosts' 2>&1").outputText
        sb.append("Hosts 挂载: ${if (hostsBind.isNotBlank()) hostsBind else "未挂载 (普通文件写入模式)"}\n")

        return sb.toString()
    }
}
