package com.privacyguard.app.ui.screens.rules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.privacyguard.app.ui.components.SegmentedTabs
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

// 内置白名单四大分类
private val BUILTIN_CATEGORIES = listOf("谷歌", "小米", "GitHub", "LSPosed")

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
    val tabTitles = listOf("规则", "自定义", "白名单")
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "规则与块表")
        },
        // 添加入口仅存在于自定义/白名单两个 tab：HyperOS 风格圆形悬浮按钮，右下角
        floatingActionButton = {
            if (selectedTab != 0) {
                FloatingActionButton(
                    onClick = { showAddDialog = true }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加", tint = Color.White)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 标签栏：添加入口已改为右下角悬浮按钮
            SegmentedTabs(
                tabs = tabTitles,
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

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
                        EmptyListHint("暂无自定义规则，点击右下角加号添加")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // 底部预留悬浮按钮高度，避免最后一条被遮
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
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
                    // 白名单：内置按四大类（谷歌/小米/GitHub/LSPosed）折叠展示，自定义独立展示
                    WhitelistTab(
                        whitelist = whitelist,
                        onDeleteWhitelist = onDeleteWhitelist
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddDomainDialog(
            title = if (selectedTab == 1) "添加拦截域名" else "添加放行白名单",
            onDismiss = { showAddDialog = false },
            onConfirm = { domain, comment ->
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

/** 分类算法：精准归类至 谷歌/小米/GitHub/LSPosed */
private fun classifyWhitelistGroup(item: WhiteListEntity): String {
    val d = item.domain.lowercase()
    val c = item.comment.lowercase()
    return when {
        d.contains("google") || c.contains("谷歌") -> "谷歌"
        d.contains("xiaomi") || d.contains("miui") || c.contains("小米") -> "小米"
        d.contains("lsposed") || d.contains("ghproxy") || d.contains("gh-proxy") || d.contains("jsdelivr") || (c.contains("lsposed") && !d.contains("github")) -> "LSPosed"
        d.contains("github") || c.contains("github") -> "GitHub"
        else -> "其他"
    }
}

/** 白名单 Tab：内置条目按分组折叠卡片，自定义可删除列表 */
@Composable
private fun WhitelistTab(
    whitelist: List<WhiteListEntity>,
    onDeleteWhitelist: (String) -> Unit
) {
    if (whitelist.isEmpty()) {
        EmptyListHint("暂无白名单域名")
        return
    }

    // 分离内置与自定义
    val builtinItems = whitelist.filter { it.isBuiltIn }
    val customItems = whitelist.filter { !it.isBuiltIn }

    // 按四大预设分类归纳内置规则
    val categorizedMap = remember(builtinItems) {
        val map = BUILTIN_CATEGORIES.associateWith { mutableListOf<WhiteListEntity>() }.toMutableMap()
        val others = mutableListOf<WhiteListEntity>()

        for (item in builtinItems) {
            val cat = classifyWhitelistGroup(item)
            if (map.containsKey(cat)) {
                map[cat]?.add(item)
            } else {
                others.add(item)
            }
        }
        val list = BUILTIN_CATEGORIES.map { cat -> cat to (map[cat] ?: emptyList()) }.toMutableList()
        if (others.isNotEmpty()) {
            list.add("其他放行" to others)
        }
        list
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部预留悬浮按钮高度，避免最后一条被遮
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 内置白名单四大折叠分组
        items(categorizedMap, key = { "group_${it.first}" }) { (groupName, members) ->
            CollapsibleWhitelistGroup(groupName = groupName, members = members)
        }

        // 自定义条目（可删除）
        if (customItems.isNotEmpty()) {
            item(key = "header_custom") {
                Text(
                    text = "自定义放行 · ${customItems.size} 条",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF66707A),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(customItems, key = { it.domain }) { wl ->
                RuleItemRow(
                    domain = wl.domain,
                    comment = wl.comment,
                    showComment = true,
                    canDelete = true,
                    onDelete = { onDeleteWhitelist(wl.domain) }
                )
            }
        }
    }
}

/** 可折叠的白名单分组卡片，默认收纳，点击展开 */
@Composable
private fun CollapsibleWhitelistGroup(
    groupName: String,
    members: List<WhiteListEntity>
) {
    var expanded by rememberSaveable(groupName) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")

    HyperCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 分组头部：点击展开/收起（无水波纹遮罩，与全站卡片一致）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 分组徽标色块
                val (groupColor, iconDesc) = when (groupName) {
                    "谷歌" -> Color(0xFF4285F4) to "Google 服务白名单"
                    "小米" -> Color(0xFFFF6900) to "小米核心服务白名单"
                    "GitHub" -> Color(0xFF24292E) to "GitHub 规则白名单"
                    "LSPosed" -> Color(0xFF7B61FF) to "LSPosed 模块及镜像"
                    else -> Color(0xFF8A919F) to "系统放行白名单"
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(groupColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = groupName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = groupColor
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${members.size} 条规则",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A1A)
                    )
                    Text(
                        text = iconDesc,
                        fontSize = 11.sp,
                        color = Color.Gray
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

            // 展开后的域名列表
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (members.isEmpty()) {
                        Text(
                            text = "该分类下暂无规则",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        members.forEachIndexed { index, wl ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Color.LightGray.copy(alpha = 0.2f))
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = wl.domain,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (wl.comment.isNotBlank() && wl.comment != "内置保护") {
                                        Text(
                                            text = wl.comment,
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF00C070).copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "放行",
                                        fontSize = 10.sp,
                                        color = Color(0xFF00C070),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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
    showComment: Boolean = true,
    onDelete: () -> Unit
) {
    HyperCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = domain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (showComment && comment.isNotBlank()) {
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
