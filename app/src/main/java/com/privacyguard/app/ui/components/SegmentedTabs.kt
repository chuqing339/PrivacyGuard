package com.privacyguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * HyperOS 分段控件风格的单体标签栏：一个圆角容器 + 等宽分段，
 * 选中段浮起底色，替代 miuix TabRow 的多段分离式外观。
 * 颜色取自 MiuixTheme，明暗模式均自适应。
 */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(3.dp)) {
            tabs.forEachIndexed { index, title ->
                val selected = index == selectedTabIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MiuixTheme.colorScheme.surface else Color.Transparent)
                        .clickable { onTabSelected(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
