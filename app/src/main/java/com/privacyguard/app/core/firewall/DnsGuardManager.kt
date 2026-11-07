package com.privacyguard.app.core.firewall

import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DnsGuardManager {

    private const val TAG = "DnsGuardManager"
    private const val CHAIN_DNS = "fj_blk_dns"

    // 知名公共 DNS IP 列表 (防止内置自建 DNS 直连上报)
    private val PUBLIC_DNS_SERVERS = listOf(
        "8.8.8.8", "8.8.4.4",            // Google DNS
        "1.1.1.1", "1.0.0.1",            // Cloudflare
        "9.9.9.9", "149.112.112.112",    // Quad9
        "208.67.222.222", "208.67.220.220", // OpenDNS
        "94.140.14.14", "94.140.15.15"   // AdGuard DNS
    )

    /**
     * 启用 DNS 兜底保护
     * 1. 封锁 TCP/UDP 853 端口 (DNS over TLS / DoT)
     * 2. 封锁 UDP 5353 端口 (mDNS 局域网探测/设备发现滥用)
     * 3. 封锁公共 DNS 直连 IP (保留本地 53 端口由系统路由器正常解析)
     */
    suspend fun applyDnsGuard(): Boolean = withContext(Dispatchers.IO) {
        val cmds = mutableListOf<String>()

        // 1. 初始化专有 DNS 拦截链
        cmds.add("iptables -w 5 -N $CHAIN_DNS 2>/dev/null")
        cmds.add("iptables -w 5 -F $CHAIN_DNS 2>/dev/null")

        // 2. 封禁 DoT (853 端口)
        cmds.add("iptables -w 5 -A $CHAIN_DNS -p tcp --dport 853 -j REJECT --reject-with tcp-reset 2>/dev/null")
        cmds.add("iptables -w 5 -A $CHAIN_DNS -p udp --dport 853 -j REJECT 2>/dev/null")

        // 3. 封禁 mDNS (5353 端口) 阻止跨应用局域网追踪
        cmds.add("iptables -w 5 -A $CHAIN_DNS -p udp --dport 5353 -j DROP 2>/dev/null")

        // 4. 封禁公共 DNS 直连 (防厂商硬编码绕过)
        for (dnsIp in PUBLIC_DNS_SERVERS) {
            cmds.add("iptables -w 5 -A $CHAIN_DNS -d $dnsIp -j REJECT 2>/dev/null")
        }

        // 5. 挂载到 OUTPUT
        cmds.add("iptables -w 5 -C OUTPUT -j $CHAIN_DNS 2>/dev/null || iptables -w 5 -I OUTPUT 2 -j $CHAIN_DNS")

        val res = SuShellExecutor.executeLines(cmds)
        Log.d(TAG, "applyDnsGuard: isSuccess=${res.isSuccess}")
        res.isSuccess
    }

    /**
     * 清理 DNS 兜底规则
     */
    suspend fun flushDnsGuard(): Boolean = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "iptables -w 5 -D OUTPUT -j $CHAIN_DNS 2>/dev/null",
            "iptables -w 5 -F $CHAIN_DNS 2>/dev/null",
            "iptables -w 5 -X $CHAIN_DNS 2>/dev/null"
        )
        val res = SuShellExecutor.executeLines(cmds)
        res.isSuccess
    }
}
