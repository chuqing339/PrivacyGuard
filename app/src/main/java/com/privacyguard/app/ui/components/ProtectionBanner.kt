package com.privacyguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacyguard.app.data.model.EngineStatus
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun ProtectionBanner(
    status: EngineStatus,
    rootFlavor: String,
    onToggleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = status == EngineStatus.RUNNING
    val isStarting = status == EngineStatus.STARTING
    val isNoRoot = status == EngineStatus.NO_ROOT

    val gradientColors = when {
        isRunning -> listOf(Color(0xFF0A59F7), Color(0xFF00B4D8))
        isStarting -> listOf(Color(0xFFFF8800), Color(0xFFFFB703))
        isNoRoot -> listOf(Color(0xFFD90429), Color(0xFFEF233C))
        else -> listOf(Color(0xFF6C757D), Color(0xFF495057))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(gradientColors))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 状态标签
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (rootFlavor.isNotEmpty()) "ROOT · $rootFlavor" else "小米专属反遥测",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 一键开关圆环大按钮
                IconButton(
                    onClick = onToggleClick,
                    enabled = !isStarting && !isNoRoot,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    if (isStarting) {
                        InfiniteProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color(0xFF0A59F7),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = "开关",
                            tint = if (isRunning) Color(0xFF0A59F7) else Color(0xFF6C757D),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 状态大标题
            Text(
                text = when {
                    isRunning -> "全机隐私防御保护中"
                    isStarting -> "规则正在编译与注入..."
                    isNoRoot -> "尚未取得 ROOT 权限"
                    else -> "隐私防护已暂停"
                },
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 副标题说明
            Text(
                text = when {
                    isRunning -> "已切断 MIUI 遥测回传、反诈特征上报与广告追踪"
                    isStarting -> "正在重构 hosts 映射与下发 iptables/ipset 硬件规则"
                    isNoRoot -> "请在 Magisk / KernelSU / APatch 管理器中为本软件授权"
                    else -> "点击右上方启动按钮，即可立即开启四层立体防护"
                },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
