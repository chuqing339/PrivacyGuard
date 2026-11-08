package com.privacyguard.app.core.privacy

import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import com.privacyguard.app.data.model.PkgKind
import com.privacyguard.app.data.model.PkgState
import com.privacyguard.app.data.model.PrivacyScanItem
import com.privacyguard.app.data.model.ResidueCleanResult
import com.privacyguard.app.data.model.RunningProcessItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object XiaomiPrivacyManager {

    private const val TAG = "XiaomiPrivacyManager"

    // 针对小米重点隐私监控服务的预设元数据
    // 命名约定：label 只保留组件短名（不加括号说明），括号内的说明性文字一律并入
    // description（体检页卡片极简展示，点击详情弹窗查看完整介绍）
    // 覆盖清单与 Hyper 隐私卫士 0.7.8 逆向分析中的 8 个目标包对齐（另保留本项目独有的 joyose/bugreport/daemon）
    private val TARGET_PKGS = listOf(
        Triple("com.miui.securitycenter", "安全中心数据引擎", PkgKind.RISK),
        Triple("com.miui.guardprovider", "病毒扫描与遥测回传", PkgKind.OPT),
        Triple("com.mobiletools.systemhelper", "SystemHelper", PkgKind.OPT),
        Triple("com.miui.analytics", "小米统计服务", PkgKind.SAFE),
        Triple("com.miui.msa", "MSA 广告标识服务", PkgKind.SAFE),
        Triple("com.miui.msa.global", "MSA 全球广告服务", PkgKind.SAFE),
        Triple("com.miui.misightservice", "MiSight", PkgKind.OPT),
        Triple("com.xiaomi.aicr", "AICR", PkgKind.OPT),
        Triple("com.xiaomi.registration", "电信终端注册", PkgKind.OPT),
        Triple("com.miui.contentcatcher", "ContentCatcher", PkgKind.SAFE),
        Triple("com.xiaomi.joyose", "Joyose", PkgKind.OPT),
        Triple("com.miui.bugreport", "小米错误日志上报", PkgKind.OPT),
        Triple("com.miui.daemon", "系统守护日志回传", PkgKind.OPT),
        Triple("com.miui.systemAdSolution", "智能服务", PkgKind.RISK)
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
                "com.miui.guardprovider" -> "病毒扫描引擎：冻结将失去安全扫描与支付保护能力。建议改用【权限限制】+ 域名阻断，仅掐断其查杀报告与 APK 样本上传。"
                "com.miui.securitycenter" -> "手机管家主组件：冻结将失去垃圾清理、电量、骚扰拦截等全部能力，切勿冻结。反诈历史记录请用本页【本地残留清理】清除。"
                "com.miui.misightservice" -> "设备诊断通道：冻结可能影响售后问题反馈与远程诊断，建议【权限限制】。"
                "com.xiaomi.registration" -> "电信终端注册（CTEI）：冻结后终端注册业务失效，建议优先用【telemetry】域名段掐断其上报出口。"
                "com.xiaomi.aicr" -> "AI 组件（风控/AI 预警）：冻结可能影响部分 AI 智能功能，建议【权限限制】。"
                else -> ""
            }

            val desc = when (pkg) {
                "com.miui.analytics" -> "全天候在后台静默收集用户打开App的时间、频率与屏幕使用，建议立即冻结。"
                "com.miui.msa" -> "负责下发广告及生成不可变更的广告追踪标识，建议禁用或限制。"
                "com.miui.msa.global" -> "MSA 国际版，下发广告并生成广告追踪标识。"
                "com.miui.guardprovider" -> "GuardProvider：除病毒扫描外，还在后台枚举应用清单、采集远程下发的 APK 样本并上传查杀报告。"
                "com.miui.securitycenter" -> "手机管家主组件：反诈行为记录（通话/屏幕共享时长等）持续写入本地 fraud_detection_data.db，并向上报接口提供数据。"
                "com.miui.misightservice" -> "设备诊断事件打包上传，测试机型还会做 OAID/序列号级身份识别。"
                "com.xiaomi.aicr" -> "AI 组件行为统计与风控遥测，接入 OneTrack 统计，行为事件随安全与广告统计一并上报。"
                "com.xiaomi.registration" -> "电信终端注册（CTEI 上报）：向电信侧上报 IMEI/IMSI/ICCID/基站信息，含明文 HTTP 通道。"
                "com.miui.contentcatcher" -> "内容捕获行为统计，捕获内容并产出行为事件，经 OneTrack 事件入口发布。"
                "com.mobiletools.systemhelper" -> "运营商定制遥测探针。"
                "com.xiaomi.joyose" -> "云控策略下发与性能遥测通道。"
                "com.miui.bugreport" -> "全量崩溃现场日志回传。"
                "com.miui.daemon" -> "系统守护日志回传通道。"
                "com.miui.systemAdSolution" -> "系统应用开屏广告宿主。"
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
     *         本机不存在的包直接跳过：不同机型/版本预装不同，绝不向缺失组件强行写入
     */
    suspend fun setPackageState(pkg: String, targetState: Int): Boolean = withContext(Dispatchers.IO) {
        val installed = SuShellExecutor.execute("pm list packages --user 0 $pkg 2>/dev/null")
            .outputText.contains("package:$pkg")
        if (!installed) {
            Log.w(TAG, "跳过写入：$pkg 在本机不存在（不同机型/版本预装不同）")
            return@withContext false
        }

        SuShellExecutor.execute("mkdir -p /data/adb/privacy_guard")

        when (targetState) {
            0 -> disablePackage(pkg)
            1 -> enablePackage(pkg)
            2 -> restrictPackage(pkg)
            else -> false
        }
    }

    /**
     * 逐条设置 appops，返回是否全部成功。
     * 本机不支持的 op（版本差异导致 "Unknown operation" 等错误）按跳过处理，
     * 不计入失败——只拦截命中的规则，不存在的规则不写也不因此中断。
     */
    private suspend fun applyAppOps(pkg: String, mode: String): Boolean {
        var allOk = true
        for (op in RESTRICTED_APPOPS) {
            val opRes = SuShellExecutor.execute("cmd appops set $pkg $op $mode")
            if (opRes.isSuccess) continue

            val output = (opRes.stdout + opRes.stderr).joinToString(" ")
            if (isUnsupportedOpError(output)) {
                Log.i(TAG, "跳过本机不支持的 appop: $pkg $op")
                continue
            }
            Log.e(TAG, "appops $mode 失败: $pkg $op (exit=${opRes.exitCode})")
            allOk = false
        }
        return allOk
    }

    private fun isUnsupportedOpError(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("unknown operation") ||
            lower.contains("bad operation") ||
            lower.contains("no such") ||
            lower.contains("not found")
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

    /** 恢复启用：pm enable + 逐条恢复 appops（本机不存在的 op 跳过），真失败才整体失败且不清理标记文件 */
    private suspend fun enablePackage(pkg: String): Boolean {
        val pmRes = SuShellExecutor.execute("pm enable --user 0 $pkg 2>/dev/null || pm enable $pkg 2>/dev/null")
        if (!pmRes.isSuccess) {
            Log.e(TAG, "pm enable 失败: $pkg (exit=${pmRes.exitCode})")
            return false
        }
        val opsOk = applyAppOps(pkg, "allow")
        if (!opsOk) return false
        SuShellExecutor.execute("rm -f /data/adb/privacy_guard/.restricted_$pkg")
        return true
    }

    /** 限制模式：pm enable + 逐条剥夺 appops（跳过本机不支持的 op）+ 标记文件仅在全部成功后落盘 */
    private suspend fun restrictPackage(pkg: String): Boolean {
        val pmRes = SuShellExecutor.execute("pm enable --user 0 $pkg 2>/dev/null || pm enable $pkg 2>/dev/null")
        if (!pmRes.isSuccess) {
            Log.e(TAG, "pm enable 失败: $pkg (exit=${pmRes.exitCode})")
            return false
        }
        val opsOk = applyAppOps(pkg, "ignore")
        if (!opsOk) return false
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
        // 进程别名与体检页 TARGET_PKGS 的 label 保持同一套命名，跨页面口径统一
        val targets = listOf(
            "com.miui.securitycenter" to "安全中心数据引擎",
            "com.miui.guardprovider" to "病毒扫描与遥测回传",
            "com.mobiletools.systemhelper" to "SystemHelper",
            "com.miui.systemAdSolution" to "智能服务",
            "com.miui.analytics" to "小米统计服务",
            "com.miui.msa" to "MSA 广告标识服务",
            "com.miui.msa.global" to "MSA 全球广告服务",
            "com.miui.misightservice" to "MiSight",
            "com.xiaomi.aicr" to "AICR",
            "com.xiaomi.registration" to "电信终端注册",
            "com.miui.contentcatcher" to "ContentCatcher"
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

    /**
     * 清理手机管家反诈行为记录库（fraud_detection_data.db 及其 -wal/-shm 伴生文件）。
     * hook 类拦截工具只拦上报、不清旧账，历史反诈记录会永久留在本地；
     * 本方法用 root 先整库备份到 /data/adb/privacy_guard/fraud_backup/<时间戳>/，再删除原文件。
     * 全程先备份后删除，任一文件备份失败即中止，不产生半清状态。
     */
    suspend fun cleanFraudDatabases(): ResidueCleanResult = withContext(Dispatchers.IO) {
        val dbDir = "/data/data/com.miui.securitycenter/databases"

        // 手机管家必须预装
        val installed = SuShellExecutor.execute("pm list packages --user 0 com.miui.securitycenter 2>/dev/null")
            .outputText.contains("package:com.miui.securitycenter")
        if (!installed) {
            return@withContext ResidueCleanResult(emptyList(), "", true, "本机未预装手机管家，无反诈记录库可清理。")
        }

        // 停掉管家进程，避免库文件在写入中被复制/删除
        SuShellExecutor.execute("am force-stop com.miui.securitycenter 2>/dev/null")

        // 只认实证库前缀 fraud_detection_data.db（含 -wal / -shm 伴生文件），避免误伤骚扰拦截等其他库
        val names = SuShellExecutor.execute("ls -1 $dbDir 2>/dev/null").outputText
            .split('\n')
            .map { it.trim() }
            .filter { it == "fraud_detection_data.db" || it.startsWith("fraud_detection_data.db-") }
        if (names.isEmpty()) {
            return@withContext ResidueCleanResult(emptyList(), "", true, "未发现反诈行为记录库，可能已清理过。")
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupDir = "/data/adb/privacy_guard/fraud_backup/$timestamp"
        val mkdir = SuShellExecutor.execute("mkdir -p '$backupDir'")
        if (!mkdir.isSuccess) {
            return@withContext ResidueCleanResult(emptyList(), "", false, "创建备份目录失败 (exit=${mkdir.exitCode})，未删除任何文件。")
        }

        val removed = mutableListOf<String>()
        for (name in names) {
            val cp = SuShellExecutor.execute("cp '$dbDir/$name' '$backupDir/'")
            if (!cp.isSuccess) {
                val msg = "备份 $name 失败 (exit=${cp.exitCode})，已中止：已备份 ${removed.size}/${names.size} 个文件，原文件未删除。"
                return@withContext ResidueCleanResult(removed.toList(), backupDir, false, msg)
            }
            val rm = SuShellExecutor.execute("rm -f '$dbDir/$name'")
            if (!rm.isSuccess) {
                val msg = "删除 $name 失败 (exit=${rm.exitCode})，已备份至 $backupDir，可手动处理。"
                return@withContext ResidueCleanResult(removed.toList(), backupDir, false, msg)
            }
            removed.add(name)
        }
        ResidueCleanResult(removed.toList(), backupDir, true, "已清除 ${removed.size} 个反诈记录文件，备份在 $backupDir。")
    }
}
