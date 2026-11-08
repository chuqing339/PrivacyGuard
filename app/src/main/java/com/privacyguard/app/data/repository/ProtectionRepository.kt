package com.privacyguard.app.data.repository

import android.content.Context
import android.util.Log
import com.privacyguard.app.core.firewall.DnsGuardManager
import com.privacyguard.app.core.firewall.HostsManager
import com.privacyguard.app.core.firewall.IptablesManager
import com.privacyguard.app.core.root.SuShellExecutor
import com.privacyguard.app.data.model.DashboardState
import com.privacyguard.app.data.model.EngineStatus
import com.privacyguard.app.data.model.LayerStatus
import com.privacyguard.app.data.preference.GuardConfig
import com.privacyguard.app.data.preference.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProtectionRepository(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val ruleRepository: RuleRepository
) {

    private val TAG = "ProtectionRepository"

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    // 启动/停止互斥锁：防止 startProtection 与 stopProtection 并发交叉执行导致规则残留或状态错乱
    private val mutex = Mutex()

    /**
     * 初始化检测与环境自检
     */
    suspend fun checkEnvironment() = withContext(Dispatchers.IO) {
        val hasRoot = SuShellExecutor.checkRoot()
        val flavor = if (hasRoot) SuShellExecutor.detectRootFlavor() else "None"
        val isFirewallOn = if (hasRoot) IptablesManager.isFirewallActive() else false

        _dashboardState.value = _dashboardState.value.copy(
            isRootGranted = hasRoot,
            rootFlavor = flavor,
            engineStatus = if (hasRoot && isFirewallOn) EngineStatus.RUNNING else if (hasRoot) EngineStatus.STOPPED else EngineStatus.NO_ROOT
        )
    }

    /**
     * 应用并启动全机防护
     */
    suspend fun startProtection(config: GuardConfig): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!_dashboardState.value.isRootGranted) {
                checkEnvironment()
                if (!_dashboardState.value.isRootGranted) return@withContext false
            }

            _dashboardState.value = _dashboardState.value.copy(engineStatus = EngineStatus.STARTING)

            try {
                val activeDomains = ruleRepository.getActiveDomains()
                val whitelistDomains = ruleRepository.getWhitelistDomains()

                // 白名单语义 = 永不拦截：App 是唯一规则写入方，必须自己保证
                // hosts / IP 层的域名集合与白名单求交（白名单域名不进入任何拦截层）
                val whitelistSet = whitelistDomains.toSet()
                val effectiveDomains = activeDomains.filter { it !in whitelistSet }
                if (effectiveDomains.size < activeDomains.size) {
                    Log.i(TAG, "白名单求交：${activeDomains.size - effectiveDomains.size} 个域名被排除出拦截层")
                }

                // 0. 预解析（必须在 hosts 生效之前）：
                //    先撤下上一轮可能残留的 hosts 挂载（标记守护，未挂载时为空操作），
                //    再并发解析全部拦截/白名单域名。若先写 hosts（域名 → 0.0.0.0）再解析，
                //    解析会读到被自己污染的 hosts，IP 层恒空，形成解析自锁。
                //    解析失败的域名由 resolveDomainsConcurrently 记录日志并跳过，不中断流程。
                HostsManager.unmountHosts()
                val blockIpMap = IptablesManager.resolveDomainsConcurrently(effectiveDomains)
                val whitelistIpMap = if (config.ipv4Enabled && config.whitelistEnabled) {
                    IptablesManager.resolveDomainsConcurrently(whitelistDomains)
                } else {
                    emptyMap()
                }

                // 1. Hosts 层
                var hostsOk = false
                if (config.hostsEnabled) {
                    hostsOk = HostsManager.applyHosts(context, effectiveDomains, config.moduleSyncMode)
                } else {
                    HostsManager.revertHosts(context)
                }

                // 2. 清理旧 iptables 规则（含 DNS 兜底链）
                IptablesManager.flushRules()

                // 3. 构建链与内网白名单（回读校验，按真实结果置位）
                var initResult = IptablesManager.ChainsInitResult(false, false, false)
                var whitelistIpsOk = false
                var blockResult = IptablesManager.BlockApplyResult(false, false, 0)
                if (config.ipv4Enabled) {
                    initResult = IptablesManager.initChains(
                        enableIpv6 = config.ipv6Enabled,
                        enableWhitelist = config.whitelistEnabled
                    )

                    // 4. 下发关键白名单（使用预解析结果，不做二次解析）
                    if (config.whitelistEnabled) {
                        whitelistIpsOk = IptablesManager.applyWhitelistIps(whitelistIpMap)
                    }

                    // 5. 下发拦截 IP（使用 hosts 污染前预解析的 IP 映射）
                    blockResult = IptablesManager.applyBlockRules(
                        ipMap = blockIpMap,
                        enableIpv6 = config.ipv6Enabled,
                        enableLog = config.logEnabled
                    )
                }

                // 6. DNS 兜底
                var dnsOk = false
                if (config.dnsGuardEnabled) {
                    dnsOk = DnsGuardManager.applyDnsGuard()
                } else {
                    DnsGuardManager.flushDnsGuard()
                }

                settingsDataStore.updateLastRefreshTime()

                // 各层真实生效结果（全部经回读校验）
                val v4LayerOk = config.ipv4Enabled && initResult.v4Ok && blockResult.v4Success
                val v6LayerOk = config.ipv4Enabled && config.ipv6Enabled && initResult.v6Ok && blockResult.v6Success
                val whitelistLayerOk = config.ipv4Enabled && config.whitelistEnabled && initResult.whitelistOk && whitelistIpsOk

                // 所有已启用的层全部失败 → ERROR，而非假装 RUNNING
                val enabledLayerResults = mutableListOf<Boolean>()
                if (config.hostsEnabled) enabledLayerResults.add(hostsOk)
                if (config.ipv4Enabled) enabledLayerResults.add(v4LayerOk)
                if (config.ipv4Enabled && config.ipv6Enabled) enabledLayerResults.add(v6LayerOk)
                if (config.dnsGuardEnabled) enabledLayerResults.add(dnsOk)
                if (config.ipv4Enabled && config.whitelistEnabled) enabledLayerResults.add(whitelistLayerOk)
                val allEnabledLayersFailed = enabledLayerResults.isNotEmpty() && enabledLayerResults.none { it }

                _dashboardState.value = _dashboardState.value.copy(
                    engineStatus = if (allEnabledLayersFailed) EngineStatus.ERROR else EngineStatus.RUNNING,
                    layerStatus = LayerStatus(
                        hostsActive = hostsOk,
                        ipv4Active = v4LayerOk,
                        ipv6Active = v6LayerOk,
                        dnsGuardActive = dnsOk,
                        whitelistActive = whitelistLayerOk,
                        activeRuleCount = effectiveDomains.size,
                        activeIpBlockCount = blockResult.verifiedCount
                    ),
                    lastRefreshTime = System.currentTimeMillis()
                )

                true
            } catch (e: Exception) {
                Log.e(TAG, "startProtection failed", e)
                _dashboardState.value = _dashboardState.value.copy(engineStatus = EngineStatus.ERROR)
                false
            }
        }
    }

    /**
     * 停止防护并清理所有规则
     */
    suspend fun stopProtection(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                HostsManager.revertHosts(context)
                // flushRules 已包含 DNS 兜底链完整清理（与 DnsGuardManager.flushDnsGuard 对齐），
                // 此处再调一次 flushDnsGuard 作为双保险，确保任何路径下都清干净
                IptablesManager.flushRules()
                DnsGuardManager.flushDnsGuard()

                _dashboardState.value = _dashboardState.value.copy(
                    engineStatus = EngineStatus.STOPPED,
                    layerStatus = LayerStatus()
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "stopProtection failed", e)
                false
            }
        }
    }

    fun updateStats(total: Int, today: Int, score: Int) {
        _dashboardState.value = _dashboardState.value.copy(
            totalInterceptCount = total,
            todayInterceptCount = today,
            privacyScore = score
        )
    }
}
