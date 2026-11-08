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
     * 初始化预置规则（保持每次启动调用：
     * insertSegments / insertRules 均为 IGNORE 策略，同主键已存在即跳过，幂等且不会重置用户已有的分段开关）
     */
    suspend fun initDefaultRulesIfNeeded() = withContext(Dispatchers.IO) {
        // 1. 初始化分段（IGNORE：已存在的分段原样保留，isEnabled 不被覆盖）
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
                // 跳过 [xxx] 段头（分组标记），避免其被当作伪域名写入白名单
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) continue
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

    /**
     * 添加自定义拦截规则
     * @return 是否写入成功；同域名已存在内置规则（isCustom=0）时拒绝，
     *         避免 insertRule 的 REPLACE 整行顶掉内置规则且删除自定义路径（DELETE WHERE isCustom=1）无法恢复
     */
    suspend fun addCustomRule(domain: String, comment: String = ""): Boolean {
        val trimmed = domain.trim()
        if (trimmed.isEmpty()) return false
        val existing = ruleDao.getRuleByDomain(trimmed)
        if (existing != null && !existing.isCustom) {
            // 内置规则优先：自定义规则不得顶掉内置规则
            return false
        }
        ruleDao.insertRule(
            RuleEntity(
                domain = trimmed,
                segment = SegmentType.CUSTOM.key,
                comment = comment,
                isCustom = true,
                isEnabled = true
            )
        )
        return true
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
