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

        // 按行精确匹配，避免子串误判（如 com.miui.msa 与 com.miui.msa.global 互不误判）
        val installedLines = allPkgsOut.split('\n').map { it.trim() }
        val disabledLines = disabledOut.split('\n').map { it.trim() }

        for ((pkg, label, kind) in TARGET_PKGS) {
            val isInstalled = installedLines.any { it == "package:$pkg" }
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

            val isDisabled = disabledLines.any { it == "package:$pkg" }
            val pids = getPackagePids(pkg)

            // 检查是否有 appops 限制标记
            val isRestricted = SuShellExecutor.execute("test -f /data/adb/privacy_guard/.restricted_$pkg && echo 1").outputText.contains("1")

            val state = when {
                isDisabled -> PkgState.DISABLED
                isRestricted -> PkgState.RESTRICTED
                else -> PkgState.ENABLED
            }

            val warning = when (pkg) {
                "com.miui.systemAdSolution" -> "冻结后部分系统自带应用开屏可能出现短暂卡顿或等待。建议改用本软件的【MIUI广告与追踪】域名阻断，即可秒跳开屏且不卡顿！"
                "com.mobiletools.systemhelper" -> "运营商定制组件，涉及 OTA 升级与 SIM 卡远程配置下发通道，冻结后运营商侧远程配置功能将失效，可随时在此恢复。"
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
     * @return 关键命令（pm / appops）逐条以真实退出码校验，全部成功才返回 true；
     *         .restricted_ 标记文件跟随真实结果落盘，不因命令批量执行被吞掉失败
     */
    suspend fun setPackageState(pkg: String, targetState: Int): Boolean = withContext(Dispatchers.IO) {
        SuShellExecutor.execute("mkdir -p /data/adb/privacy_guard")

        when (targetState) {
            0 -> disablePackage(pkg)
            1 -> enablePackage(pkg)
            2 -> restrictPackage(pkg)
            else -> false
        }
    }

    /** 彻底禁用：pm disable-user 失败时回退 pm disable，以退出码为准 */
    private suspend fun disablePackage(pkg: String): Boolean {
        val pmRes = SuShellExecutor.execute("pm disable-user --user 0 $pkg 2>/dev/null || pm disable $pkg 2>/dev/null")
        if (!pmRes.isSuccess) {
            Log.e(TAG, "pm disable 失败: $pkg (exit=${pmRes.exitCode})")
            return false
        }
        // force-stop 仅为收尾清理，不影响成功判定
        SuShellExecutor.execute("am force-stop $pkg 2>/dev/null")
        SuShellExecutor.execute("rm -f /data/adb/privacy_guard/.restricted_$pkg")
        return true
    }

    /** 恢复启用：pm enable + 逐条恢复 appops，任一失败即整体失败且不清理标记文件 */
    private suspend fun enablePackage(pkg: String): Boolean {
        val pmRes = SuShellExecutor.execute("pm enable --user 0 $pkg 2>/dev/null || pm enable $pkg 2>/dev/null")
        if (!pmRes.isSuccess) {
            Log.e(TAG, "pm enable 失败: $pkg (exit=${pmRes.exitCode})")
            return false
        }
        for (op in RESTRICTED_APPOPS) {
            val opRes = SuShellExecutor.execute("cmd appops set $pkg $op allow")
            if (!opRes.isSuccess) {
                Log.e(TAG, "appops allow 失败: $pkg $op (exit=${opRes.exitCode})")
                return false
            }
        }
        SuShellExecutor.execute("rm -f /data/adb/privacy_guard/.restricted_$pkg")
        return true
    }

    /** 限制模式：pm enable + 逐条剥夺 appops + 标记文件仅在全部成功后落盘 */
    private suspend fun restrictPackage(pkg: String): Boolean {
        val pmRes = SuShellExecutor.execute("pm enable --user 0 $pkg 2>/dev/null || pm enable $pkg 2>/dev/null")
        if (!pmRes.isSuccess) {
            Log.e(TAG, "pm enable 失败: $pkg (exit=${pmRes.exitCode})")
            return false
        }
        for (op in RESTRICTED_APPOPS) {
            val opRes = SuShellExecutor.execute("cmd appops set $pkg $op ignore")
            if (!opRes.isSuccess) {
                Log.e(TAG, "appops ignore 失败: $pkg $op (exit=${opRes.exitCode})")
                return false
            }
        }
        SuShellExecutor.execute("am force-stop $pkg 2>/dev/null")
        SuShellExecutor.execute("touch /data/adb/privacy_guard/.restricted_$pkg")
        return true
    }

    /**
     * 扫描系统中正在运行的隐私与上报相关进程。
     * 使用完整包名查询：pidof 以进程名（即包名）匹配，短名（如 "msa"）可能误命中无关进程或漏配。
     */
    suspend fun scanRunningProcs(): List<RunningProcessItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<RunningProcessItem>()
        val targets = listOf(
            "com.miui.securitycenter" to "安全中心数据引擎",
            "com.miui.guardprovider" to "病毒扫描与遥测回传",
            "com.mobiletools.systemhelper" to "SystemHelper 探针",
            "com.miui.systemAdSolution" to "智能服务广告引擎",
            "com.miui.analytics" to "小米分析统计",
            "com.miui.msa" to "MSA 广告服务",
            "com.miui.msa.global" to "MSA 全球广告服务"
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
