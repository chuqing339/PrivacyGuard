package com.privacyguard.app.data.repository

import com.privacyguard.app.core.privacy.XiaomiPrivacyManager
import com.privacyguard.app.data.local.dao.PrivacyPkgDao
import com.privacyguard.app.data.local.entity.PrivacyPkgEntity
import com.privacyguard.app.data.model.PkgKind
import com.privacyguard.app.data.model.PkgState
import com.privacyguard.app.data.model.PrivacyScanItem
import com.privacyguard.app.data.model.RunningProcessItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    val handledCount: Flow<Int> = privacyPkgDao.getHandledCount()

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

        calculateHealthScore(scanResults)
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
     * 计算隐私安全健康得分 (0 - 100)
     */
    private fun calculateHealthScore(items: List<PrivacyScanItem>): Int {
        var score = 100
        for (item in items) {
            if (item.state == PkgState.ENABLED) {
                when (item.kind) {
                    PkgKind.SAFE -> score -= 25 // 严重扣分（小米统计与MSA常驻采集）
                    PkgKind.OPT -> score -= 15  // 运营商探针/守护进程
                    PkgKind.RISK -> score -= 5
                }
            }
        }
        return score.coerceAtLeast(0)
    }
}
