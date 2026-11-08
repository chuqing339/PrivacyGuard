package com.privacyguard.app.ui.screens.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.privacyguard.app.data.local.entity.InterceptLogEntity
import com.privacyguard.app.data.model.PkgKind
import com.privacyguard.app.data.model.PkgState
import com.privacyguard.app.data.model.PrivacyScanItem
import com.privacyguard.app.data.model.RunningProcessItem
import com.privacyguard.app.data.repository.PackageInterceptStats
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SystemPrivacyScreen(
    privacyScore: Int,
    scanItems: List<PrivacyScanItem>,
    runningProcs: List<RunningProcessItem>,
    onRescan: () -> Unit,
    onOneClickOptimize: () -> Unit,
    onTogglePackage: (String, Int) -> Unit,
    onCleanFraudResidue: () -> Unit,
    onQueryPackageStats: suspend (PrivacyScanItem) -> PackageInterceptStats,
    modifier: Modifier = Modifier
) {
    var showOptimizeConfirm by remember { mutableStateOf(false) }
    var pendingFreezeItem by remember { mutableStateOf<PrivacyScanItem?>(null) }
    var showResidueConfirm by remember { mutableStateOf(false) }

    // 组件详情弹窗：卡片保持极简，介绍与拦截动向收进这里
    var detailItem by remember { mutableStateOf<PrivacyScanItem?>(null) }
    var detailStats by remember { mutableStateOf<PackageInterceptStats?>(null) }
    val detailTimeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(detailItem?.packageName) {
        val item = detailItem ?: return@LaunchedEffect
        detailStats = null
        detailStats = onQueryPackageStats(item)
    }

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
                        Text("一键安全冻结采集探针", fontSize = 13.sp)
                    }
                }
            }

            // 2. 反诈记录清理（紧凑单行）
            item {
                HyperCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "反诈记录深度清理",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "先整库备份再删除，可随时还原",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Button(
                            onClick = { showResidueConfirm = true },
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            minHeight = 36.dp,
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFFFF8800).copy(alpha = 0.15f),
                                contentColor = Color(0xFFFF8800)
                            )
                        ) {
                            Text("清理", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 3. 采集宿主服务：按风险层级分组（低风险 → 优化项 → 需谨慎），组头样式与白名单页一致
            if (scanItems.isEmpty()) {
                item(key = "scan_empty") {
                    Text(
                        text = "尚未扫描到组件，点击右上角重新体检",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }
            listOf(
                PkgKind.SAFE to "低风险",
                PkgKind.OPT to "优化项",
                PkgKind.RISK to "需谨慎"
            ).forEach { (kind, groupTitle) ->
                val members = scanItems.filter { it.kind == kind }
                if (members.isNotEmpty()) {
                    item(key = "header_$kind") {
                        Text(
                            text = "$groupTitle · ${members.size} 项",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF66707A),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(members, key = { it.packageName }) { item ->
                        PrivacyPackageCard(
                            item = item,
                            onClick = { detailItem = item },
                            onStateChange = { targetState ->
                                onTogglePackage(item.packageName, targetState)
                            },
                            onFreeze = { pendingFreezeItem = it }
                        )
                    }
                }
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
    detailItem?.let { item ->
        PackageDetailDialog(
            item = item,
            stats = detailStats,
            dateFormat = detailTimeFormat,
            onClose = {
                detailItem = null
                detailStats = null
            }
        )
    }

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
 * 隐私组件卡片：极简两行（标题+徽章 / 类别+包名）+ 操作按钮；
 * 介绍与拦截动向收进点击后的详情弹窗
 */
@Composable
private fun PrivacyPackageCard(
    item: PrivacyScanItem,
    onClick: () -> Unit,
    onStateChange: (Int) -> Unit,
    onFreeze: (PrivacyScanItem) -> Unit
) {
    HyperCard(onClick = onClick) {
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

/**
 * 组件详情弹窗：介绍（收归于此的长文案）+ 今日/累计拦截统计 + 今日动向列表
 */
@Composable
private fun PackageDetailDialog(
    item: PrivacyScanItem,
    stats: PackageInterceptStats?,
    dateFormat: SimpleDateFormat,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp)
        ) {
            // 标题行：名称 + 状态徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
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
                    Text(stateText, color = stateColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = item.packageName, fontSize = 11.sp, color = Color.Gray)

            // 介绍：卡片上收起的说明文案在此完整展示
            if (item.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = item.description, fontSize = 13.sp, color = Color.Gray, lineHeight = 18.sp)
            }
            if (item.warningText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = item.warningText, fontSize = 12.sp, color = Color(0xFFFF8800), lineHeight = 17.sp)
            }

            // 拦截统计：今日 / 累计
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("今日拦截", stats?.today, modifier = Modifier.weight(1f))
                StatBox("累计拦截", stats?.total, modifier = Modifier.weight(1f))
            }

            // 今日动向：该组件今天的逐条拦截事件
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = "今日动向", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            val events = stats?.todayEvents.orEmpty()
            when {
                stats == null -> Text("统计加载中…", fontSize = 12.sp, color = Color.Gray)
                events.isEmpty() -> Text(
                    text = if (item.state == PkgState.DISABLED) "组件已冻结，今日无活动" else "今日无拦截记录",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(events.size) { idx ->
                            val e = events[idx]
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(0xFFFF3B30).copy(alpha = 0.1f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(e.layerType, color = Color(0xFFFF3B30), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = e.target,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = dateFormat.format(Date(e.timestamp)),
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            if (idx < events.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Color.LightGray.copy(alpha = 0.2f))
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    color = Color(0xFF8A919F).copy(alpha = 0.15f),
                    contentColor = Color(0xFF555555)
                )
            ) {
                Text("关闭", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 统计数字块（今日/累计），有拦截时红色高亮 */
@Composable
private fun StatBox(label: String, value: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF8A919F).copy(alpha = 0.1f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value?.toString() ?: "—",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if ((value ?: 0) > 0) Color(0xFFFF3B30) else Color(0xFF8A919F)
        )
        Text(text = "$label（次）", fontSize = 11.sp, color = Color.Gray)
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
