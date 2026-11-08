package com.privacyguard.app.data.repository

import com.privacyguard.app.core.privacy.XiaomiPrivacyManager
import com.privacyguard.app.data.local.dao.PrivacyPkgDao
import com.privacyguard.app.data.local.entity.PrivacyPkgEntity
import com.privacyguard.app.data.model.PkgKind
import com.privacyguard.app.data.model.PkgState
import com.privacyguard.app.data.model.PrivacyScanItem
import com.privacyguard.app.data.model.ResidueCleanResult
import com.privacyguard.app.data.model.RunningProcessItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class PrivacyRepository(
    private val privacyPkgDao: PrivacyPkgDao
) {

    private val _items = MutableStateFlow<List<PrivacyScanItem>>(emptyList())
    val items: StateFlow<List<PrivacyScanItem>> = _items.asStateFlow()

    private val _runningProcs = MutableStateFlow<List<RunningProcessItem>>(emptyList())
    val runningProcs: StateFlow<List<RunningProcessItem>> = _runningProcs.asStateFlow()

    // 体检得分（0-100）作为独立流暴露，供 App 层统计收集器合并进仪表盘
    private val _healthScore = MutableStateFlow(100)
    val healthScore: StateFlow<Int> = _healthScore.asStateFlow()

    /**
     * 执行全量体检
     */
    suspend fun performScan(): Int = withContext(Dispatchers.IO) {
        val scanResults = XiaomiPrivacyManager.scanPrivacyPackages()
        _items.value = scanResults

        val procs = XiaomiPrivacyManager.scanRunningProcs()
        _runningProcs.value = procs

        // 保存至 Room
        val entities = scanResults.map { item ->
            PrivacyPkgEntity(
                packageName = item.packageName,
                label = item.label,
                kind = item.kind.name,
                state = item.state.name,
                description = item.description,
                warningText = item.warningText,
                currentPid = item.pids.joinToString(",")
            )
        }
        privacyPkgDao.insertOrUpdateAll(entities)

        val score = calculateHealthScore(scanResults)
        _healthScore.value = score
        return@withContext score
    }

    /**
     * 切换指定包名的状态
     */
    suspend fun togglePackage(packageName: String, targetState: Int): Boolean = withContext(Dispatchers.IO) {
        val success = XiaomiPrivacyManager.setPackageState(packageName, targetState)
        if (success) {
            performScan()
        }
        success
    }

    /**
     * 一键处理所有安全可冻结项 (SAFE 类如 小米统计、MSA、SystemHelper)
     */
    suspend fun oneClickOptimize(): Boolean = withContext(Dispatchers.IO) {
        var allOk = true
        for (item in _items.value) {
            if (item.kind == PkgKind.SAFE && item.state == PkgState.ENABLED) {
                val ok = XiaomiPrivacyManager.setPackageState(item.packageName, 0) // 0 禁用
                if (!ok) allOk = false
            } else if (item.packageName == "com.mobiletools.systemhelper" && item.state == PkgState.ENABLED) {
                // 运营商定制探针直接禁用
                val ok = XiaomiPrivacyManager.setPackageState(item.packageName, 0)
                if (!ok) allOk = false
            }
        }
        performScan()
        allOk
    }

    /**
     * 清理手机管家本地反诈行为记录库（先备份后删除，可恢复）
     */
    suspend fun cleanFraudResidue(): ResidueCleanResult = withContext(Dispatchers.IO) {
        XiaomiPrivacyManager.cleanFraudDatabases()
    }

    /**
     * 计算隐私安全健康得分 (0 - 100)
     * 单包扣分按 14 个候选组件的基数标定（SAFE -8 / OPT -4 / RISK -2），
     * 保证"全开"与"全冻结"两个极端之间仍有足够区分度，而不是动辄归零。
     */
    private fun calculateHealthScore(items: List<PrivacyScanItem>): Int {
        var score = 100
        for (item in items) {
            if (item.state == PkgState.ENABLED) {
                when (item.kind) {
                    PkgKind.SAFE -> score -= 8 // 常驻采集（小米统计、MSA、内容捕获）
                    PkgKind.OPT -> score -= 4  // 探针/诊断/注册类
                    PkgKind.RISK -> score -= 2 // 不建议直接冻结的主组件
                }
            }
        }
        return score.coerceAtLeast(0)
    }
}
