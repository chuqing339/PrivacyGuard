package com.privacyguard.app.ui.screens.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.data.model.PkgKind
import com.privacyguard.app.data.model.PkgState
import com.privacyguard.app.data.model.PrivacyScanItem
import com.privacyguard.app.data.model.RunningProcessItem
import com.privacyguard.app.ui.components.HyperCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun SystemPrivacyScreen(
    privacyScore: Int,
    scanItems: List<PrivacyScanItem>,
    runningProcs: List<RunningProcessItem>,
    onRescan: () -> Unit,
    onOneClickOptimize: () -> Unit,
    onTogglePackage: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = "小米隐私深度体检",
                actions = {
                    IconButton(onClick = onRescan) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新体检")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 体检得分卡片
            item {
                HyperCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(
                                    if (privacyScore >= 80) Color(0xFF00C070).copy(alpha = 0.15f)
                                    else Color(0xFFFF3B30).copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$privacyScore",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (privacyScore >= 80) Color(0xFF00C070) else Color(0xFFFF3B30)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (privacyScore >= 80) "隐私防护良好" else "存在系统级遥测隐患",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "MIUI/HyperOS 后台预置多个静默搜集用户行为与设备指纹的常驻探针",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onOneClickOptimize,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = Color(0xFF00C070),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("一键安全冻结无用遥测与广告探针", fontSize = 14.sp)
                    }
                }
            }

            // 2. 小米系统采集宿主组件管理
            item {
                SmallTitle(text = "系统级采集宿主服务 (${scanItems.size})")
            }

            items(scanItems, key = { it.packageName }) { item ->
                PrivacyPackageCard(
                    item = item,
                    onStateChange = { targetState ->
                        onTogglePackage(item.packageName, targetState)
                    }
                )
            }

            // 3. 运行中敏感探测进程
            if (runningProcs.isNotEmpty()) {
                item {
                    SmallTitle(text = "正在活跃的采集/安全引擎进程 (${runningProcs.size})")
                }

                items(runningProcs, key = { it.name }) { proc ->
                    HyperCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF8800))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = proc.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(text = "进程: ${proc.name} · PID: ${proc.pid}", fontSize = 11.sp, color = Color.Gray)
                            }
                            Text(text = "运行中", fontSize = 12.sp, color = Color(0xFFFF8800))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyPackageCard(
    item: PrivacyScanItem,
    onStateChange: (Int) -> Unit
) {
    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 风险类别指示
                val kindColor = when (item.kind) {
                    PkgKind.SAFE -> Color(0xFF00C070)
                    PkgKind.OPT -> Color(0xFFFF8800)
                    PkgKind.RISK -> Color(0xFFFF3B30)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(kindColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = when (item.kind) {
                            PkgKind.SAFE -> "低风险"
                            PkgKind.OPT -> "优化项"
                            PkgKind.RISK -> "需谨慎"
                        },
                        color = kindColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = item.packageName,
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                // 当前状态小徽章
                val (stateText, stateColor) = when (item.state) {
                    PkgState.ENABLED -> "运行中" to Color(0xFFFF3B30)
                    PkgState.DISABLED -> "已冻结" to Color(0xFF00C070)
                    PkgState.RESTRICTED -> "权限受限" to Color(0xFF0A59F7)
                    PkgState.ABSENT -> "未预装" to Color.LightGray
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(stateColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stateText,
                        color = stateColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (item.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.description,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    lineHeight = 16.sp
                )
            }

            if (item.warningText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.warningText,
                    fontSize = 11.sp,
                    color = Color(0xFFFF8800),
                    lineHeight = 15.sp
                )
            }

            // 操作按钮组
            if (item.state != PkgState.ABSENT) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.state != PkgState.DISABLED) {
                        Button(
                            onClick = { onStateChange(0) },
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            minHeight = 32.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFFFF3B30).copy(alpha = 0.12f),
                                contentColor = Color(0xFFFF3B30)
                            )
                        ) {
                            Text("彻底冻结", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (item.state != PkgState.RESTRICTED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onStateChange(2) },
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            minHeight = 32.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFF0A59F7).copy(alpha = 0.12f),
                                contentColor = Color(0xFF0A59F7)
                            )
                        ) {
                            Text("权限限制", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (item.state != PkgState.ENABLED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            text = "恢复",
                            onClick = { onStateChange(1) },
                            minHeight = 32.dp
                        )
                    }
                }
            }
        }
    }
}
