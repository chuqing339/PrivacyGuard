package com.privacyguard.app.ui.screens.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.data.local.entity.RuleEntity
import com.privacyguard.app.data.local.entity.RuleSegmentEntity
import com.privacyguard.app.data.local.entity.WhiteListEntity
import com.privacyguard.app.ui.components.HyperCard
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun RulesScreen(
    segments: List<RuleSegmentEntity>,
    customRules: List<RuleEntity>,
    whitelist: List<WhiteListEntity>,
    onToggleSegment: (String, Boolean) -> Unit,
    onAddCustomRule: (String, String) -> Unit,
    onDeleteCustomRule: (String) -> Unit,
    onAddWhitelist: (String, String) -> Unit,
    onDeleteWhitelist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("规则分段", "自定义拦截", "关键白名单")
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "规则与块表",
                actions = {
                    if (selectedTab != 0) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                        }
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
            // Tab 切换
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color(0xFF0A59F7),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 14.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (selectedTab) {
                0 -> {
                    // 分段列表
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(segments, key = { it.segmentKey }) { seg ->
                            SegmentCard(
                                segment = seg,
                                onCheckedChange = { isChecked ->
                                    onToggleSegment(seg.segmentKey, isChecked)
                                }
                            )
                        }
                    }
                }
                1 -> {
                    // 自定义域名
                    if (customRules.isEmpty()) {
                        EmptyListHint("暂无自定义规则，点击右上角加号添加")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(customRules, key = { it.domain }) { rule ->
                                RuleItemRow(
                                    domain = rule.domain,
                                    comment = rule.comment,
                                    onDelete = { onDeleteCustomRule(rule.domain) }
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // 白名单列表
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(whitelist, key = { it.domain }) { wl ->
                            RuleItemRow(
                                domain = wl.domain,
                                comment = if (wl.isBuiltIn) "内置保护" else wl.comment,
                                canDelete = !wl.isBuiltIn,
                                onDelete = { onDeleteWhitelist(wl.domain) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddDomainDialog(
            title = if (selectedTab == 1) "添加拦截域名" else "添加放行白名单",
            onDismiss = { showAddDialog = false },
            onConfirm = { domain, comment ->
                if (selectedTab == 1) {
                    onAddCustomRule(domain, comment)
                } else {
                    onAddWhitelist(domain, comment)
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun SegmentCard(
    segment: RuleSegmentEntity,
    onCheckedChange: (Boolean) -> Unit
) {
    HyperCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = segment.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = segment.description,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    lineHeight = 16.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = segment.isEnabled,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF0A59F7)
                )
            )
        }
    }
}

@Composable
private fun RuleItemRow(
    domain: String,
    comment: String,
    canDelete: Boolean = true,
    onDelete: () -> Unit
) {
    HyperCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = domain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (comment.isNotBlank()) {
                    Text(text = comment, fontSize = 11.sp, color = Color.Gray)
                }
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = Color(0xFFFF3B30)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyListHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
private fun AddDomainDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var domainText by remember { mutableStateOf("") }
    var commentText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = domainText,
                    onValueChange = { domainText = it },
                    label = { Text("域名 (如 tracking.miui.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text("备注说明 (选填)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (domainText.isNotBlank()) {
                        onConfirm(domainText.trim(), commentText.trim())
                    }
                },
                enabled = domainText.isNotBlank()
            ) {
                Text("确定添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
