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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    // POST_NOTIFICATIONS 运行时申请（Android 13+）：
    // - 申请时机改在用户「开启防护」时按需发起（常驻通知才是该权限的真实用途），
    //   不再每次冷启动弹窗；被拒后不反复纠缠
    // - startForeground 在缺权限时不抛错，前台服务与开机自启均正常，
    //   被拒只影响状态栏常驻通知的可见性（HyperOS 上无可见通知的后台更易被省电策略清理，建议授予）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this@MainActivity,
            if (granted) "已授予通知权限，防护状态将常驻通知栏"
            else "未授予通知权限：防护功能正常运行，仅状态栏常驻通知不显示",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge：窗口延伸到状态栏/导航条后面，系统栏避让全部交给 Miuix 组件
        // （TopAppBar/NavigationBar 自带 systemBars insets padding，Scaffold 传 innerPadding）；
        // 系统栏图标亮暗由其按系统深色模式自动设置，与 MiuixTheme 默认跟随系统一致。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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

                // 拦截日志前台补采循环：清爽模式（无前台服务）下统计的唯一更新入口；
                // 与前台服务的 15s 轮询并存时幂等（dmesg 水位线去重）
                LaunchedEffect(Unit) {
                    while (isActive) {
                        runCatching { app.interceptLogRepository.captureKernelLogs() }
                        delay(30_000)
                    }
                }

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
                                                // 通知权限按需申请：仅在开启防护且未授予时发起，
                                                // 结果无论授予与否都不阻断下方防护启动序列
                                                requestNotificationPermissionIfNeeded()
                                                // 清爽模式（保活通知关闭）不启动前台服务：规则照常应用，
                                                // 拦截统计由下方的前台补采循环在打开 App 时更新
                                                if (guardConfig.keepAliveNotification) {
                                                    ProtectionForegroundService.start(this@MainActivity)
                                                }
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
                                    onDeleteAppLogs = { pkg, label ->
                                        coroutineScope.launch {
                                            app.interceptLogRepository.deleteAppLogs(pkg, label)
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
                                    },
                                    // 组件详情弹窗的拦截统计（累计/今日/今日动向），从拦截日志库按组件口径查询
                                    onQueryPackageStats = { item ->
                                        app.interceptLogRepository.packageInterceptStats(item.packageName, item.label)
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
                                            app.settingsDataStore.setKeepAliveNotification(newConfig.keepAliveNotification)
                                            app.settingsDataStore.setModuleSyncMode(newConfig.moduleSyncMode)
                                            app.settingsDataStore.setLogEnabled(newConfig.logEnabled)

                                            if (dashboardState.engineStatus == EngineStatus.RUNNING) {
                                                app.protectionRepository.startProtection(newConfig)
                                                // 保活开关切换时同步前台服务：开启补挂通知，关闭进清爽模式
                                                if (newConfig.keepAliveNotification) {
                                                    ProtectionForegroundService.start(this@MainActivity)
                                                } else {
                                                    ProtectionForegroundService.stop(this@MainActivity)
                                                }
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

        // Hosts 挂载检测：精确匹配挂载点（目标前后各有空格），避免把
        // /data/adb/modules/xxx/system/etc/hosts 之类的无关路径误判为已挂载；
        // 原始输出带超长挂载选项串，整段拼接会换行缠绕似乱码，改为逐行提炼可读摘要
        val hostsMountRaw = SuShellExecutor.execute(
            "grep -E ' (/system/etc/hosts|/etc/hosts) ' /proc/mounts 2>/dev/null"
        ).outputText

        // /proc/mounts 实际行格式为 "源 目标 类型 选项..."（空格分隔，无 on/type 字面量），
        // 故先按 "源 on 目标 类型 (...)" 的 mount 命令风格匹配，不中再按空格分隔三元组兜底
        val mountLines = hostsMountRaw.lines().mapNotNull { line ->
            val match = Regex("""(\S+) on (\S+) type (\S+)""").find(line)
                ?: Regex("""(\S+)\s+(\S+)\s+(\S+)""").find(line)
                ?: return@mapNotNull null
            val (source, target, type) = match.destructured
            "  · $target ($type) ← $source"
        }

        sb.append(
            if (mountLines.isEmpty()) {
                "Hosts 挂载: 未挂载 (普通文件写入模式)\n"
            } else {
                (listOf("Hosts 挂载: ${mountLines.size} 个挂载点") + mountLines).joinToString("\n") + "\n"
            }
        )

        return sb.toString()
    }
}
