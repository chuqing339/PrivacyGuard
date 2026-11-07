package com.privacyguard.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 拦截域名规则实体
 */
@Entity(
    tableName = "block_rules",
    indices = [
        Index(value = ["domain"], unique = true),
        Index(value = ["segment"])
    ]
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val segment: String,        // telemetry, antifraud, riskgate, miuiad, doh, adsdk, custom
    val comment: String = "",   // 备注说明（例如：小米安全中心 多媒体/视频回传）
    val isCustom: Boolean = false,
    val isEnabled: Boolean = true
)

/**
 * 规则分段控制实体
 */
@Entity(tableName = "rule_segments")
data class RuleSegmentEntity(
    @PrimaryKey
    val segmentKey: String,     // telemetry, antifraud, riskgate, miuiad, doh, adsdk, custom
    val title: String,          // 标题（例如：小米遥测与回传）
    val description: String,    // 详细说明
    val isEnabled: Boolean = true,
    val ruleCount: Int = 0
)

/**
 * 关键域名白名单实体（保护 LSPosed、GitHub、小米账号关键服务防误伤）
 */
@Entity(
    tableName = "whitelist_rules",
    indices = [Index(value = ["domain"], unique = true)]
)
data class WhiteListEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val comment: String = "",
    val isBuiltIn: Boolean = false
)

/**
 * 小米系统级隐私服务状态记录
 */
@Entity(tableName = "privacy_packages")
data class PrivacyPkgEntity(
    @PrimaryKey
    val packageName: String,
    val label: String,
    val kind: String,           // SAFE (安全可停用), OPT (可选优化), RISK (需谨慎)
    val state: String,          // ENABLED, DISABLED, RESTRICTED, ABSENT
    val description: String = "",
    val warningText: String = "",
    val currentPid: String = ""
)
