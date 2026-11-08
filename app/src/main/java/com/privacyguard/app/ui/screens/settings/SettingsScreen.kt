package com.privacyguard.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.data.preference.GuardConfig
import com.privacyguard.app.ui.components.HyperCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

// 当前设置子页面
private enum class SubPage { NONE, DIAGNOSTIC, ABOUT }

@Composable
fun SettingsScreen(
    guardConfig: GuardConfig,
    onUpdateConfig: (GuardConfig) -> Unit,
    onRunDiagnostic: () -> Unit,
    diagnosticOutput: String,
    modifier: Modifier = Modifier
) {
    var subPage by remember { mutableStateOf(SubPage.NONE) }

    // 拦截系统返回键，返回上一级
    if (subPage != SubPage.NONE) {
        BackHandler {
            subPage = SubPage.NONE
        }
    }

    when (subPage) {
        SubPage.DIAGNOSTIC -> {
            DiagnosticPage(
                diagnosticOutput = diagnosticOutput,
                onRunDiagnostic = onRunDiagnostic,
                onBack = { subPage = SubPage.NONE }
            )
        }
        SubPage.ABOUT -> {
            AboutPage(onBack = { subPage = SubPage.NONE })
        }
        SubPage.NONE -> {
            SettingsMainPage(
                guardConfig = guardConfig,
                onUpdateConfig = onUpdateConfig,
                onOpenDiagnostic = {
                    onRunDiagnostic()
                    subPage = SubPage.DIAGNOSTIC
                },
                onOpenAbout = { subPage = SubPage.ABOUT },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun SettingsMainPage(
    guardConfig: GuardConfig,
    onUpdateConfig: (GuardConfig) -> Unit,
    onOpenDiagnostic: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(title = "高级防护设置")
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 各层拦截策略
            SmallTitle(text = "各层策略与开关")

            Card {
                SwitchPreference(
                    title = "L1 · Hosts 域名层拦截",
                    summary = "将所有规则域名本地解析为 0.0.0.0",
                    checked = guardConfig.hostsEnabled,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(hostsEnabled = it)) }
                )
                SwitchPreference(
                    title = "L2 · IPv4 iptables 链拦截",
                    summary = "阻断解析所得真实 IP，拦截硬编码直连",
                    checked = guardConfig.ipv4Enabled,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(ipv4Enabled = it)) }
                )
                SwitchPreference(
                    title = "L3 · IPv6 双栈同封",
                    summary = "启用 ip6tables 防止应用利用 IPv6 绕过",
                    checked = guardConfig.ipv6Enabled,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(ipv6Enabled = it)) }
                )
                SwitchPreference(
                    title = "L4 · DNS 强制兜底 (Anti-DoH)",
                    summary = "封锁 853 端口 DoT、mDNS 及公共 DNS 绕过",
                    checked = guardConfig.dnsGuardEnabled,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(dnsGuardEnabled = it)) }
                )
                SwitchPreference(
                    title = "核心域名白名单",
                    summary = "优先放行 LSPosed、GitHub、小米账号等",
                    checked = guardConfig.whitelistEnabled,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(whitelistEnabled = it)) }
                )
            }

            // 2. 系统与模块联动
            SmallTitle(text = "系统与自启选项")

            Card {
                SwitchPreference(
                    title = "开机自动启动防护",
                    summary = "设备重启后自动拉起防火墙与内核监听",
                    checked = guardConfig.bootAutoStart,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(bootAutoStart = it)) }
                )
                SwitchPreference(
                    title = "同步写入 Magisk/KernelSU 模块",
                    summary = "在 /data/adb/modules 同步写入 hosts 保证系统级持久化",
                    checked = guardConfig.moduleSyncMode,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(moduleSyncMode = it)) }
                )
                SwitchPreference(
                    title = "内核拦截日志采集",
                    summary = "通过 iptables -j LOG 实时监听阻断事件",
                    checked = guardConfig.logEnabled,
                    onCheckedChange = { onUpdateConfig(guardConfig.copy(logEnabled = it)) }
                )
            }

            // 3. 底层排错与诊断
            SmallTitle(text = "底层诊断与维护")

            Card {
                ArrowPreference(
                    title = "运行底层诊断脚本",
                    summary = "检查 iptables 规则链、hosts 挂载与 root 状态",
                    onClick = onOpenDiagnostic
                )
            }

            // 4. 关于项目
            SmallTitle(text = "关于隐私守卫")

            Card {
                ArrowPreference(
                    title = "关于本软件",
                    summary = "版本 v${BuildConfig.VERSION_NAME} · 软件来源、二开架构与开源协议",
                    onClick = onOpenAbout
                )
            }
        }
    }
}

