package com.privacyguard.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.privacyguard.app.data.local.entity.InterceptLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * 聚合统计返回结构
 */
data class AppInterceptCount(
    val packageName: String,
    val appLabel: String,
    val count: Int
)

data class DomainInterceptCount(
    val target: String,
    val segment: String,
    val count: Int
)

@Dao
interface InterceptLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: InterceptLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<InterceptLogEntity>)

    @Query("SELECT * FROM intercept_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<InterceptLogEntity>>

    @Query("SELECT COUNT(*) FROM intercept_logs")
    fun getTotalInterceptCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM intercept_logs WHERE timestamp >= :sinceTime")
    fun getTodayInterceptCount(sinceTime: Long): Flow<Int>

    @Query("""
        SELECT packageName, appLabel, COUNT(*) as count 
        FROM intercept_logs 
        WHERE packageName != '' 
        GROUP BY packageName 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    fun getTopInterceptedApps(limit: Int = 10): Flow<List<AppInterceptCount>>

    @Query("""
        SELECT target, segment, COUNT(*) as count 
        FROM intercept_logs 
        GROUP BY target 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    fun getTopInterceptedDomains(limit: Int = 10): Flow<List<DomainInterceptCount>>

    @Query("DELETE FROM intercept_logs")
    suspend fun clearAll()

    @Query("DELETE FROM intercept_logs WHERE id NOT IN (SELECT id FROM intercept_logs ORDER BY timestamp DESC LIMIT :maxKeep)")
    suspend fun trimOldLogs(maxKeep: Int = 2000)
}
