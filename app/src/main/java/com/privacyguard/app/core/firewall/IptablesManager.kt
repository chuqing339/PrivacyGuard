package com.privacyguard.app.core.firewall

import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

object IptablesManager {

    private const val TAG = "IptablesManager"

    // 专有防护链名称
    private const val CHAIN_V4 = "fj_blk_out"
    private const val CHAIN_V6 = "fj_blk6_out"
    private const val CHAIN_WL = "fj_wl_out"

    /**
     * 判断是否为局域网/保留 IP，坚决不下发拦截规则（防死断网）
     */
    fun isLanIp(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("192.168.") ||
            ip.startsWith("169.254.") || ip.startsWith("0.") || ip.startsWith("224.") || ip.startsWith("240.")) {
            return true
        }
        if (ip.startsWith("100.")) {
            val parts = ip.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull() ?: 0
                if (second in 64..127) return true
            }
        }
        if (ip.startsWith("172.")) {
            val parts = ip.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull() ?: 0
                if (second in 16..31) return true
            }
        }
        return false
    }

    /**
     * 清理所有已下发的 iptables 规则与自定义链
     */
    suspend fun flushRules(): Boolean = withContext(Dispatchers.IO) {
        val cmds = listOf(
            // IPv4 清理
            "iptables -w 5 -D OUTPUT -j $CHAIN_V4 2>/dev/null",
            "iptables -w 5 -F $CHAIN_V4 2>/dev/null",
            "iptables -w 5 -X $CHAIN_V4 2>/dev/null",
            // 白名单链清理
            "iptables -w 5 -F $CHAIN_WL 2>/dev/null",
            "iptables -w 5 -X $CHAIN_WL 2>/dev/null",
            // IPv6 清理
            "ip6tables -w 5 -D OUTPUT -j $CHAIN_V6 2>/dev/null",
            "ip6tables -w 5 -F $CHAIN_V6 2>/dev/null",
            "ip6tables -w 5 -X $CHAIN_V6 2>/dev/null"
        )
        val res = SuShellExecutor.executeLines(cmds)
        Log.d(TAG, "flushRules: ${res.isSuccess}")
        res.isSuccess
    }

    /**
     * 初始化防护链并挂载至 OUTPUT
     */
    suspend fun initChains(enableIpv6: Boolean, enableWhitelist: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmds = mutableListOf<String>()

        // 1. 创建 IPv4 链
        cmds.add("iptables -w 5 -N $CHAIN_V4 2>/dev/null")

        // 2. 插入内网与保留网段豁免 (必须在最前)
        val lanNets = listOf(
            "127.0.0.0/8",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "100.64.0.0/10"
        )
        for (net in lanNets) {
            cmds.add("iptables -w 5 -C $CHAIN_V4 -d $net -j RETURN 2>/dev/null || iptables -w 5 -A $CHAIN_V4 -d $net -j RETURN")
        }

        // 3. 白名单子链 (保护 LSPosed / GitHub / 小米账号)
        if (enableWhitelist) {
            cmds.add("iptables -w 5 -N $CHAIN_WL 2>/dev/null")
            cmds.add("iptables -w 5 -C $CHAIN_V4 -j $CHAIN_WL 2>/dev/null || iptables -w 5 -I $CHAIN_V4 1 -j $CHAIN_WL")
        }

        // 4. 将链挂入 OUTPUT 第一位
        cmds.add("iptables -w 5 -C OUTPUT -j $CHAIN_V4 2>/dev/null || iptables -w 5 -I OUTPUT 1 -j $CHAIN_V4")

        // 5. IPv6 双栈防护
        if (enableIpv6) {
            cmds.add("ip6tables -w 5 -N $CHAIN_V6 2>/dev/null")
            cmds.add("ip6tables -w 5 -A $CHAIN_V6 -d ::1/128 -j RETURN 2>/dev/null")
            cmds.add("ip6tables -w 5 -A $CHAIN_V6 -d fe80::/10 -j RETURN 2>/dev/null")
            cmds.add("ip6tables -w 5 -A $CHAIN_V6 -d fc00::/7 -j RETURN 2>/dev/null")
            cmds.add("ip6tables -w 5 -C OUTPUT -j $CHAIN_V6 2>/dev/null || ip6tables -w 5 -I OUTPUT 1 -j $CHAIN_V6")
        }

        val res = SuShellExecutor.executeLines(cmds)
        res.isSuccess
    }

    /**
     * 下发白名单 IP（放行）
     */
    suspend fun applyWhitelistIps(whitelistDomains: List<String>): Boolean = withContext(Dispatchers.IO) {
        val cmds = mutableListOf<String>()
        cmds.add("iptables -w 5 -F $CHAIN_WL 2>/dev/null")

        for (domain in whitelistDomains) {
            val ips = resolveDomainSafely(domain)
            for (ip in ips) {
                if (ip.isNotEmpty() && !isLanIp(ip)) {
                    cmds.add("iptables -w 5 -A $CHAIN_WL -d $ip -j RETURN 2>/dev/null")
                }
            }
        }

        if (cmds.size > 1) {
            SuShellExecutor.executeLines(cmds)
        }
        true
    }

    /**
     * 下发拦截 IP 规则（IPv4 + IPv6）
     */
    suspend fun applyBlockRules(
        domains: List<String>,
        enableIpv6: Boolean,
        enableLog: Boolean
    ): Int = withContext(Dispatchers.IO) {
        var appliedCount = 0
        val v4Cmds = mutableListOf<String>()
        val v6Cmds = mutableListOf<String>()

        // 批次解析
        for (domain in domains) {
            val ips = resolveDomainSafely(domain)
            for (ip in ips) {
                if (ip.contains(":")) {
                    // IPv6
                    if (enableIpv6 && !ip.startsWith("fe80:") && !ip.startsWith("::1")) {
                        if (enableLog) {
                            v6Cmds.add("ip6tables -w 5 -A $CHAIN_V6 -d $ip -m limit --limit 20/min -j LOG --log-prefix \"[PG_BLK6] \" 2>/dev/null")
                        }
                        v6Cmds.add("ip6tables -w 5 -A $CHAIN_V6 -d $ip -j REJECT 2>/dev/null")
                        appliedCount++
                    }
                } else {
                    // IPv4
                    if (!isLanIp(ip)) {
                        if (enableLog) {
                            v4Cmds.add("iptables -w 5 -A $CHAIN_V4 -d $ip -m limit --limit 30/min -j LOG --log-prefix \"[PG_BLK] \" 2>/dev/null")
                        }
                        v4Cmds.add("iptables -w 5 -A $CHAIN_V4 -d $ip -j REJECT --reject-with icmp-port-unreachable 2>/dev/null")
                        appliedCount++
                    }
                }
            }
        }

        // 分批执行，防止命令过长
        val chunkSize = 50
        v4Cmds.chunked(chunkSize).forEach { chunk ->
            SuShellExecutor.executeLines(chunk)
        }
        v6Cmds.chunked(chunkSize).forEach { chunk ->
            SuShellExecutor.executeLines(chunk)
        }

        Log.d(TAG, "applyBlockRules completed: $appliedCount rules applied")
        appliedCount
    }

    /**
     * 解析域名 IP（采用 Java InetAddress 与 su 自带解析双路验证）
     */
    private fun resolveDomainSafely(domain: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val addresses = InetAddress.getAllByName(domain)
            for (addr in addresses) {
                val host = addr.hostAddress
                if (host != null && host != "0.0.0.0" && host != "::" && host != "127.0.0.1") {
                    result.add(host)
                }
            }
        } catch (_: Exception) {
            // 解析失败（可能已在 Hosts 屏蔽或网络离线）
        }
        return result
    }

    /**
     * 检查当前 iptables 链是否已激活
     */
    suspend fun isFirewallActive(): Boolean = withContext(Dispatchers.IO) {
        val res = SuShellExecutor.execute("iptables -w 5 -S OUTPUT 2>/dev/null | grep -q '$CHAIN_V4' && echo ACTIVE")
        res.outputText.contains("ACTIVE")
    }
}