/** 诊断脚本专属页面：结构化展示各项底层状态与指标 */
@Composable
private fun DiagnosticPage(
    diagnosticOutput: String,
    onRunDiagnostic: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "底层环境诊断",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 结构化状态矩阵
            val isChecking = diagnosticOutput.isBlank() || diagnosticOutput == "正在诊断中..."

            if (isChecking) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = Color(0xFF0A59F7),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "正在检测系统底层环境与安全链...",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                DiagnosticCardsView(output = diagnosticOutput)
            }

            // 重新运行按钮
            Button(
                onClick = onRunDiagnostic,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    color = Color(0xFF0A59F7),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("重新运行诊断", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 结构化解析诊断输出并渲染成精美卡片 */
@Composable
private fun DiagnosticCardsView(output: String) {
    val lines = output.lines()

    // 提炼关键指标
    val rootLine = lines.firstOrNull { it.contains("ROOT 权限:") } ?: ""
    val isRootOk = rootLine.contains("已授权")

    val flavorLine = lines.firstOrNull { it.contains("Root 方案:") }?.substringAfter("Root 方案:")?.trim() ?: "未检测到"
    val iptLine = lines.firstOrNull { it.contains("iptables:") }?.substringAfter("iptables:")?.trim() ?: "未知"
    val chainLine = lines.firstOrNull { it.contains("IPv4 防护链:") }?.substringAfter("IPv4 防护链:")?.trim() ?: "未挂载"
    val isChainOk = chainLine.contains("已挂载")

    val selinuxLine = lines.firstOrNull { it.contains("SELinux 模式:") }?.substringAfter("SELinux 模式:")?.trim() ?: "未知"
    val hostsLine = lines.firstOrNull { it.contains("Hosts 挂载:") }?.substringAfter("Hosts 挂载:")?.trim() ?: "未挂载"
    val isHostsOk = hostsLine.contains("个挂载点")

    // 1. Root 与权限状态
    DiagnosticStatusCard(
        title = "ROOT 运行环境",
        summary = if (isRootOk) "已获取最高 Root 权限（uid=0）" else "未检测到 Root 权限",
        badge = flavorLine,
        isSuccess = isRootOk
    )

    // 2. 防火墙与规则链
    DiagnosticStatusCard(
        title = "iptables 内核防火墙",
        summary = if (isChainOk) "防御链 fj_blk_out 正常生效 ($iptLine)" else "防御链未挂载或 iptables 异常 ($iptLine)",
        badge = if (isChainOk) "已熔断" else "未挂载",
        isSuccess = isChainOk
    )

    // 3. SELinux 与 Hosts
    DiagnosticStatusCard(
        title = "Hosts 文件与挂载",
        summary = "SELinux: $selinuxLine · 挂载状态: $hostsLine",
        badge = if (isHostsOk) "系统级挂载" else "文件写入模式",
        isSuccess = isHostsOk
    )

    // 4. 详细诊断终端原始日志（可折叠）
    var showRawLog by remember { mutableStateOf(false) }
    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRawLog = !showRawLog }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "终端原始检测输出",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.rotate(if (showRawLog) 180f else 0f).size(18.dp)
                )
            }

            AnimatedVisibility(visible = showRawLog) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF24292E))
                        .padding(10.dp)
                ) {
                    Text(
                        text = output,
                        fontSize = 11.sp,
                        color = Color(0xFFE1E4E8),
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStatusCard(
    title: String,
    summary: String,
    badge: String,
    isSuccess: Boolean
) {
    HyperCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSuccess) Color(0xFF00C070).copy(alpha = 0.12f)
                        else Color(0xFFFF3B30).copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isSuccess) Color(0xFF00C070) else Color(0xFFFF3B30),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isSuccess) Color(0xFF00C070).copy(alpha = 0.1f)
                                else Color(0xFFFF8800).copy(alpha = 0.1f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSuccess) Color(0xFF00C070) else Color(0xFFFF8800)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = summary,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

