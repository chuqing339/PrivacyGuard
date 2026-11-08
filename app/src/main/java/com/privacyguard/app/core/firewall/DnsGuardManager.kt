package com.privacyguard.app.core.firewall

import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

object DnsGuardManager {

    private const val TAG = "DnsGuardManager"
    private const val CHAIN_DNS = "fj_blk_dns"

    // 知名公共 DNS IPv4 列表 (防止内置自建 DNS 直连上报)
    private val PUBLIC_DNS_V4 = listOf(
        "8.8.8.8", "8.8.4.4",            // Google DNS
        "1.1.1.1", "1.0.0.1",            // Cloudflare
        "9.9.9.9", "149.112.112.112",    // Quad9
        "208.67.222.222", "208.67.220.220", // OpenDNS
        "94.140.14.14", "94.140.15.15"   // AdGuard DNS
    )

    // 知名公共 DNS IPv6 列表（双栈设备绕过 v4 拦截的补口）
    private val PUBLIC_DNS_V6 = listOf(
        "2001:4860:4860::8888",   // Google DNS
        "2606:4700:4700::1111",   // Cloudflare
        "2620:fe::fe"             // Quad9
    )

    /**
     * 启用 DNS 兜底保护
     * 1. 封锁 TCP/UDP 853 端口 (DNS over TLS / DoT)
     * 2. 封锁 UDP 5353 端口 (mDNS 局域网探测/设备发现滥用)
     * 3. 封锁公共 DNS 直连 IP (保留本地 53 端口由系统路由器正常解析)，
     *    其中命中本机当前上游的 IP 豁免不下发，防止拦截自身解析流量导致全机断网
     */
    suspend fun applyDnsGuard(): Boolean = withContext(Dispatchers.IO) {
        val localDns = collectLocalDnsServers()

        // 本机上游豁免：凡命中本机当前 DNS 的公共 IP 跳过不下发
        val blockV4 = PUBLIC_DNS_V4.filter { normalizeIpLiteral(it) !in localDns }
        val blockV6 = PUBLIC_DNS_V6.filter { normalizeIpLiteral(it) !in localDns }
        val exemptV4 = PUBLIC_DNS_V4.size - blockV4.size
        val exemptV6 = PUBLIC_DNS_V6.size - blockV6.size
        if (exemptV4 > 0 || exemptV6 > 0) {
            Log.i(TAG, "本机上游命中公共 DNS，豁免 v4=$exemptV4 v6=$exemptV6 个 IP")
        }

        val cmds = mutableListOf<String>()

        // 1. 初始化专有 DNS 拦截链 (v4)
        cmds.add("iptables -w 5 -N $CHAIN_DNS 2>/dev/null")
        cmds.add("iptables -w 5 -F $CHAIN_DNS 2>/dev/null")

        // 2. 封禁 DoT (853 端口)
        cmds.add("iptables -w 5 -A $CHAIN_DNS -p tcp --dport 853 -j REJECT --reject-with tcp-reset 2>/dev/null")
        cmds.add("iptables -w 5 -A $CHAIN_DNS -p udp --dport 853 -j REJECT 2>/dev/null")

        // 3. 封禁 mDNS (5353 端口) 阻止跨应用局域网追踪
        cmds.add("iptables -w 5 -A $CHAIN_DNS -p udp --dport 5353 -j DROP 2>/dev/null")

        // 4. 封禁公共 DNS 直连 (防厂商硬编码绕过，本机上游除外)
        for (dnsIp in blockV4) {
            cmds.add("iptables -w 5 -A $CHAIN_DNS -d $dnsIp -j REJECT 2>/dev/null")
        }

        // 5. IPv6 补口：公共 DNS IPv6 直连拦截（链名按协议族独立命名空间，可与 v4 同名）
        cmds.add("ip6tables -w 5 -N $CHAIN_DNS 2>/dev/null")
        cmds.add("ip6tables -w 5 -F $CHAIN_DNS 2>/dev/null")
        for (dnsIp in blockV6) {
            cmds.add("ip6tables -w 5 -A $CHAIN_DNS -d $dnsIp -j REJECT 2>/dev/null")
        }

        // 6. 挂载到 OUTPUT（v4 与 v6 各挂一份）
        cmds.add("iptables -w 5 -C OUTPUT -j $CHAIN_DNS 2>/dev/null || iptables -w 5 -I OUTPUT 2 -j $CHAIN_DNS")
        cmds.add("ip6tables -w 5 -C OUTPUT -j $CHAIN_DNS 2>/dev/null || ip6tables -w 5 -I OUTPUT 2 -j $CHAIN_DNS")

        val res = SuShellExecutor.executeLines(cmds)
        if (!res.isSuccess) {
            Log.w(TAG, "applyDnsGuard 下发失败: exitCode=${res.exitCode}")
            return@withContext false
        }

        // 回读校验：OUTPUT 已挂链，且链内规则数不少于「端口封禁 3 条 + 生效 DNS IP 数」
        val verify = SuShellExecutor.executeLines(
            listOf(
                "iptables -w 5 -S OUTPUT 2>/dev/null",
                "iptables -w 5 -S $CHAIN_DNS 2>/dev/null"
            )
        )
        val jumpOk = verify.stdout.any { it.contains("-j $CHAIN_DNS") }
        val ruleCount = verify.stdout.count { it.contains("-A $CHAIN_DNS") }
        val expectedMin = 3 + blockV4.size
        val ok = jumpOk && ruleCount >= expectedMin
        Log.d(TAG, "applyDnsGuard: jumpOk=$jumpOk ruleCount=$ruleCount expectedMin=$expectedMin ok=$ok")
        ok
    }

