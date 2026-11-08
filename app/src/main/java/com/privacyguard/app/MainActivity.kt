package com.privacyguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.privacyguard.app.core.root.SuShellExecutor
import com.privacyguard.app.core.service.ProtectionForegroundService
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
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    // POST_NOTIFICATIONS 运行时申请（Android 13+）：被拒仅提示，不影响防护功能本身
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                applicationContext,
                "未授予通知权限，前台防护状态提示可能不显示，防护功能不受影响",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge：窗口延伸到状态栏/导航条后面，系统栏避让全部交给 Miuix 组件
        // （TopAppBar/NavigationBar 自带 systemBars insets padding，Scaffold 传 innerPadding）；
        // 系统栏图标亮暗由其按系统深色模式自动设置，与 MiuixTheme 默认跟随系统一致。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val app = application as PrivacyGuardApp

        setContent {
            MiuixTheme {
                val coroutineScope = rememberCoroutineScope()

                // 当前激活底栏页面（rememberSaveable：旋转/进程回收后恢复选中页）
                var currentScreenIndex by rememberSaveable { mutableIntStateOf(0) }
                val currentScreen = bottomNavItems[currentScreenIndex.coerceIn(bottomNavItems.indices)]

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
                    // imePadding：edge-to-edge 下键盘不再压缩窗口，弹出时整体抬升，
                    // 保证规则页输入框不被键盘遮挡
                    modifier = Modifier.imePadding(),
                    bottomBar = {
                        NavigationBar {
                            bottomNavItems.forEach { screen ->
                                NavigationBarItem(
                                    selected = currentScreen == screen,
                                    onClick = { currentScreenIndex = bottomNavItems.indexOf(screen) },
                                    icon = screen.icon,
                                    label = screen.title
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    // 嵌套 Scaffold 防双算（各屏幕内部还有一层 Scaffold + TopAppBar）：
                    // - 底部：外层在此 padding（底栏高度+导航条）并全额消费，内层不再重复计导航条 inset
                    // - 顶部/横向：放行不消费，由内层 TopAppBar/Scaffold 自带的 systemBars、
                    //   displayCutout padding 接管，标题背景得以延伸到状态栏后（沉浸式）
                    val bottomPadding = innerPadding.calculateBottomPadding()
                    Box(
                        modifier = Modifier
                            .padding(bottom = bottomPadding)
                            .consumeWindowInsets(PaddingValues(bottom = bottomPadding))
                    ) {
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
                                            // 仅在防护运行时重放 startProtection 才有意义，否则只提示
                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                Toast.makeText(this@MainActivity, "正在重新解析与构建规则...", Toast.LENGTH_SHORT).show()
                                                app.protectionRepository.startProtection(guardConfig)
                                                Toast.makeText(this@MainActivity, "规则重构完成", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "防护未开启，无需刷新规则", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onNavigateToSystem = {
                                        currentScreenIndex = bottomNavItems.indexOf(Screen.System)
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
                                            // 体检分经 PrivacyRepository.healthScore 由 App 层统计收集器自动同步
                                            app.privacyRepository.performScan()
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
                                    },
                                    onCleanFraudResidue = {
                                        coroutineScope.launch {
                                            val result = app.privacyRepository.cleanFraudResidue()
                                            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

        // 链挂载以真实退出码为准：stderr 已被合并进 stdout，仅凭输出文本会把
        // "No chain/target/match by that name" 之类报错误判为已挂载
        val chainRes = SuShellExecutor.execute("iptables -w 5 -S fj_blk_out")
        val chainCheck = chainRes.stdout.take(3).joinToString("; ")
        sb.append("IPv4 防护链: ${if (chainRes.isSuccess && chainCheck.isNotBlank()) "已挂载 ($chainCheck)" else "未挂载"}\n")

        val selinux = SuShellExecutor.execute("getenforce 2>&1").outputText
        sb.append("SELinux 模式: $selinux\n")

        val hostsBind = SuShellExecutor.execute("mount | grep -E '/system/etc/hosts|/etc/hosts' 2>&1").outputText
        sb.append("Hosts 挂载: ${if (hostsBind.isNotBlank()) hostsBind else "未挂载 (普通文件写入模式)"}\n")

        return sb.toString()
    }
}
