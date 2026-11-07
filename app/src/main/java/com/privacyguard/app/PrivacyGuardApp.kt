package com.privacyguard.app

import android.app.Application
import com.privacyguard.app.data.local.AppDatabase
import com.privacyguard.app.data.preference.SettingsDataStore
import com.privacyguard.app.data.repository.InterceptLogRepository
import com.privacyguard.app.data.repository.PrivacyRepository
import com.privacyguard.app.data.repository.ProtectionRepository
import com.privacyguard.app.data.repository.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PrivacyGuardApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var settingsDataStore: SettingsDataStore
        private set
    lateinit var ruleRepository: RuleRepository
        private set
    lateinit var protectionRepository: ProtectionRepository
        private set
    lateinit var privacyRepository: PrivacyRepository
        private set
    lateinit var interceptLogRepository: InterceptLogRepository
        private set

    private val applicationScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化数据库与配置存储
        database = AppDatabase.getInstance(this)
        settingsDataStore = SettingsDataStore(this)

        // 2. 初始化各个仓库
        ruleRepository = RuleRepository(this, database.ruleDao())
        protectionRepository = ProtectionRepository(this, settingsDataStore, ruleRepository)
        privacyRepository = PrivacyRepository(database.privacyPkgDao())
        interceptLogRepository = InterceptLogRepository(this, database.interceptLogDao())

        // 3. 异步预热：载入内置规则、环境检查
        applicationScope.launch {
            ruleRepository.initDefaultRulesIfNeeded()
            protectionRepository.checkEnvironment()
            privacyRepository.performScan()

            // 监听拦截日志与体检分更新到仪表盘
            launch {
                interceptLogRepository.totalInterceptCount.collect { total ->
                    protectionRepository.updateStats(
                        total = total,
                        today = protectionRepository.dashboardState.value.todayInterceptCount,
                        score = protectionRepository.dashboardState.value.privacyScore
                    )
                }
            }

            launch {
                interceptLogRepository.getTodayInterceptCount().collect { today ->
                    protectionRepository.updateStats(
                        total = protectionRepository.dashboardState.value.totalInterceptCount,
                        today = today,
                        score = protectionRepository.dashboardState.value.privacyScore
                    )
                }
            }
        }
    }
}
