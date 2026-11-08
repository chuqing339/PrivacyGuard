package com.privacyguard.app.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import com.privacyguard.app.data.local.dao.AppInterceptCount
import com.privacyguard.app.data.local.dao.DomainInterceptCount
import com.privacyguard.app.data.local.dao.InterceptLogDao
import com.privacyguard.app.data.local.entity.InterceptLogEntity
import com.privacyguard.app.data.preference.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Calendar

class InterceptLogRepository(
    private val context: Context,
    private val interceptLogDao: InterceptLogDao,
    private val settingsDataStore: SettingsDataStore
) {

    companion object {
        private const val TAG = "InterceptLogRepository"

        // 内核日志时间戳前缀：[ 12345.678]（开机起算的秒数，秒内毫秒可变长）
        private val KERNEL_TS_REGEX = Regex("^\\[\\s*(\\d+\\.\\d+)\\]")

        // 重启判定余量（秒）：内核时间戳在本开机周期内不可能超过当前开机时长，
        // 超出该余量即判定水位线来自上一次开机
        private const val REBOOT_DETECT_MARGIN_SEC = 60.0

        // 每次回读的内核日志行数上限
        private const val DMESG_TAIL_LINES = 200
    }

    val recentLogs: Flow<List<InterceptLogEntity>> = interceptLogDao.getRecentLogs(100)
    val totalInterceptCount: Flow<Int> = interceptLogDao.getTotalInterceptCount()
    val topApps: Flow<List<AppInterceptCount>> = interceptLogDao.getTopInterceptedApps(10)
    val topDomains: Flow<List<DomainInterceptCount>> = interceptLogDao.getTopInterceptedDomains(10)

    /**
     * 今日拦截数。
     * 以每日零点为统计基准，并在跨零点时自动翻转基准重新订阅 Room 流，
     * 修复原先「启动时算一次零点、之后永不翻新」导致跨天后口径冻结的问题。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getTodayInterceptCount(): Flow<Int> = flow {
        while (true) {
            val midnight = currentMidnight()
            emit(midnight)
            val nextMidnight = Calendar.getInstance().apply {
                timeInMillis = midnight
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            // 挂起至下一个零点（+1 秒余量确保已跨天）；时钟被手动调整时 negative delay 会立即返回并重算
            delay(nextMidnight - System.currentTimeMillis() + 1_000L)
        }
    }.flatMapLatest { sinceTime ->
        interceptLogDao.getTodayInterceptCount(sinceTime)
    }.conflate()

    private fun currentMidnight(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * 非清除式捕获内核日志中带有 [PG_BLK] / [PG_BLK6] 标记的拦截事件。
     *
     * 不再使用 `dmesg -c`：它会清空整个内核环形缓冲，属系统级副作用（影响其他排查工具与日志留存）。
     * 改为普通 `dmesg` 只读回读 + 持久化水位线去重：
     * - 水位线 = 已处理的最大内核时间戳（开机秒数），存于 DataStore，进程重启后不重复入库；
     * - 仅入库内核时间戳严格大于水位线的行，处理后推进水位线；同一内核时间戳的多行会在同一批内全部入库；
     * - 水位线超过当前开机时长（+ 余量）说明设备已重启（内核时间戳归零、环形缓冲为本次开机内容），自动重置水位线。
     */
    suspend fun captureKernelLogs(): Int = withContext(Dispatchers.IO) {
        val res = SuShellExecutor.execute(
            "dmesg 2>/dev/null | grep -E '\\[PG_BLK\\]|\\[PG_BLK6\\]' | tail -n $DMESG_TAIL_LINES"
        )
        if (!res.isSuccess || res.stdout.isEmpty()) return@withContext 0

        var watermark = settingsDataStore.dmesgWatermark()
        val nowElapsedSec = SystemClock.elapsedRealtime() / 1000.0
        if (watermark > nowElapsedSec + REBOOT_DETECT_MARGIN_SEC) {
            Log.i(TAG, "检测到设备重启，重置 dmesg 水位线 $watermark -> 0.0")
            watermark = 0.0
        }

        val pm = context.packageManager
        val nowWallClock = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val newLogs = mutableListOf<InterceptLogEntity>()
        var maxSeenTs = watermark

        for (line in res.stdout) {
            val kts = KERNEL_TS_REGEX.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (kts <= watermark) continue
            // 对所有新见过的行推进水位线（含无法解析出事件内容的行），避免下次轮询反复重扫
            if (kts > maxSeenTs) maxSeenTs = kts
            parseDmesgLine(line, pm, nowWallClock, nowElapsedMs)?.let { newLogs.add(it) }
        }

        if (newLogs.isNotEmpty()) {
            interceptLogDao.insertAll(newLogs)
            interceptLogDao.trimOldLogs(500)
        }
        if (maxSeenTs > watermark) {
            settingsDataStore.setDmesgWatermark(maxSeenTs)
        }
        newLogs.size
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        interceptLogDao.clearAll()
    }

    suspend fun deleteAppLogs(packageName: String, label: String) = withContext(Dispatchers.IO) {
        interceptLogDao.deleteByPackageOrLabel(packageName, label)
    }

    private fun parseDmesgLine(
        line: String,
        pm: PackageManager,
        nowWallClock: Long,
        nowElapsedMs: Long
    ): InterceptLogEntity? {
        try {
            // [PG_BLK] IN=... OUT=... SRC=192.168.1.5 DST=120.92.113.111 ... PROTO=TCP SPT=... DPT=443 UID=10234
            val dstRegex = Regex("DST=([0-9a-fA-F.:]+)")
            val protoRegex = Regex("PROTO=([A-Z]+)")
            val dptRegex = Regex("DPT=([0-9]+)")
            val uidRegex = Regex("UID=([0-9]+)")

            val dst = dstRegex.find(line)?.groupValues?.get(1) ?: return null
            val proto = protoRegex.find(line)?.groupValues?.get(1) ?: "TCP"
            val dpt = dptRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val uid = uidRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1

            // 内核时间戳为开机起算的单调秒数，按「事件发生于 now - 已流逝时长」近似映射为墙钟时间
            val kts = KERNEL_TS_REGEX.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            val timestamp = if (kts != null && kts >= 0) {
                nowWallClock - ((nowElapsedMs / 1000.0 - kts) * 1000).toLong().coerceAtLeast(0L)
            } else {
                nowWallClock
            }

            val (pkg, label) = resolveAppByUid(pm, uid)
            val isV6 = line.contains("[PG_BLK6]")

            return InterceptLogEntity(
                target = dst,
                layerType = if (isV6) "IPV6" else "IPV4",
                uid = uid,
                packageName = pkg,
                appLabel = label,
                protocol = proto,
                port = dpt,
                timestamp = timestamp
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun resolveAppByUid(pm: PackageManager, uid: Int): Pair<String, String> {
        if (uid <= 0) return "" to "系统底层/内核"
        if (uid == 1000) return "android.system" to "系统核心服务"
        return try {
            val pkgs = pm.getPackagesForUid(uid)
            val pkg = pkgs?.firstOrNull() ?: "UID: $uid"
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            pkg to label
        } catch (_: Exception) {
            "UID: $uid" to "应用 (UID $uid)"
        }
    }
}
