package com.privacyguard.app.data.repository

import android.content.Context
import com.privacyguard.app.data.local.dao.RuleDao
import com.privacyguard.app.data.local.entity.RuleEntity
import com.privacyguard.app.data.local.entity.RuleSegmentEntity
import com.privacyguard.app.data.local.entity.WhiteListEntity
import com.privacyguard.app.data.model.SegmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RuleRepository(
    private val context: Context,
    private val ruleDao: RuleDao
) {

    val allRules: Flow<List<RuleEntity>> = ruleDao.getAllRules()
    val allSegments: Flow<List<RuleSegmentEntity>> = ruleDao.getAllSegments()
    val allWhitelist: Flow<List<WhiteListEntity>> = ruleDao.getAllWhitelist()
    val totalRuleCount: Flow<Int> = ruleDao.getRuleCount()

    /**
     * 首次或重置时初始化预置规则
     */
    suspend fun initDefaultRulesIfNeeded() = withContext(Dispatchers.IO) {
        // 1. 初始化分段
        val segmentEntities = SegmentType.values().map { seg ->
            RuleSegmentEntity(
                segmentKey = seg.key,
                title = seg.title,
                description = seg.defaultDesc,
                isEnabled = true
            )
        }
        ruleDao.insertSegments(segmentEntities)

        // 2. 从 assets/blocklist.txt 导入默认规则
        try {
            val blocklistStream = context.assets.open("blocklist.txt")
            val lines = blocklistStream.bufferedReader().readLines()
            var currentSegment = "telemetry"
            val ruleList = mutableListOf<RuleEntity>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSegment = trimmed.substring(1, trimmed.length - 1).lowercase()
                    continue
                }

                ruleList.add(
                    RuleEntity(
                        domain = trimmed,
                        segment = currentSegment,
                        isCustom = false,
                        isEnabled = true
                    )
                )
            }
            ruleDao.insertRules(ruleList)
        } catch (_: Exception) {}

        // 3. 从 assets/whitelist.txt 导入关键域名白名单
        try {
            val wlStream = context.assets.open("whitelist.txt")
            val lines = wlStream.bufferedReader().readLines()
            val wlList = mutableListOf<WhiteListEntity>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                wlList.add(
                    WhiteListEntity(
                        domain = trimmed,
                        isBuiltIn = true,
                        comment = "系统核心/常用关键组件白名单"
                    )
                )
            }
            ruleDao.insertWhitelist(wlList)
        } catch (_: Exception) {}
    }

    suspend fun setSegmentEnabled(segmentKey: String, isEnabled: Boolean) {
        ruleDao.setSegmentEnabled(segmentKey, isEnabled)
    }

    suspend fun addCustomRule(domain: String, comment: String = "") {
        ruleDao.insertRule(
            RuleEntity(
                domain = domain.trim(),
                segment = SegmentType.CUSTOM.key,
                comment = comment,
                isCustom = true,
                isEnabled = true
            )
        )
    }

    suspend fun deleteCustomRule(domain: String) {
        ruleDao.deleteCustomDomain(domain)
    }

    suspend fun addWhitelist(domain: String, comment: String = "") {
        ruleDao.insertWhitelistItem(
            WhiteListEntity(
                domain = domain.trim(),
                comment = comment,
                isBuiltIn = false
            )
        )
    }

    suspend fun deleteWhitelist(domain: String) {
        ruleDao.deleteWhitelistByDomain(domain)
    }

    suspend fun getActiveDomains(): List<String> = withContext(Dispatchers.IO) {
        val rules = ruleDao.getActiveRules()
        rules.map { it.domain }
    }

    suspend fun getWhitelistDomains(): List<String> = withContext(Dispatchers.IO) {
        ruleDao.getWhitelistDomains()
    }
}