/** 关于页面：深度阐述软件来源、二次开发背景与二开技术架构 */
@Composable
private fun AboutPage(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "关于隐私守卫",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // App 标识卡
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0A59F7).copy(alpha = 0.08f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0A59F7).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF0A59F7),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "隐私守卫 (Privacy Guard)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} · 专为 MIUI / HyperOS 打造",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            // 软件来源与二开说明
            AboutSection(title = "软件来源与二开背景", icon = Icons.Default.Info) {
                AboutItem(
                    label = "软件性质",
                    value = "本项目属于二次开发作品（二开）。其核心防护理念与部分规则库源自开源社区的「Hyper 隐私卫士」项目。"
                )
                AboutItem(
                    label = "二开动机与优化",
                    value = "针对原生工具界面交互简陋、缺乏状态感知、规则生效不透明等痛点，本项目进行了自底层网络栈至顶层 UI 的全栈重构，打造了一套原生沉浸式的 HyperOS 体验与四层立体防御体系。"
                )
                AboutItem(
                    label = "设备与权限要求",
                    value = "面向小米 / Redmi 全系列机型（MIUI 14 / HyperOS 1.x / HyperOS 2.x）。需要获取设备 Root 权限（支持 Magisk、KernelSU、APatch 等主流方案）。"
                )
            }

            // 二开技术架构
            AboutSection(title = "二开技术架构", icon = Icons.Default.Code) {
                AboutItem(
                    label = "1. 原生 UI 规范 (Miuix KMP + Compose)",
                    value = "全面采用 Jetpack Compose 与 Miuix KMP 声明式组件库，深度对齐小米 HyperOS 视觉与交互规范，支持动态圆角、无感折叠与沉浸式状态栏适配。"
                )
                AboutItem(
                    label = "2. 四层立体防御引擎",
                    value = "· L1 Hosts 阻断层：将遥测/广告/反诈域名本地映射至 0.0.0.0；\n" +
                        "· L2 IPv4 熔断层：通过 iptables 针对域名解析真实 IP 进行内核级丢弃；\n" +
                        "· L3 IPv6 双栈同封：采用 ip6tables 同步封锁 IPv6 隧道偷跑；\n" +
                        "· L4 DNS 强制兜底：封锁 853 端口 DoT 与公共 DoH 加密规避通道。"
                )
                AboutItem(
                    label = "3. 系统级服务深度管控 (AppOps + PM)",
                    value = "提供 pm disable-user 彻底冻结、cmd appops 后台常驻与位置权限剥夺等多维管控手段，并独创反诈记录本地数据库整库备份还原机制。"
                )
                AboutItem(
                    label = "4. 响应式数据流与内核监听",
                    value = "采用 Room 数据库持久化规则与历史拦截，结合 iptables -j LOG 与 dmesg 只读水位线轮询，实现无残留的实时内核拦截捕获。"
                )
            }

            // 开源与免责声明
            AboutSection(title = "开源与免责声明", icon = Icons.Default.Shield) {
                AboutItem(
                    label = "开源协议",
                    value = "遵循 GPL-3.0 开源协议，遵循开源社区开源共享精神。"
                )
                AboutItem(
                    label = "免责声明",
                    value = "本软件专为个人隐私防护与网络优化设计。使用 Root 涉及修改底层网络配置，请遵循当地法律法规，作者不对因操作不当或系统兼容性引发的问题承担责任。"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AboutSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF0A59F7),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF555555)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF7F8FA))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun AboutItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF222222)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = Color(0xFF666666),
            lineHeight = 17.sp
        )
    }
}
