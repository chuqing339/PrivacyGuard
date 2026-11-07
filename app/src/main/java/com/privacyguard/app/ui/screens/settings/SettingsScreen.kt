package com.privacyguard.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.data.preference.GuardConfig
import com.privacyguard.app.ui.components.HyperCard
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun SettingsScreen(
    guardConfig: GuardConfig,
    onUpdateConfig: (GuardConfig) -> Unit,
    onRunDiagnostic: () -> Unit,
    diagnosticOutput: String,
    modifier: Modifier = Modifier
) {
    var showDiagDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "高级防护设置"
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 各层拦截策略
            Text(
                text = "各层策略与开关",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HyperCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitchRow(
                        title = "L1 · Hosts 域名层拦截",
                        subtitle = "将所有规则域名本地解析为 0.0.0.0",
                        checked = guardConfig.hostsEnabled,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(hostsEnabled = it)) }
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    SettingSwitchRow(
                        title = "L2 · IPv4 iptables 链拦截",
                        subtitle = "阻断解析所得真实 IP，拦截硬编码直连",
                        checked = guardConfig.ipv4Enabled,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(ipv4Enabled = it)) }
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    SettingSwitchRow(
                        title = "L3 · IPv6 双栈同封",
                        subtitle = "启用 ip6tables 防止应用利用 IPv6 绕过",
                        checked = guardConfig.ipv6Enabled,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(ipv6Enabled = it)) }
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    SettingSwitchRow(
                        title = "L4 · DNS 强制兜底 (Anti-DoH)",
                        subtitle = "封锁 853 端口 DoT、mDNS 及公共 DNS 绕过",
                        checked = guardConfig.dnsGuardEnabled,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(dnsGuardEnabled = it)) }
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    SettingSwitchRow(
                        title = "核心域名白名单",
                        subtitle = "优先放行 LSPosed、GitHub、小米账号等",
                        checked = guardConfig.whitelistEnabled,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(whitelistEnabled = it)) }
                    )
                }
            }

            // 2. 系统与模块联动
            Text(
                text = "系统与自启选项",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HyperCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitchRow(
                        title = "开机自动启动防护",
                        subtitle = "设备重启后自动拉起防火墙与内核监听",
                        checked = guardConfig.bootAutoStart,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(bootAutoStart = it)) }
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    SettingSwitchRow(
                        title = "同步写入 Magisk/KernelSU 模块",
                        subtitle = "在 /data/adb/modules 同步写入 hosts 保证系统级持久化",
                        checked = guardConfig.moduleSyncMode,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(moduleSyncMode = it)) }
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    SettingSwitchRow(
                        title = "内核拦截日志采集",
                        subtitle = "在 iptables 中加入限速 LOG 用于监控分析",
                        checked = guardConfig.logEnabled,
                        onCheckedChange = { onUpdateConfig(guardConfig.copy(logEnabled = it)) }
                    )
                }
            }

            // 3. 诊断与排错
            Text(
                text = "工具与诊断",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HyperCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("一键环境诊断", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("测试 busybox、iptables 挂载链、SELinux 上下文", fontSize = 12.sp, color = Color.Gray)
                    }
                    Button(
                        onClick = {
                            onRunDiagnostic()
                            showDiagDialog = true
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("运行诊断", fontSize = 13.sp)
                    }
                }
            }

            // 4. 关于卡片
            HyperCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF0A59F7),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("隐私守卫 (PrivacyGuard)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("版本 2.6.0 · 专为小米手机 (HyperOS / MIUI) 深度定制", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDiagDialog) {
        AlertDialog(
            onDismissRequest = { showDiagDialog = false },
            title = { Text("环境诊断结果") },
            text = {
                Text(
                    text = diagnosticOutput.ifBlank { "正在检测底层命令与网络链..." },
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showDiagDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF0A59F7)
            )
        )
    }
}
