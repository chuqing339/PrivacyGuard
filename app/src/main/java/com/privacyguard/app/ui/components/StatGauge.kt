package com.privacyguard.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatGauge(
    totalIntercept: Int,
    todayIntercept: Int,
    activeRules: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatItemCard(
            title = "今日拦截",
            value = todayIntercept.toString(),
            color = Color(0xFF0A59F7),
            modifier = Modifier.weight(1f)
        )
        StatItemCard(
            title = "累计阻断",
            value = totalIntercept.toString(),
            color = Color(0xFF00C070),
            modifier = Modifier.weight(1f)
        )
        StatItemCard(
            title = "生效域名",
            value = activeRules.toString(),
            color = Color(0xFFFF8800),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatItemCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    HyperCard(
        modifier = modifier,
        cornerRadius = 18.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = color,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
