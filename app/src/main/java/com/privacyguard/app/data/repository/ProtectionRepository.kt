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
import kotlinx.coroutines.withContext

class ProtectionRepository(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val ruleRepository: RuleRepository
) {

    private val TAG = "ProtectionRepository"

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

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
    suspend fun startProtection(config: GuardConfig): Boolean = withContext(Dispatchers.IO) {
        if (!_dashboardState.value.isRootGranted) {
            checkEnvironment()
            if (!_dashboardState.value.isRootGranted) return@withContext false
        }

        _dashboardState.value = _dashboardState.value.copy(engineStatus = EngineStatus.STARTING)

        try {
            val activeDomains = ruleRepository.getActiveDomains()
            val whitelistDomains = ruleRepository.getWhitelistDomains()

            // 1. Hosts 层
            var hostsOk = false
            if (config.hostsEnabled) {
                hostsOk = HostsManager.applyHosts(context, activeDomains, config.moduleSyncMode)
            } else {
                HostsManager.revertHosts(context)
            }

            // 2. 清理旧 iptables 规则
            IptablesManager.flushRules()

            // 3. 构建链与内网白名单
            var iptablesOk = false
            var appliedIps = 0
            if (config.ipv4Enabled) {
                IptablesManager.initChains(
                    enableIpv6 = config.ipv6Enabled,
                    enableWhitelist = config.whitelistEnabled
                )

                // 4. 下发关键白名单
                if (config.whitelistEnabled) {
                    IptablesManager.applyWhitelistIps(whitelistDomains)
                }

                // 5. 下发拦截 IP
                appliedIps = IptablesManager.applyBlockRules(
                    domains = activeDomains,
                    enableIpv6 = config.ipv6Enabled,
                    enableLog = config.logEnabled
                )
                iptablesOk = true
            }

            // 6. DNS 兜底
            var dnsOk = false
            if (config.dnsGuardEnabled) {
                dnsOk = DnsGuardManager.applyDnsGuard()
            } else {
                DnsGuardManager.flushDnsGuard()
            }

            settingsDataStore.updateLastRefreshTime()

            _dashboardState.value = _dashboardState.value.copy(
                engineStatus = EngineStatus.RUNNING,
                layerStatus = LayerStatus(
                    hostsActive = hostsOk,
                    ipv4Active = iptablesOk,
                    ipv6Active = config.ipv6Enabled && iptablesOk,
                    dnsGuardActive = dnsOk,
                    whitelistActive = config.whitelistEnabled,
                    activeRuleCount = activeDomains.size,
                    activeIpBlockCount = appliedIps
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

    /**
     * 停止防护并清理所有规则
     */
    suspend fun stopProtection(): Boolean = withContext(Dispatchers.IO) {
        try {
            HostsManager.revertHosts(context)
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

    fun updateStats(total: Int, today: Int, score: Int) {
        _dashboardState.value = _dashboardState.value.copy(
            totalInterceptCount = total,
            todayInterceptCount = today,
            privacyScore = score
        )
    }
}
