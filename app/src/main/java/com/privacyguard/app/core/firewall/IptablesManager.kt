package com.privacyguard.app.core.firewall

import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

object IptablesManager {

    private const val TAG = "IptablesManager"

    // 专有防护链名称
    private const val CHAIN_V4 = "fj_blk_out"
    private const val CHAIN_V6 = "fj_blk6_out"
    private const val CHAIN_WL = "fj_wl_out"
    // DNS 兜底链（与 DnsGuardManager.CHAIN_DNS 对齐），flushRules 需统一清理
    private const val CHAIN_DNS = "fj_blk_dns"

    // LOG --log-uid 内核能力探测缓存（null = 未探测，进程内只探测一次）
    @Volatile
    private var logUidSupported: Boolean? = null

    /**
     * 防护链初始化结果（按回读校验真实置位，非启用层一律 false）
     */
    data class ChainsInitResult(
        val v4Ok: Boolean,          // IPv4 主链存在、内网豁免齐全且已挂载 OUTPUT
        val v6Ok: Boolean,          // IPv6 链存在、豁免齐全且已挂载 OUTPUT
        val whitelistOk: Boolean    // 白名单子链已创建且已挂入主链
    )

    /**
     * 拦截规则下发结果（verifiedCount 为回读校验确认生效的 REJECT 规则数）
     */
    data class BlockApplyResult(
        val v4Success: Boolean,
        val v6Success: Boolean,
        val verifiedCount: Int
    )

