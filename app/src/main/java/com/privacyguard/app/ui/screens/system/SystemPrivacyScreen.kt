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
    onCleanFraudResidue: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 危险冻结操作二次确认状态
    var showOptimizeConfirm by remember { mutableStateOf(false) }
    var pendingFreezeItem by remember { mutableStateOf<PrivacyScanItem?>(null) }
    // 反诈记录清理二次确认
    var showResidueConfirm by remember { mutableStateOf(false) }

    // 与 PrivacyRepository.oneClickOptimize 的目标筛选口径保持一致
    val optimizeTargets = scanItems.filter {
        (it.kind == PkgKind.SAFE || it.packageName == "com.mobiletools.systemhelper") &&
            it.state == PkgState.ENABLED
    }

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
                        onClick = { showOptimizeConfirm = true },
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

            // 2. 本地采集残留清理（拦截上报 ≠ 清除已落地的历史数据）
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
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "通话、屏幕共享等反诈行为记录会长期留存在手机管家本地数据库（fraud_detection_data.db）中，仅靠拦截上报无法清除历史。本操作先整库备份到 /data/adb/privacy_guard/fraud_backup/，再删除原文件，可随时还原。",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showResidueConfirm = true },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFFFF8800).copy(alpha = 0.15f),
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

    // 一键深度优化：批量 pm disable-user 冻结前二次确认
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

    // 单项「彻底冻结」（disable 语义）前二次确认；「恢复」与「权限限制」不弹
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

    // 反诈记录清理前二次确认（会先整库备份再删除）
    if (showResidueConfirm) {
        ConfirmDialog(
            title = "确认清理反诈行为记录",
            packages = listOf("com.miui.securitycenter · fraud_detection_data.db（含 -wal/-shm 伴生文件）"),
            warningText = "将删除手机管家本地保存的全部历史反诈行为记录（通话/屏幕共享时长等）。删除前会整库备份到 /data/adb/privacy_guard/fraud_backup/<时间戳>/，需要时可手动还原。",
            onConfirm = {
                onCleanFraudResidue()
                showResidueConfirm = false
            },
            onDismiss = { showResidueConfirm = false }
        )
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
                Text("当前未发现可冻结的启用中组件。", fontSize = 13.sp, color = Color.Gray)
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
                text = "冻结操作可随时回到体检页点击「恢复」撤销，不影响系统数据。",
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

@Composable
private fun PrivacyPackageCard(
    item: PrivacyScanItem,
    onStateChange: (Int) -> Unit,
    onFreeze: (PrivacyScanItem) -> Unit
) {
    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 第一行：标题占满剩余宽度自动换行，状态徽章固定在右侧不再被挤压
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

                // 当前状态小徽章
                val (stateText, stateColor) = when (item.state) {
                    PkgState.ENABLED -> "运行中" to Color(0xFFFF3B30)
                    PkgState.DISABLED -> "已冻结" to Color(0xFF00C070)
                    PkgState.RESTRICTED -> "权限受限" to Color(0xFF0A59F7)
                    PkgState.ABSENT -> "未预装" to Color(0xFF8A919F)
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

            // 第二行：风险类别标签 + 包名
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

                Text(
                    text = item.packageName,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
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
                            onClick = { onFreeze(item) },
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            minHeight = 36.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFFFF3B30).copy(alpha = 0.12f),
                                contentColor = Color(0xFFFF3B30)
                            )
                        ) {
                            Text("彻底冻结", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (item.state != PkgState.RESTRICTED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onStateChange(2) },
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            minHeight = 36.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFF0A59F7).copy(alpha = 0.12f),
                                contentColor = Color(0xFF0A59F7)
                            )
                        ) {
                            Text("权限限制", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (item.state != PkgState.ENABLED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onStateChange(1) },
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            minHeight = 36.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFF8A919F).copy(alpha = 0.15f),
                                contentColor = Color(0xFF8A919F)
                            )
                        ) {
                            Text("恢复", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
