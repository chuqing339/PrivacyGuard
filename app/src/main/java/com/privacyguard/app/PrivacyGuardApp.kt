package com.privacyguard.app

import android.app.Application
import android.util.Log
import com.privacyguard.app.data.local.AppDatabase
import com.privacyguard.app.data.preference.SettingsDataStore
import com.privacyguard.app.data.repository.InterceptLogRepository
import com.privacyguard.app.data.repository.PrivacyRepository
import com.privacyguard.app.data.repository.ProtectionRepository
import com.privacyguard.app.data.repository.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PrivacyGuardApp : Application() {

    companion object {
        private const val TAG = "PrivacyGuardApp"
    }

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

    // SupervisorJob：单个子协程异常不会拖垮整个应用级作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化数据库与配置存储
        database = AppDatabase.getInstance(this)
        settingsDataStore = SettingsDataStore(this)

        // 2. 初始化各个仓库
        ruleRepository = RuleRepository(this, database.ruleDao())
        protectionRepository = ProtectionRepository(this, settingsDataStore, ruleRepository)
        privacyRepository = PrivacyRepository(database.privacyPkgDao())
        interceptLogRepository = InterceptLogRepository(this, database.interceptLogDao(), settingsDataStore)

        // 3. 异步预热：载入内置规则、环境检查、隐私体检，各自独立 launch + try/catch，
        //    任一项失败只记日志，不阻塞其他初始化
        applicationScope.launch {
            try {
                ruleRepository.initDefaultRulesIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "初始化内置规则失败", e)
            }
        }
        applicationScope.launch {
            try {
                protectionRepository.checkEnvironment()
            } catch (e: Exception) {
                Log.e(TAG, "Root 环境自检失败", e)
            }
        }
        applicationScope.launch {
            try {
                privacyRepository.performScan()
            } catch (e: Exception) {
                Log.e(TAG, "隐私体检初始化失败", e)
            }
        }

        // 4. 统计单点收集：累计拦截 / 今日拦截 / 体检分（来源 PrivacyRepository.healthScore，
        //    由 performScan 更新）合并为一条流，一次性 updateStats。
        //    替代原先两个收集器各自「读 value→copy→写」互相覆盖丢字段的写法。
        applicationScope.launch {
            combine(
                interceptLogRepository.totalInterceptCount,
                interceptLogRepository.getTodayInterceptCount(),
                privacyRepository.healthScore
            ) { total, today, score -> Triple(total, today, score) }
                .collect { (total, today, score) ->
                    protectionRepository.updateStats(total = total, today = today, score = score)
                }
        }
    }
}