    /**
     * 清理 DNS 兜底规则 (v4 + v6)
     */
    suspend fun flushDnsGuard(): Boolean = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "iptables -w 5 -D OUTPUT -j $CHAIN_DNS 2>/dev/null",
            "iptables -w 5 -F $CHAIN_DNS 2>/dev/null",
            "iptables -w 5 -X $CHAIN_DNS 2>/dev/null",
            "ip6tables -w 5 -D OUTPUT -j $CHAIN_DNS 2>/dev/null",
            "ip6tables -w 5 -F $CHAIN_DNS 2>/dev/null",
            "ip6tables -w 5 -X $CHAIN_DNS 2>/dev/null"
        )
        val res = SuShellExecutor.executeLines(cmds)
        res.isSuccess
    }

    /**
     * 采集本机当前生效的 DNS 上游，作为豁免集：
     * 通过 root shell 读取系统 DNS 属性（net.dns1-4 / dhcp 各网卡）与 /etc/resolv.conf。
     * 部分系统上这些属性可能为空，此时豁免集为空，行为退化为与旧版一致（全量下发）。
     */
    private suspend fun collectLocalDnsServers(): Set<String> = withContext(Dispatchers.IO) {
        val servers = mutableSetOf<String>()
        try {
            val propRes = SuShellExecutor.execute(
                "for p in net.dns1 net.dns2 net.dns3 net.dns4 dhcp.wlan0.dns1 dhcp.eth0.dns1 dhcp.rmnet0.dns1; do getprop \$p; done"
            )
            propRes.stdout.forEach { line ->
                val v = line.trim()
                if (v.isNotEmpty()) servers.add(v)
            }

            val resolvRes = SuShellExecutor.execute("cat /etc/resolv.conf 2>/dev/null")
            for (line in resolvRes.stdout) {
                val t = line.trim()
                if (t.startsWith("nameserver")) {
                    val ip = t.removePrefix("nameserver").trim()
                    if (ip.isNotEmpty()) servers.add(ip)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "采集本机 DNS 上游失败，本次不豁免", e)
        }
        servers.map { normalizeIpLiteral(it) }.toSet()
    }

    /**
     * IP 字面量归一化（IPv6 展开为标准形、统一小写），用于豁免集比对。
     * 仅对形似 IP 的字符串走 InetAddress 标准化（字面量解析不触发真实 DNS 查询）
     */
    private fun normalizeIpLiteral(raw: String): String {
        val v = raw.trim().lowercase()
        return try {
            if (v.contains(":") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(v)) {
                InetAddress.getByName(v).hostAddress?.lowercase() ?: v
            } else {
                v
            }
        } catch (_: Exception) {
            v
        }
    }
}
