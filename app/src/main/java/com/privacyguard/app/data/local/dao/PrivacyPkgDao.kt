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
}
