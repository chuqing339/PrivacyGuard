package com.privacyguard.app.data.model

/**
 * 防护引擎运行状态
 */
enum class EngineStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
    NO_ROOT
}

/**
 * 四层防护状态详情
 */
data class LayerStatus(
    val hostsActive: Boolean = false,
    val ipv4Active: Boolean = false,
    val ipv6Active: Boolean = false,
    val dnsGuardActive: Boolean = false,
    val whitelistActive: Boolean = false,
    val activeRuleCount: Int = 0,
    val activeIpBlockCount: Int = 0
)

/**
 * 仪表盘总体状态
 */
data class DashboardState(
    val engineStatus: EngineStatus = EngineStatus.STOPPED,
    val layerStatus: LayerStatus = LayerStatus(),
    val isRootGranted: Boolean = false,
    val rootFlavor: String = "",       // Magisk, KernelSU, APatch, SuperSU
    val totalInterceptCount: Int = 0,
    val todayInterceptCount: Int = 0,
    val privacyScore: Int = 100,       // 隐私安全体检得分 (0-100)
    val lastRefreshTime: Long = 0L,
    val currentNetwork: String = "WIFI"
)

/**
 * 小米系统级隐私宿主
 */
data class PrivacyScanItem(
    val packageName: String,
    val label: String,
    val kind: PkgKind,
    val state: PkgState,
    val pids: List<String> = emptyList(),
    val warningText: String = "",
    val description: String = ""
)

enum class PkgKind {
    SAFE,   // 低风险，可放心禁用
    OPT,    // 可选优化，需手动确认
    RISK    // 高风险，不建议直接禁用（建议走域名阻断）
}

enum class PkgState {
    ENABLED,     // 正常启用中
    DISABLED,    // 已彻底禁用 (pm disable-user)
    RESTRICTED,  // 已限制后台与敏感权限 (appops)
    ABSENT       // 系统中未预装
}

/**
 * 运行中敏感探测进程
 */
data class RunningProcessItem(
    val name: String,
    val label: String,
    val pid: String,
    val isSuspicious: Boolean = true
)

/**
 * 本地采集残留清理结果（反诈行为记录库等）
 */
data class ResidueCleanResult(
    val removedFiles: List<String>,
    val backupDir: String,
    val success: Boolean,
    val message: String
)

/**
 * 预设分段
 */
enum class SegmentType(val key: String, val title: String, val defaultDesc: String) {
    TELEMETRY("telemetry", "小米遥测与多媒体回传", "拦截安全中心多媒体/视频回传、手机管家数据上报、电信终端注册(CTEI)与 MiSight 测试设备识别"),
    ANTIFRAUD("antifraud", "MIUI反诈特征与信通院", "阻止将用户已安装App列表、Hash哈希与行为记录向反诈中心和信通院上报"),
    RISKGATE("riskgate", "第三方风控隐秘探针", "阻断微知等嵌入在系统与应用内部的隐秘设备指纹与风控探针"),
    MIUIAD("miuiad", "MIUI/HyperOS广告与追踪", "屏蔽智能服务(Zeus/Mimo)、广告曝光点击追踪、开屏监测等"),
    DOH("doh", "加密公共DNS入口 (DoH)", "阻断国外和公共加密DNS服务，阻止应用暗度陈仓绕过本地Hosts拦截"),
    ADSDK("adsdk", "第三方通用追踪与广告SDK", "封锁友盟Umeng、腾讯广点通、Google Ads、AppLovin等遥测SDK"),
    CUSTOM("custom", "用户自定义屏蔽域名", "由您手动添加并强制拦截的敏感域名与服务器")
}
