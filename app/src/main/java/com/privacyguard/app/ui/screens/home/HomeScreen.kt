package com.privacyguard.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.data.model.DashboardState
import com.privacyguard.app.data.model.EngineStatus
import com.privacyguard.app.data.preference.GuardConfig
import com.privacyguard.app.ui.components.HyperCard
import com.privacyguard.app.ui.components.ProtectionBanner
import com.privacyguard.app.ui.components.StatGauge
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

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
            TopAppBar(
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

            // 3. 小米专属隐私健康体检卡片
            HyperCard(
                onClick = onNavigateToSystem
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (dashboardState.privacyScore >= 80) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${dashboardState.privacyScore}",
                            color = if (dashboardState.privacyScore >= 80) Color(0xFF00C070) else Color(0xFFFF3B30),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "小米系统隐私体检得分",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (dashboardState.privacyScore >= 80) "小米遥测与广告探针已得到有效遏制" else "发现常驻采集宿主（小米统计/MSA/运营商探针）",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "查看",
                        tint = Color.Gray
                    )
                }
            }

            // 4. 四层立体防护状态矩阵
            Text(
                text = "四层防护架构矩阵",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HyperCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LayerItemRow(
                        name = "L1 · Hosts 屏蔽层",
                        desc = "底层将遥测/广告/反诈域名映射至 0.0.0.0",
                        isActive = dashboardState.layerStatus.hostsActive && guardConfig.hostsEnabled
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    LayerItemRow(
                        name = "L2 · IPv4 硬件熔断层",
                        desc = "iptables/ipset 封杀解析出的真实 IP，阻断硬编码直连",
                        isActive = dashboardState.layerStatus.ipv4Active && guardConfig.ipv4Enabled
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    LayerItemRow(
                        name = "L3 · IPv6 双栈同封",
                        desc = "ip6tables 同步拦截 v6 隧道偷跑，全栈无死角",
                        isActive = dashboardState.layerStatus.ipv6Active && guardConfig.ipv6Enabled
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
                    LayerItemRow(
                        name = "L4 · DNS 强制兜底",
                        desc = "封 DoT 853 / mDNS / 公共 DNS，杜绝加密规避",
                        isActive = dashboardState.layerStatus.dnsGuardActive && guardConfig.dnsGuardEnabled
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.2f))
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
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0A59F7)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("一键刷新域名解析与 IP 规则库", fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
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
