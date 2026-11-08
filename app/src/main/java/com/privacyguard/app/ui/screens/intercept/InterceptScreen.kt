package com.privacyguard.app.ui.screens.intercept

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun InterceptScreen(
    logs: List<InterceptLogEntity>,
    topApps: List<AppInterceptCount>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf("实时拦截流", "Top 偷跑排行")
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "拦截监控中心",
                actions = {
                    IconButton(onClick = onClearLogs) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "清空记录"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                tabs = tabTitles,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(logs, key = { it.id }) { log ->
                                InterceptLogItemCard(log = log, timeStr = dateFormat.format(Date(log.timestamp)))
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

@Composable
private fun InterceptLogItemCard(
    log: InterceptLogEntity,
    timeStr: String
) {
    HyperCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF3B30).copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = log.layerType,
                    color = Color(0xFFFF3B30),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.target,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${log.appLabel.ifBlank { log.packageName.ifBlank { "系统底层" } }} · ${log.protocol} :${log.port}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Text(
                text = timeStr,
                fontSize = 11.sp,
                color = Color.LightGray
            )
        }
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
