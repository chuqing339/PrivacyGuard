package com.privacyguard.app.ui.screens.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.window.Dialog
import com.privacyguard.app.data.model.PkgKind
import com.privacyguard.app.data.model.PkgState
import com.privacyguard.app.data.model.PrivacyScanItem
import com.privacyguard.app.data.model.RunningProcessItem
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
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun SystemPrivacyScreen(
    privacyScore: Int,
    scanItems: List<PrivacyScanItem>,
    runningProcs: List<RunningProcessItem>,
    onRescan: () -> Unit,
    onOneClickOptimize: () -> Unit,
    onTogglePackage: (String, Int) -> Unit,
    onCleanFraudResidue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOptimizeConfirm by remember { mutableStateOf(false) }
    var pendingFreezeItem by remember { mutableStateOf<PrivacyScanItem?>(null) }
    var showResidueConfirm by remember { mutableStateOf(false) }

    val optimizeTargets = scanItems.filter {
        (it.kind == PkgKind.SAFE || it.packageName == "com.mobiletools.systemhelper") &&
            it.state == PkgState.ENABLED
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "小米隐私深度体检")
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 体检得分卡片（集成了重新体检按钮）
            item {
                HyperCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(
                                    if (privacyScore >= 80) Color(0xFF00C070).copy(alpha = 0.15f)
                                    else Color(0xFFFF3B30).copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$privacyScore",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (privacyScore >= 80) Color(0xFF00C070) else Color(0xFFFF3B30)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (privacyScore >= 80) "隐私防护良好" else "存在系统级遥测隐患",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "MIUI/HyperOS 后台常驻探针监控与分析",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }

                        IconButton(onClick = onRescan) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重新体检",
                                tint = Color(0xFF0A59F7)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { showOptimizeConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = Color(0xFF00C070),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("一键安全冻结无用遥测与广告探针", fontSize = 13.sp)
                    }
                }
            }

            // 2. 本地采集残留清理
            item {
                SmallTitle(text = "本地采集残留清理")
            }
            item {
                HyperCard {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "反诈行为记录深度清理",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "通话、屏幕共享等记录会留存于手机管家本地数据库（fraud_detection_data.db），仅拦截无法清除历史。本操作整库安全备份后清除原库，可随时还原。",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showResidueConfirm = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFFFF8800).copy(alpha = 0.12f),
                                contentColor = Color(0xFFFF8800)
                            )
                        ) {
                            Text("备份并清理反诈记录", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 3. 小米系统采集宿主组件管理
            item {
                SmallTitle(text = "系统级采集宿主服务 (${scanItems.size})")
            }

            items(scanItems, key = { it.packageName }) { item ->
                PrivacyPackageCard(
                    item = item,
                    onStateChange = { targetState ->
                        onTogglePackage(item.packageName, targetState)
                    },
                    onFreeze = { pendingFreezeItem = it }
                )
            }

            // 4. 运行中敏感探测进程
            if (runningProcs.isNotEmpty()) {
                item {
                    SmallTitle(text = "正在活跃的采集/安全引擎进程 (${runningProcs.size})")
                }

                items(runningProcs, key = { it.name }) { proc ->
                    RunningProcCard(proc = proc)
                }
            }
        }
    }

    // 二次确认对话框
    if (showOptimizeConfirm) {
        ConfirmDialog(
            title = "确认一键深度优化",
            packages = optimizeTargets.map { it.label },
            warningText = "将对以上启用中的组件执行彻底冻结（pm disable-user），其中运营商探针涉及 OTA 与 SIM 远程配置通道。",
            onConfirm = {
                onOneClickOptimize()
                showOptimizeConfirm = false
            },
            onDismiss = { showOptimizeConfirm = false }
        )
    }

    pendingFreezeItem?.let { item ->
        ConfirmDialog(
            title = "确认彻底冻结",
            packages = listOf(item.label),
            warningText = item.warningText.ifBlank { "冻结期间该组件及其后台上报将完全停止运行。" },
            onConfirm = {
                onTogglePackage(item.packageName, 0)
                pendingFreezeItem = null
            },
            onDismiss = { pendingFreezeItem = null }
        )
    }

    if (showResidueConfirm) {
        ConfirmDialog(
            title = "确认清理反诈行为记录",
            packages = listOf("com.miui.securitycenter · fraud_detection_data.db"),
            warningText = "将删除手机管家本地保存的历史反诈记录。删除前会自动备份到 /data/adb/privacy_guard/fraud_backup/，需要时可手动还原。",
            onConfirm = {
                onCleanFraudResidue()
                showResidueConfirm = false
            },
            onDismiss = { showResidueConfirm = false }
        )
    }
}