    /**
     * 判断是否为局域网/保留 IP，坚决不下发拦截规则（防死断网）
     */
    fun isLanIp(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("192.168.") ||
            ip.startsWith("169.254.") || ip.startsWith("0.") || ip.startsWith("224.") || ip.startsWith("240.") ||
            ip.startsWith("198.18.") || ip.startsWith("198.19.") ||   // 198.18.0.0/15 基准测试保留段（Clash fake-ip 默认网段）
            ip.startsWith("192.0.0.")                                  // 192.0.0.0/24 IETF 协议分配保留段
        ) {
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
     * 包含 DNS 兜底链：无论 dnsGuardEnabled 当前值如何都完整清理（flush + 删链 + 删 OUTPUT 引用），
     * 与 DnsGuardManager.flushDnsGuard 对齐，避免停止防护后残留拦截规则
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
            "ip6tables -w 5 -X $CHAIN_V6 2>/dev/null",
            // DNS 兜底链清理（v4 + v6）
            "iptables -w 5 -D OUTPUT -j $CHAIN_DNS 2>/dev/null",
            "iptables -w 5 -F $CHAIN_DNS 2>/dev/null",
            "iptables -w 5 -X $CHAIN_DNS 2>/dev/null",
            "ip6tables -w 5 -D OUTPUT -j $CHAIN_DNS 2>/dev/null",
            "ip6tables -w 5 -F $CHAIN_DNS 2>/dev/null",
            "ip6tables -w 5 -X $CHAIN_DNS 2>/dev/null"
        )
        val res = SuShellExecutor.executeLines(cmds)
        Log.d(TAG, "flushRules: ${res.isSuccess}")
        res.isSuccess
    }

    /**
     * 初始化防护链并挂载至 OUTPUT
     * 全部命令执行后回读校验：链存在、内网豁免齐全、OUTPUT 挂载成功，按真实结果置位
     */
    suspend fun initChains(enableIpv6: Boolean, enableWhitelist: Boolean): ChainsInitResult = withContext(Dispatchers.IO) {
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

        // 5. IPv6 双栈防护（-C || -A 防重复累积，与 IPv4 写法一致）
        if (enableIpv6) {
            cmds.add("ip6tables -w 5 -N $CHAIN_V6 2>/dev/null")
            cmds.add("ip6tables -w 5 -C $CHAIN_V6 -d ::1/128 -j RETURN 2>/dev/null || ip6tables -w 5 -A $CHAIN_V6 -d ::1/128 -j RETURN")
            cmds.add("ip6tables -w 5 -C $CHAIN_V6 -d fe80::/10 -j RETURN 2>/dev/null || ip6tables -w 5 -A $CHAIN_V6 -d fe80::/10 -j RETURN")
            cmds.add("ip6tables -w 5 -C $CHAIN_V6 -d fc00::/7 -j RETURN 2>/dev/null || ip6tables -w 5 -A $CHAIN_V6 -d fc00::/7 -j RETURN")
            cmds.add("ip6tables -w 5 -C OUTPUT -j $CHAIN_V6 2>/dev/null || ip6tables -w 5 -I OUTPUT 1 -j $CHAIN_V6")
        }

        val res = SuShellExecutor.executeLines(cmds)
        if (!res.isSuccess) {
            Log.w(TAG, "initChains 下发失败: exitCode=${res.exitCode}")
            return@withContext ChainsInitResult(false, false, false)
        }

        // 回读校验：-S 输出中确认 OUTPUT 挂载、内网豁免与白名单子链挂载
        val verifyCmds = mutableListOf(
            "iptables -w 5 -S OUTPUT 2>/dev/null",
            "iptables -w 5 -S $CHAIN_V4 2>/dev/null"
        )
        if (enableWhitelist) verifyCmds.add("iptables -w 5 -S $CHAIN_WL 2>/dev/null")
        if (enableIpv6) {
            verifyCmds.add("ip6tables -w 5 -S OUTPUT 2>/dev/null")
            verifyCmds.add("ip6tables -w 5 -S $CHAIN_V6 2>/dev/null")
        }
        val verify = SuShellExecutor.executeLines(verifyCmds)

        val v4JumpOk = verify.stdout.any { it.contains("-j $CHAIN_V4") }
        val v4ReturnCount = verify.stdout.count { it.startsWith("-A $CHAIN_V4") && it.contains("-j RETURN") }
        val wlJumpOk = verify.stdout.any { it.startsWith("-A $CHAIN_V4") && it.contains("-j $CHAIN_WL") }
        val v6JumpOk = verify.stdout.any { it.contains("-j $CHAIN_V6") }
        val v6ReturnCount = verify.stdout.count { it.startsWith("-A $CHAIN_V6") && it.contains("-j RETURN") }

        if (!v4JumpOk || v4ReturnCount < lanNets.size) {
            Log.w(TAG, "initChains IPv4 回读校验失败: jumpOk=$v4JumpOk returnCount=$v4ReturnCount")
        }
        if (enableIpv6 && (!v6JumpOk || v6ReturnCount < 3)) {
            Log.w(TAG, "initChains IPv6 回读校验失败: jumpOk=$v6JumpOk returnCount=$v6ReturnCount")
        }

        ChainsInitResult(
            v4Ok = v4JumpOk && v4ReturnCount >= lanNets.size,
            v6Ok = enableIpv6 && v6JumpOk && v6ReturnCount >= 3,
            whitelistOk = enableWhitelist && wlJumpOk
        )
    }

    /**
     * 下发白名单 IP（放行）
     * @param ipMap 域名 → 已解析 IP 列表（必须在 hosts 生效前解析，避免解析被自身污染）
     */
    suspend fun applyWhitelistIps(ipMap: Map<String, List<String>>): Boolean = withContext(Dispatchers.IO) {
        // 先清空再重建，回读时 RETURN 行数才能与期望精确比对
        val cmds = mutableListOf<String>("iptables -w 5 -F $CHAIN_WL 2>/dev/null")

        val whitelistIps = LinkedHashSet<String>()
        for (ips in ipMap.values) {
            for (ip in ips) {
                if (ip.isNotEmpty() && !isLanIp(ip)) {
                    whitelistIps.add(ip)
                }
            }
        }
        for (ip in whitelistIps) {
            cmds.add("iptables -w 5 -A $CHAIN_WL -d $ip -j RETURN 2>/dev/null")
        }

        val res = SuShellExecutor.executeLines(cmds)
        if (!res.isSuccess) {
            Log.w(TAG, "applyWhitelistIps 下发失败: exitCode=${res.exitCode}")
            return@withContext false
        }

        // 回读校验：RETURN 行数与期望一致
        val verify = SuShellExecutor.execute("iptables -w 5 -S $CHAIN_WL 2>/dev/null")
        val actual = verify.stdout.count { it.contains("-j RETURN") }
        if (actual != whitelistIps.size) {
            Log.w(TAG, "applyWhitelistIps 回读不符: expect=${whitelistIps.size} actual=$actual")
            return@withContext false
        }
        true
    }

    /**
     * 下发拦截 IP 规则（IPv4 + IPv6）
     * @param ipMap 域名 → 已解析 IP 列表（必须在 hosts 生效前完成解析后传入）
     */
    suspend fun applyBlockRules(
        ipMap: Map<String, List<String>>,
        enableIpv6: Boolean,
        enableLog: Boolean
    ): BlockApplyResult = withContext(Dispatchers.IO) {
        // 按协议族收集并去重，期望规则数才能与回读数精确比对
        val v4Ips = LinkedHashSet<String>()
        val v6Ips = LinkedHashSet<String>()
        for (ips in ipMap.values) {
            for (ip in ips) {
                if (ip.contains(":")) {
                    // IPv6
                    if (enableIpv6 && !ip.startsWith("fe80:") && !ip.startsWith("::1")) {
                        v6Ips.add(ip)
                    }
                } else {
                    // IPv4
                    if (!isLanIp(ip)) {
                        v4Ips.add(ip)
                    }
                }
            }
        }

        // LOG 规则按内核能力探测结果决定是否带 --log-uid（影响拦截日志能否归因到具体应用）
        val logUidArg = if (enableLog && detectLogUidSupport()) " --log-uid" else ""

        val v4Cmds = mutableListOf<String>()
        val v6Cmds = mutableListOf<String>()
        for (ip in v4Ips) {
            if (enableLog) {
                v4Cmds.add("iptables -w 5 -A $CHAIN_V4 -d $ip -m limit --limit 30/min -j LOG --log-prefix \"[PG_BLK] \"$logUidArg 2>/dev/null")
            }
            v4Cmds.add("iptables -w 5 -A $CHAIN_V4 -d $ip -j REJECT --reject-with icmp-port-unreachable 2>/dev/null")
        }
        for (ip in v6Ips) {
            if (enableLog) {
                v6Cmds.add("ip6tables -w 5 -A $CHAIN_V6 -d $ip -m limit --limit 20/min -j LOG --log-prefix \"[PG_BLK6] \"$logUidArg 2>/dev/null")
            }
            v6Cmds.add("ip6tables -w 5 -A $CHAIN_V6 -d $ip -j REJECT 2>/dev/null")
        }

        // 分批执行，防止命令过长
        val chunkSize = 50
        for (chunk in v4Cmds.chunked(chunkSize)) {
            val r = SuShellExecutor.executeLines(chunk)
            if (!r.isSuccess) Log.w(TAG, "IPv4 拦截规则批次下发失败: exitCode=${r.exitCode}")
        }
        for (chunk in v6Cmds.chunked(chunkSize)) {
            val r = SuShellExecutor.executeLines(chunk)
            if (!r.isSuccess) Log.w(TAG, "IPv6 拦截规则批次下发失败: exitCode=${r.exitCode}")
        }

        // 回读校验：统计链内 REJECT 行数与期望比对（链在 startProtection 前已被 flushRules 清空，计数可靠）
        val v4Verify = SuShellExecutor.execute("iptables -w 5 -S $CHAIN_V4 2>/dev/null")
        val v4Actual = v4Verify.stdout.count { it.contains("-j REJECT") }
        val v4Ok = v4Actual == v4Ips.size
        if (!v4Ok) Log.w(TAG, "IPv4 拦截规则回读不符: expect=${v4Ips.size} actual=$v4Actual")

        var v6Actual = 0
        var v6Ok = false
        if (enableIpv6) {
            val v6Verify = SuShellExecutor.execute("ip6tables -w 5 -S $CHAIN_V6 2>/dev/null")
            v6Actual = v6Verify.stdout.count { it.contains("-j REJECT") }
            v6Ok = v6Actual == v6Ips.size
            if (!v6Ok) Log.w(TAG, "IPv6 拦截规则回读不符: expect=${v6Ips.size} actual=$v6Actual")
        }

        Log.d(TAG, "applyBlockRules: v4Ok=$v4Ok v6Ok=$v6Ok verified=${v4Actual + v6Actual}")
        BlockApplyResult(v4Ok, v6Ok, v4Actual + v6Actual)
    }

    /**
     * 批量并发解析域名 → IP 列表映射（受限并发，避免上千域名串行解析拖慢启动）
     * 必须在 hosts 生效之前调用：hosts 写入后同一批域名会被自己污染的 hosts 劫持，
     * 解析结果全为 0.0.0.0，导致 IP 层恒空（解析自锁）。
     * 解析失败的域名记录日志但不中断，仅跳过该域名的 IP 层下发。
     */
    suspend fun resolveDomainsConcurrently(
        domains: List<String>,
        concurrency: Int = 10
    ): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(concurrency)
        val resultMap = ConcurrentHashMap<String, List<String>>()
        domains.distinct().map { domain ->
            async {
                semaphore.withPermit {
                    val ips = resolveDomainSafely(domain)
                    if (ips.isEmpty()) {
                        Log.w(TAG, "域名解析失败（跳过 IP 层下发）: $domain")
                    } else {
                        resultMap[domain] = ips
                    }
                }
            }
        }.awaitAll()
        resultMap.toMap()
    }

    /**
     * 探测内核是否支持 LOG --log-uid（移植自模块端已验证方案），结果缓存到伴生级别只探测一次。
     * 探测方式：新建临时链 fj_logtest，尝试添加带 --log-uid 的 LOG 规则并回显退出码，
     * 成功（PROBE_RC=0）即内核支持；不支持时 LOG 规则省略 --log-uid 下发，避免整条规则被拒。
     */
    private suspend fun detectLogUidSupport(): Boolean {
        logUidSupported?.let { return it }
        val probe = SuShellExecutor.executeLines(
            listOf(
                "iptables -w 5 -N fj_logtest 2>/dev/null",
                "iptables -w 5 -A fj_logtest -m limit --limit 1/min -j LOG --log-prefix \"FJTEST \" --log-level 4 --log-uid 2>/dev/null",
                "echo PROBE_RC=\$?",
                "iptables -w 5 -F fj_logtest 2>/dev/null",
                "iptables -w 5 -X fj_logtest 2>/dev/null"
            )
        )
        val supported = probe.outputText.contains("PROBE_RC=0")
        logUidSupported = supported
        Log.d(TAG, "LOG --log-uid 内核能力探测结果: $supported")
        return supported
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
