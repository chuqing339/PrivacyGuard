package com.privacyguard.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 拦截记录实体
 * 记录通过 Hosts、iptables、ip6tables 或 DNS 兜底捕获的偷跑事件
 */
@Entity(
    tableName = "intercept_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["target"]),
        Index(value = ["packageName"])
    ]
)
data class InterceptLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val target: String,             // 域名或目标 IP
    val layerType: String,          // HOSTS, IPV4, IPV6, DNS_GUARD
    val uid: Int = -1,              // Linux UID
    val packageName: String = "",   // 应用包名（根据 UID 映射）
    val appLabel: String = "",      // 应用中文名
    val segment: String = "",       // 命中的规则段落：telemetry, antifraud, miuiad 等
    val protocol: String = "TCP",   // TCP, UDP, DoT
    val port: Int = 0,
    val action: String = "REJECT"   // REJECT, DROP, NXDOMAIN
)
