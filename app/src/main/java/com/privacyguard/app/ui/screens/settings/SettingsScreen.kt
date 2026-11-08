package com.privacyguard.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.data.preference.GuardConfig
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

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
                    summary = "检查 iptables 规则链、hosts 挂载与 busybox 状态",
                    onClick = {
                        onRunDiagnostic()
                        showDiagDialog = true
                    }
                )
            }

            // 4. 关于项目
            SmallTitle(text = "关于隐私守卫")

            Card {
                ArrowPreference(
                    title = "小米专属 Root 隐私防护",
                    summary = "版本 v${BuildConfig.VERSION_NAME} · 基于 Miuix 原生 HyperOS 规范构建"
                )
            }
        }
    }

    if (showDiagDialog) {
        Dialog(onDismissRequest = { showDiagDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "环境诊断结果",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = diagnosticOutput.ifBlank { "正在检测底层命令与网络链..." },
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showDiagDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}
