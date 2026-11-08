package com.privacyguard.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.data.model.DashboardState
import com.privacyguard.app.data.preference.GuardConfig
import com.privacyguard.app.ui.components.HyperCard
import com.privacyguard.app.ui.components.ProtectionBanner
import com.privacyguard.app.ui.components.StatGauge
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun HomeScreen(
    dashboardState: DashboardState,
    guardConfig: GuardConfig,
    onToggleMaster: () -> Unit,
    onRefreshRules: () -> Unit,
    onNavigateToSystem: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "隐私守卫"
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
            // 1. 顶部盾牌主状态 Banner
            ProtectionBanner(
                status = dashboardState.engineStatus,
                rootFlavor = dashboardState.rootFlavor,
                onToggleClick = onToggleMaster
            )

            // 2. 指标小仪表盘
            StatGauge(
                totalIntercept = dashboardState.totalInterceptCount,
                todayIntercept = dashboardState.todayInterceptCount,
                activeRules = dashboardState.layerStatus.activeRuleCount
            )

            // 4. 四层立体防护状态矩阵
            SmallTitle(text = "四层防护架构矩阵")

            HyperCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LayerItemRow(
                        name = "L1 · Hosts 屏蔽层",
                        desc = "底层将遥测/广告/反诈域名映射至 0.0.0.0",
                        isActive = dashboardState.layerStatus.hostsActive && guardConfig.hostsEnabled
                    )
                    LayerDivider()
                    LayerItemRow(
                        name = "L2 · IPv4 硬件熔断层",
                        desc = "iptables/ipset 封杀解析出的真实 IP，阻断硬编码直连",
                        isActive = dashboardState.layerStatus.ipv4Active && guardConfig.ipv4Enabled
                    )
                    LayerDivider()
                    LayerItemRow(
                        name = "L3 · IPv6 双栈同封",
                        desc = "ip6tables 同步拦截 v6 隧道偷跑，全栈无死角",
                        isActive = dashboardState.layerStatus.ipv6Active && guardConfig.ipv6Enabled
                    )
                    LayerDivider()
                    LayerItemRow(
                        name = "L4 · DNS 强制兜底",
                        desc = "封 DoT 853 / mDNS / 公共 DNS，杜绝加密规避",
                        isActive = dashboardState.layerStatus.dnsGuardActive && guardConfig.dnsGuardEnabled
                    )
                    LayerDivider()
                    LayerItemRow(
                        name = "白名单 · 核心业务保护",
                        desc = "放行 GitHub、LSPosed 在线更新与小米账号验证",
                        isActive = dashboardState.layerStatus.whitelistActive && guardConfig.whitelistEnabled
                    )
                }
            }

            // 5. 快速操作按钮
            Button(
                onClick = onRefreshRules,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("一键刷新域名解析与 IP 规则库")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LayerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.LightGray.copy(alpha = 0.2f))
    )
}

@Composable
private fun LayerItemRow(
    name: String,
    desc: String,
    isActive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFF00C070) else Color(0xFFB0B7C3))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = desc, fontSize = 11.sp, color = Color.Gray)
        }
        Text(
            text = if (isActive) "已生效" else "未开启",
            fontSize = 12.sp,
            color = if (isActive) Color(0xFF00C070) else Color.Gray,
            fontWeight = FontWeight.Medium
        )
    }
}
