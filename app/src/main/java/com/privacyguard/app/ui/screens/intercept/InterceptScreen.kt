@file:OptIn(ExperimentalFoundationApi::class)

package com.privacyguard.app.ui.screens.intercept

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.data.local.dao.AppInterceptCount
import com.privacyguard.app.data.local.entity.InterceptLogEntity
import com.privacyguard.app.ui.components.HyperCard
import com.privacyguard.app.ui.components.SegmentedTabs
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import java.text.SimpleDateFormat
import java.util.*

// 每个 App 分组最多展示的记录条数
private const val MAX_LOGS_PER_GROUP = 5
// 实时拦截流最多显示的分组（app）数量
private const val MAX_GROUPS = 20

@Composable
fun InterceptScreen(
    logs: List<InterceptLogEntity>,
    topApps: List<AppInterceptCount>,
    onDeleteAppLogs: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf("拦截日志", "偷跑排行")
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // 长按分组卡片进入的多选删除模式（仅作用于「拦截日志」页的 App 分组卡片）：
    // Pair 为 (packageName, appLabel)，packageName 取该组首条日志（可能为空串），
    // 删除时原样传给 onDeleteAppLogs
    var selectionMode by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf(setOf<Pair<String, String>>()) }
    // 长按与选中态下的单击共用：确保已进入选中模式后切换该组选中状态
    // （未选中时 selectionMode = true 是幂等操作，对应 onLongClick = { selectionMode = true; 切换选中 }）
    val toggleGroupSelection: (Pair<String, String>) -> Unit = { key ->
        selectionMode = true
        selectedGroups = if (key in selectedGroups) selectedGroups - key else selectedGroups + key
    }
    val exitSelectionMode: () -> Unit = {
        selectionMode = false
        selectedGroups = emptySet()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "拦截监控中心")
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 顶部一行两个 tab（HyperOS 分段控件风格）
            SegmentedTabs(
                tabs = tabTitles,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when (selectedTab) {
                0 -> {
                    // 按 app 分组（最多 MAX_GROUPS 组）；提升到分支顶部，供下方操作栏与列表共用
                    val grouped: List<Pair<String, List<InterceptLogEntity>>> = remember(logs) {
                        logs.groupBy { log ->
                            log.appLabel.ifBlank { log.packageName.ifBlank { "系统底层" } }
                        }
                            .entries
                            .sortedByDescending { it.value.first().timestamp }
                            .take(MAX_GROUPS)
                            .map { (k, v) -> k to v }
                    }
                    // 全集：分组名 + 该组首条日志的 packageName（与卡片选中键保持一致）
                    val allGroupKeys: Set<Pair<String, String>> = remember(grouped) {
                        grouped.map { (label, groupLogs) ->
                            (groupLogs.firstOrNull()?.packageName ?: "") to label
                        }.toSet()
                    }

                    // 多选操作栏：取消 / 全选 / 反选 / 删除所选
                    AnimatedVisibility(visible = selectionMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                text = "取消",
                                onClick = exitSelectionMode
                            )
                            Button(
                                onClick = { selectedGroups = allGroupKeys },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                insideMargin = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color(0xFF8A919F).copy(alpha = 0.15f),
                                    contentColor = Color(0xFF8A919F)
                                )
                            ) {
                                Text("全选", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                            Button(
                                onClick = { selectedGroups = allGroupKeys - selectedGroups },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                insideMargin = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color(0xFF8A919F).copy(alpha = 0.15f),
                                    contentColor = Color(0xFF8A919F)
                                )
                            ) {
                                Text("反选", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    selectedGroups.forEach { (pkg, label) -> onDeleteAppLogs(pkg, label) }
                                    exitSelectionMode()
                                },
                                enabled = selectedGroups.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                insideMargin = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color(0xFFFF3B30).copy(alpha = 0.12f),
                                    contentColor = Color(0xFFFF3B30)
                                )
                            ) {
                                Text("删除所选", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }

                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无拦截记录，四层防护正在静默守护中", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(grouped, key = { it.first }) { (appLabel, appLogs) ->
                                val groupKey = (appLogs.firstOrNull()?.packageName ?: "") to appLabel
                                AppLogGroup(
                                    appLabel = appLabel,
                                    logs = appLogs,
                                    dateFormat = dateFormat,
                                    inSelectionMode = selectionMode,
                                    isSelected = groupKey in selectedGroups,
                                    onToggleSelect = { toggleGroupSelection(groupKey) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    if (topApps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("尚未统计到偷跑上报数据", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        val maxCount = topApps.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(topApps, key = { it.packageName }) { appItem ->
                                TopAppItemCard(
                                    label = appItem.appLabel.ifBlank { appItem.packageName },
                                    packageName = appItem.packageName,
                                    count = appItem.count,
                                    progress = appItem.count.toFloat() / maxCount
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 按 App 折叠的拦截日志卡片（支持长按进入多选删除模式） */
@Composable
private fun AppLogGroup(
    appLabel: String,
    logs: List<InterceptLogEntity>,
    dateFormat: SimpleDateFormat,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit
) {
    var expanded by rememberSaveable(appLabel) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")
    val displayLogs = remember(logs, expanded) {
        if (expanded) logs.take(MAX_LOGS_PER_GROUP) else logs.take(1)
    }
    val totalCount = logs.size

    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 分组头部：选中模式下点击=切换选中、长按=进入多选并选中；否则点击=展开/收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            if (inSelectionMode) onToggleSelect() else expanded = !expanded
                        },
                        onLongClick = onToggleSelect
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inSelectionMode) {
                    // 选中指示器：未选中=描边圆圈，选中=实心圆 + 白色对勾
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFF0A59F7) else Color.Transparent)
                            .border(
                                width = 2.dp,
                                color = if (isSelected) Color.Transparent else Color(0xFFB0B7C3),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "已选中",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!expanded && logs.isNotEmpty()) {
                        Text(
                            text = "最近: ${logs.first().target} · ${dateFormat.format(Date(logs.first().timestamp))}",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 拦截次数徽章
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFF3B30).copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "$totalCount 次",
                        fontSize = 12.sp,
                        color = Color(0xFFFF3B30),
                        fontWeight = FontWeight.Bold
                    )
                }
                // 展开箭头（选中模式下隐藏，展开行为随之禁用）
                if (!inSelectionMode) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = Color.Gray,
                        modifier = Modifier
                            .rotate(arrowRotation)
                            .size(20.dp)
                    )
                }
            }

            // 展开后的记录列表
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    displayLogs.forEachIndexed { index, log ->
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color.LightGray.copy(alpha = 0.2f))
                            )
                        }
                        LogEntryRow(log = log, timeStr = dateFormat.format(Date(log.timestamp)))
                    }
                    if (totalCount > MAX_LOGS_PER_GROUP) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "仅显示最近 $MAX_LOGS_PER_GROUP 条，共 $totalCount 条",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(log: InterceptLogEntity, timeStr: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFFF3B30).copy(alpha = 0.1f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = log.layerType,
                color = Color(0xFFFF3B30),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = log.target,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = timeStr,
            fontSize = 11.sp,
            color = Color.LightGray
        )
    }
}

@Composable
private fun TopAppItemCard(
    label: String,
    packageName: String,
    count: Int,
    progress: Float
) {
    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = packageName, fontSize = 11.sp, color = Color.Gray)
                }
                Text(
                    text = "$count 次",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0A59F7)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }
    }
}
