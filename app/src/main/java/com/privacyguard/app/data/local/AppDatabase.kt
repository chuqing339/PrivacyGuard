package com.privacyguard.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.privacyguard.app.data.local.dao.InterceptLogDao
import com.privacyguard.app.data.local.dao.PrivacyPkgDao
import com.privacyguard.app.data.local.dao.RuleDao
import com.privacyguard.app.data.local.entity.InterceptLogEntity
import com.privacyguard.app.data.local.entity.PrivacyPkgEntity
import com.privacyguard.app.data.local.entity.RuleEntity
import com.privacyguard.app.data.local.entity.RuleSegmentEntity
import com.privacyguard.app.data.local.entity.WhiteListEntity

@Database(
    entities = [
        InterceptLogEntity::class,
        RuleEntity::class,
        RuleSegmentEntity::class,
        WhiteListEntity::class,
        PrivacyPkgEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun interceptLogDao(): InterceptLogDao
    abstract fun ruleDao(): RuleDao
    abstract fun privacyPkgDao(): PrivacyPkgDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "privacy_guard.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
