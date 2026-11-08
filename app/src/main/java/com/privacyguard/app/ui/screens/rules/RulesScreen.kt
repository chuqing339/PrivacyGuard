package com.privacyguard.app.ui.screens.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.widget.Toast
import com.privacyguard.app.data.local.entity.RuleEntity
import com.privacyguard.app.data.local.entity.RuleSegmentEntity
import com.privacyguard.app.data.local.entity.WhiteListEntity
import com.privacyguard.app.ui.components.HyperCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf("规则分段", "自定义拦截", "关键白名单")
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
            TabRow(
                tabs = tabTitles,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

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
                    if (whitelist.isEmpty()) {
                        EmptyListHint("暂无白名单域名")
                    } else {
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
    }

    if (showAddDialog) {
        AddDomainDialog(
            title = if (selectedTab == 1) "添加拦截域名" else "添加放行白名单",
            onDismiss = { showAddDialog = false },
            onConfirm = { domain, comment ->
                // 添加前基于现有列表数据查重，避免重复添加产生无效规则
                val duplicated = if (selectedTab == 1) {
                    customRules.any { it.domain.equals(domain, ignoreCase = true) }
                } else {
                    whitelist.any { it.domain.equals(domain, ignoreCase = true) }
                }
                if (duplicated) {
                    Toast.makeText(context, "该域名已存在", Toast.LENGTH_SHORT).show()
                } else {
                    if (selectedTab == 1) {
                        onAddCustomRule(domain, comment)
                    } else {
                        onAddWhitelist(domain, comment)
                    }
                    showAddDialog = false
                }
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
                onCheckedChange = onCheckedChange
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

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp)
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            TextField(
                value = domainText,
                onValueChange = { domainText = it },
                label = "域名 (如 tracking.miui.com)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = commentText,
                onValueChange = { commentText = it },
                label = "备注说明 (选填)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))

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
                    onClick = {
                        if (domainText.isNotBlank()) {
                            onConfirm(domainText.trim(), commentText.trim())
                        }
                    },
                    enabled = domainText.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("确定添加")
                }
            }
        }
    }
}
