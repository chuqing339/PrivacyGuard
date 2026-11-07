package com.privacyguard.app.data.local.dao

import androidx.room.*
import com.privacyguard.app.data.local.entity.PrivacyPkgEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrivacyPkgDao {

    @Query("SELECT * FROM privacy_packages")
    fun getAllPrivacyPkgs(): Flow<List<PrivacyPkgEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(pkgs: List<PrivacyPkgEntity>)

    @Query("UPDATE privacy_packages SET state = :state, currentPid = :pid WHERE packageName = :pkg")
    suspend fun updateState(pkg: String, state: String, pid: String)

    @Query("SELECT COUNT(*) FROM privacy_packages WHERE state = 'DISABLED' OR state = 'RESTRICTED'")
    fun getHandledCount(): Flow<Int>
}
