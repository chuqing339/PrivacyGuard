package com.privacyguard.app.ui.screens.intercept

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
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
    onClearLogs: () -> Unit,
    onDeleteAppLogs: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf("实时拦截流", "Top 偷跑排行")
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

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
            // 标签栏 + 清空按钮同行，靠顶排布
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabRow(
                    tabs = tabTitles,
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClearLogs) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "清空记录",
                        tint = Color.Gray
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无拦截记录，四层防护正在静默守护中", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        // 按 app 分组（最多 MAX_GROUPS 组）
                        val grouped: List<Pair<String, List<InterceptLogEntity>>> = remember(logs) {
                            logs.groupBy { log ->
                                log.appLabel.ifBlank { log.packageName.ifBlank { "系统底层" } }
                            }
                                .entries
                                .sortedByDescending { it.value.first().timestamp }
                                .take(MAX_GROUPS)
                                .map { (k, v) -> k to v }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(grouped, key = { it.first }) { (appLabel, appLogs) ->
                                val firstLog = appLogs.firstOrNull()
                                AppLogGroup(
                                    appLabel = appLabel,
                                    logs = appLogs,
                                    dateFormat = dateFormat,
                                    onDeleteGroup = {
                                        onDeleteAppLogs(firstLog?.packageName ?: "", appLabel)
                                    }
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

/** 按 App 折叠的拦截日志卡片 */
@Composable
private fun AppLogGroup(
    appLabel: String,
    logs: List<InterceptLogEntity>,
    dateFormat: SimpleDateFormat,
    onDeleteGroup: () -> Unit
) {
    var expanded by rememberSaveable(appLabel) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")
    val displayLogs = remember(logs, expanded) {
        if (expanded) logs.take(MAX_LOGS_PER_GROUP) else logs.take(1)
    }
    val totalCount = logs.size

    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 分组头部：App 名 + 拦截总数，点击展开/收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Spacer(modifier = Modifier.width(4.dp))
                // 单组删除按钮
                IconButton(onClick = onDeleteGroup) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除本组件记录",
                        tint = Color.LightGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = Color.Gray,
                    modifier = Modifier
                        .rotate(arrowRotation)
                        .size(20.dp)
                )
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