/** 运行中进程卡片（轻量整洁） */
@Composable
private fun RunningProcCard(proc: RunningProcessItem) {
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
                Text(text = proc.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${proc.name} · PID: ${proc.pid}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFF8800).copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(text = "运行中", fontSize = 11.sp, color = Color(0xFFFF8800), fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 隐私组件卡片：精心排版层次，小字提示采用独立背景容器，避免挤压杂乱
 */
@Composable
private fun PrivacyPackageCard(
    item: PrivacyScanItem,
    onStateChange: (Int) -> Unit,
    onFreeze: (PrivacyScanItem) -> Unit
) {
    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 第一行：标题 + 状态徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                val (stateText, stateColor) = when (item.state) {
                    PkgState.ENABLED -> "运行中" to Color(0xFFFF3B30)
                    PkgState.DISABLED -> "已冻结" to Color(0xFF00C070)
                    PkgState.RESTRICTED -> "权限受限" to Color(0xFF0A59F7)
                    PkgState.ABSENT -> "未预装" to Color(0xFF8A919F)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(stateColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stateText,
                        color = stateColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 第二行：风险级别标签 + 包名
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val kindColor = when (item.kind) {
                    PkgKind.SAFE -> Color(0xFF00C070)
                    PkgKind.OPT -> Color(0xFFFF8800)
                    PkgKind.RISK -> Color(0xFFFF3B30)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(kindColor.copy(alpha = 0.12f))
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

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = item.packageName,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
            }

            // 功能描述
            if (item.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.description,
                    fontSize = 12.sp,
                    color = Color(0xFF666666),
                    lineHeight = 16.sp
                )
            }

            // 警告提示：独立容器块，带图标与背景底色，避免裸露挤在一起
            if (item.warningText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFFFF7E6))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFD46B08),
                            modifier = Modifier
                                .size(13.dp)
                                .padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = item.warningText,
                            fontSize = 11.sp,
                            color = Color(0xFFD46B08),
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // 底部操作按钮组
            if (item.state != PkgState.ABSENT) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.state != PkgState.DISABLED) {
                        Button(
                            onClick = { onFreeze(item) },
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            minHeight = 32.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFFFF3B30).copy(alpha = 0.1f),
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
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            minHeight = 32.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFF0A59F7).copy(alpha = 0.1f),
                                contentColor = Color(0xFF0A59F7)
                            )
                        ) {
                            Text("权限限制", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (item.state != PkgState.ENABLED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onStateChange(1) },
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            minHeight = 32.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFF8A919F).copy(alpha = 0.12f),
                                contentColor = Color(0xFF555555)
                            )
                        ) {
                            Text("恢复", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    packages: List<String>,
    warningText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp)
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            if (packages.isEmpty()) {
                Text("当前未发现可操作的组件。", fontSize = 13.sp, color = Color.Gray)
            } else {
                Text("受影响组件：", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = packages.joinToString("\n") { "· $it" },
                    fontSize = 12.sp,
                    color = Color.Gray,
                    lineHeight = 17.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = warningText,
                fontSize = 12.sp,
                color = Color(0xFFFF8800),
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "操作可随时回到体检页撤销与恢复，不损坏系统。",
                fontSize = 12.sp,
                color = Color(0xFF00C070),
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onConfirm,
                    enabled = packages.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        color = Color(0xFFFF3B30),
                        contentColor = Color.White
                    )
                ) {
                    Text("确认执行")
                }
            }
        }
    }
}
