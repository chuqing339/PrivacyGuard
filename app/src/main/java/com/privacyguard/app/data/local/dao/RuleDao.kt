package com.privacyguard.app.data.local.dao

import androidx.room.*
import com.privacyguard.app.data.local.entity.RuleEntity
import com.privacyguard.app.data.local.entity.RuleSegmentEntity
import com.privacyguard.app.data.local.entity.WhiteListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    // --- 拦截规则 ---
    @Query("SELECT * FROM block_rules ORDER BY segment, domain")
    fun getAllRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM block_rules WHERE segment = :segment")
    fun getRulesBySegment(segment: String): Flow<List<RuleEntity>>

    @Query("""
        SELECT r.* FROM block_rules r
        INNER JOIN rule_segments s ON r.segment = s.segmentKey
        WHERE s.isEnabled = 1 AND r.isEnabled = 1
    """)
    suspend fun getActiveRules(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRules(rules: List<RuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RuleEntity): Long

    @Delete
    suspend fun deleteRule(rule: RuleEntity)

    @Query("DELETE FROM block_rules WHERE isCustom = 1 AND domain = :domain")
    suspend fun deleteCustomDomain(domain: String)

    @Query("DELETE FROM block_rules WHERE isCustom = 0")
    suspend fun clearBuiltInRules()

    @Query("SELECT COUNT(*) FROM block_rules")
    fun getRuleCount(): Flow<Int>

    // --- 分段控制 ---
    @Query("SELECT * FROM rule_segments")
    fun getAllSegments(): Flow<List<RuleSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<RuleSegmentEntity>)

    @Query("UPDATE rule_segments SET isEnabled = :enabled WHERE segmentKey = :segmentKey")
    suspend fun setSegmentEnabled(segmentKey: String, enabled: Boolean)

    @Query("SELECT * FROM rule_segments WHERE segmentKey = :segmentKey LIMIT 1")
    suspend fun getSegment(segmentKey: String): RuleSegmentEntity?

    // --- 白名单规则 ---
    @Query("SELECT * FROM whitelist_rules ORDER BY domain")
    fun getAllWhitelist(): Flow<List<WhiteListEntity>>

    @Query("SELECT domain FROM whitelist_rules")
    suspend fun getWhitelistDomains(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWhitelist(items: List<WhiteListEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelistItem(item: WhiteListEntity): Long

    @Delete
    suspend fun deleteWhitelistItem(item: WhiteListEntity)

    @Query("DELETE FROM whitelist_rules WHERE domain = :domain")
    suspend fun deleteWhitelistByDomain(domain: String)
}
