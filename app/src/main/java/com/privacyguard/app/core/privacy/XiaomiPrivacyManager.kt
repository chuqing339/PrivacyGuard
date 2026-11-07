package com.privacyguard.app.core.privacy

import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import com.privacyguard.app.data.model.PkgKind
import com.privacyguard.app.data.model.PkgState
import com.privacyguard.app.data.model.PrivacyScanItem
import com.privacyguard.app.data.model.RunningProcessItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object XiaomiPrivacyManager {

    private const val TAG = "XiaomiPrivacyManager"

    // 针对小米重点隐私监控服务的预设元数据
    private val TARGET_PKGS = listOf(
        Triple("com.mobiletools.systemhelper", "SystemHelper（运营商定制遥测探针）", PkgKind.OPT),
        Triple("com.miui.analytics", "小米统计服务（静默搜集用户行为与App时长）", PkgKind.SAFE),
        Triple("com.miui.msa", "MSA 广告标识服务（个性化广告画像与追踪）", PkgKind.SAFE),
        Triple("com.miui.msa.global", "MSA 全球广告服务", PkgKind.SAFE),
        Triple("com.xiaomi.joyose", "Joyose（云控策略下发与性能遥测）", PkgKind.OPT),
        Triple("com.miui.bugreport", "小米错误日志上报（全量崩溃现场回传）", PkgKind.OPT),
        Triple("com.miui.daemon", "系统守护日志回传", PkgKind.OPT),
        Triple("com.miui.systemAdSolution", "智能服务（开屏广告宿主）", PkgKind.RISK)
    )

    private val RESTRICTED_APPOPS = listOf(
        "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND",
        "WAKE_LOCK",
        "START_FOREGROUND",
        "FINE_LOCATION",
        "COARSE_LOCATION",
        "READ_PHONE_STATE",
        "GET_USAGE_STATS"
    )

    /**
     * 全面扫描系统中小米隐私宿主服务的状态
     */
    suspend fun scanPrivacyPackages(): List<PrivacyScanItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<PrivacyScanItem>()

        // 一次性获取已禁用包列表和已安装包列表
        val disabledOut = SuShellExecutor.execute("pm list packages -d --user 0 2>/dev/null").outputText
        val allPkgsOut = SuShellExecutor.execute("pm list packages --user 0 2>/dev/null").outputText

        for ((pkg, label, kind) in TARGET_PKGS) {
            val isInstalled = allPkgsOut.contains("package:$pkg")
            if (!isInstalled) {
                result.add(
                    PrivacyScanItem(
                        packageName = pkg,
                        label = label,
                        kind = kind,
                        state = PkgState.ABSENT,
                        description = "该包在当前系统中未预装或已被精简。"
                    )
                )
                continue
            }

            val isDisabled = disabledOut.contains("package:$pkg")
            val pids = getPackagePids(pkg)

            // 检查是否有 appops 限制标记
            val isRestricted = SuShellExecutor.execute("test -f /data/adb/privacy_guard/.restricted_$pkg && echo 1").outputText.contains("1")

            val state = when {
                isDisabled -> PkgState.DISABLED
                isRestricted -> PkgState.RESTRICTED
                else -> PkgState.ENABLED
            }

            val warning = when (pkg) {
                "com.miui.systemAdSolution" -> "注意：部分系统自带App开屏会等待该组件返回。建议通过开启本软件的【MIUI广告与追踪】域名阻断，即可秒跳开屏且不卡顿！"
                "com.mobiletools.systemhelper" -> "运营商静默探针，无界面无前台功能，可放心深度冻结。"
                "com.xiaomi.joyose" -> "冻结可解除部分温控限制，但可能影响某些官方游戏插帧功能。"
                else -> ""
            }

            val desc = when (pkg) {
                "com.miui.analytics" -> "全天候在后台静默收集用户打开App的时间、频率与屏幕使用，建议立即冻结。"
                "com.miui.msa" -> "负责下发广告及生成不可变更的广告追踪标识，建议禁用或限制。"
                else -> "系统后台驻留服务。"
            }

            result.add(
                PrivacyScanItem(
                    packageName = pkg,
                    label = label,
                    kind = kind,
                    state = state,
                    pids = pids,
                    warningText = warning,
                    description = desc
                )
            )
        }
        result
    }

    /**
     * 切换隐私包状态
     * @param targetState 0: 彻底禁用 (pm disable-user), 1: 恢复启用 (pm enable), 2: 限制模式 (appops)
     */
    suspend fun setPackageState(pkg: String, targetState: Int): Boolean = withContext(Dispatchers.IO) {
        val cmds = mutableListOf<String>()
        cmds.add("mkdir -p /data/adb/privacy_guard")

        when (targetState) {
            0 -> {
                // 彻底禁用
                cmds.add("pm disable-user --user 0 $pkg 2>/dev/null || pm disable $pkg 2>/dev/null")
                cmds.add("am force-stop $pkg 2>/dev/null")
                cmds.add("rm -f /data/adb/privacy_guard/.restricted_$pkg 2>/dev/null")
            }
            1 -> {
                // 恢复启用
                cmds.add("pm enable --user 0 $pkg 2>/dev/null || pm enable $pkg 2>/dev/null")
                for (op in RESTRICTED_APPOPS) {
                    cmds.add("cmd appops set $pkg $op allow 2>/dev/null")
                }
                cmds.add("rm -f /data/adb/privacy_guard/.restricted_$pkg 2>/dev/null")
            }
            2 -> {
                // 限制模式（保留安装，剥夺权限并冻结后台）
                cmds.add("pm enable --user 0 $pkg 2>/dev/null || pm enable $pkg 2>/dev/null")
                for (op in RESTRICTED_APPOPS) {
                    cmds.add("cmd appops set $pkg $op ignore 2>/dev/null")
                }
                cmds.add("am force-stop $pkg 2>/dev/null")
                cmds.add("touch /data/adb/privacy_guard/.restricted_$pkg")
            }
        }

        val res = SuShellExecutor.executeLines(cmds)
        res.isSuccess
    }

    /**
     * 扫描系统中正在运行的隐私与上报相关进程
     */
    suspend fun scanRunningProcs(): List<RunningProcessItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<RunningProcessItem>()
        val targets = listOf(
            "securitycenter" to "安全中心数据引擎",
            "guardprovider" to "病毒扫描与遥测回传",
            "systemhelper" to "SystemHelper 探针",
            "systemAdSolution" to "智能服务广告引擎",
            "analytics" to "小米分析统计",
            "msa" to "MSA 广告服务"
        )

        for ((key, label) in targets) {
            val pids = SuShellExecutor.execute("pidof $key 2>/dev/null").outputText.trim()
            if (pids.isNotEmpty()) {
                items.add(
                    RunningProcessItem(
                        name = key,
                        label = label,
                        pid = pids,
                        isSuspicious = true
                    )
                )
            }
        }
        items
    }

    private suspend fun getPackagePids(pkg: String): List<String> {
        val out = SuShellExecutor.execute("pidof $pkg 2>/dev/null").outputText.trim()
        return if (out.isNotEmpty()) out.split("\\s+".toRegex()) else emptyList()
    }
}
