package com.privacyguard.app.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.privacyguard.app.core.root.SuShellExecutor
import com.privacyguard.app.data.local.dao.AppInterceptCount
import com.privacyguard.app.data.local.dao.DomainInterceptCount
import com.privacyguard.app.data.local.dao.InterceptLogDao
import com.privacyguard.app.data.local.entity.InterceptLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar

class InterceptLogRepository(
    private val context: Context,
    private val interceptLogDao: InterceptLogDao
) {

    val recentLogs: Flow<List<InterceptLogEntity>> = interceptLogDao.getRecentLogs(100)
    val totalInterceptCount: Flow<Int> = interceptLogDao.getTotalInterceptCount()
    val topApps: Flow<List<AppInterceptCount>> = interceptLogDao.getTopInterceptedApps(10)
    val topDomains: Flow<List<DomainInterceptCount>> = interceptLogDao.getTopInterceptedDomains(10)

    fun getTodayInterceptCount(): Flow<Int> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return interceptLogDao.getTodayInterceptCount(calendar.timeInMillis)
    }

    /**
     * 捕获并解析内核日志中带有 [PG_BLK] 标记的拦截事件
     */
    suspend fun captureKernelLogs(): Int = withContext(Dispatchers.IO) {
        val res = SuShellExecutor.execute("dmesg -c 2>/dev/null | grep -E '\\[PG_BLK\\]|\\[PG_BLK6\\]' | tail -n 50")
        if (!res.isSuccess || res.stdout.isEmpty()) return@withContext 0

        val newLogs = mutableListOf<InterceptLogEntity>()
        val pm = context.packageManager

        for (line in res.stdout) {
            val entity = parseDmesgLine(line, pm)
            if (entity != null) {
                newLogs.add(entity)
            }
        }

        if (newLogs.isNotEmpty()) {
            interceptLogDao.insertAll(newLogs)
            interceptLogDao.trimOldLogs(2000)
        }
        newLogs.size
    }

    /**
     * 手动记录单次拦截
     */
    suspend fun recordIntercept(
        target: String,
        layerType: String,
        segment: String = "",
        uid: Int = -1
    ) = withContext(Dispatchers.IO) {
        val (pkg, label) = resolveAppByUid(context.packageManager, uid)
        val entity = InterceptLogEntity(
            target = target,
            layerType = layerType,
            segment = segment,
            uid = uid,
            packageName = pkg,
            appLabel = label,
            timestamp = System.currentTimeMillis()
        )
        interceptLogDao.insert(entity)
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        interceptLogDao.clearAll()
    }

    private fun parseDmesgLine(line: String, pm: PackageManager): InterceptLogEntity? {
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
                timestamp = System.currentTimeMillis()
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
