package com.privacyguard.app.core.firewall

import android.content.Context
import android.util.Base64
import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HostsManager {

    private const val TAG = "HostsManager"
    private const val WORK_DIR = "/data/adb/privacy_guard"
    private const val TMP_HOSTS_PATH = "$WORK_DIR/hosts"
    private const val MODULE_HOSTS_PATH = "/data/adb/modules/FJ_HostsBlock/system/etc/hosts"

    // 本会话成功 bind mount 的标记文件：mount 成功创建、umount 成功删除。
    // revert 时仅当标记存在才卸载，避免误拆 Magisk 模块 magic mount 的下位 hosts 层
    private const val HOSTS_APPLIED_MARKER = "$WORK_DIR/.hosts_applied"

    // 域名强校验：左右锚定，仅放行合法主机名（允许下划线的 DNS SP 记录风格），
    // 从源头杜绝 shell 元字符注入与伪域名进入 hosts
    private val DOMAIN_REGEX = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*\\.[A-Za-z0-9]+$")
    private const val DOMAIN_MAX_LENGTH = 253

    fun isValidDomain(domain: String): Boolean =
        domain.isNotEmpty() && domain.length <= DOMAIN_MAX_LENGTH && DOMAIN_REGEX.matches(domain)

    /**
     * 生成并应用 hosts 文件
     * @param domains 活跃的拦截域名列表
     * @param syncToModule 是否同步写入 Magisk/KernelSU 模块目录
     */
    suspend fun applyHosts(
        context: Context,
        domains: List<String>,
        syncToModule: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 读取基础 hosts
            val baseContent = readBaseHosts(context)

            // 2. 域名写入前强校验：不合法跳过并告警，绝不进入 shell
            val validDomains = mutableListOf<String>()
            for (domain in domains) {
                val d = domain.trim()
                when {
                    d.isEmpty() || d.startsWith("#") -> {}
                    isValidDomain(d) -> validDomains.add(d)
                    else -> Log.w(TAG, "跳过非法域名: $d")
                }
            }

            // 3. 准备写入命令：
            //    - 基础内容体量小且来源受控（APK assets），用 base64 管道写入；
            //    - 屏蔽行逐条 printf 追加（域名已过强校验，无引号/元字符风险）。
            //    彻底替代 cat << 'EOF' heredoc，消除定界符冲突与内容注入风险
            val commands = mutableListOf(
                "mkdir -p $WORK_DIR",
                buildBase64WriteCommand(baseContent),
                "printf '\\n# === PrivacyGuard Auto Generated Rules (${validDomains.size} domains) ===\\n' >> $TMP_HOSTS_PATH"
            )
            for (domain in validDomains) {
                commands.add("printf '%s\\n' \"0.0.0.0 $domain\" >> $TMP_HOSTS_PATH")
                // 保留 :: 行的取舍说明：:: 是合法的 IPv6 未指定地址（语义等价于 IPv4 的 0.0.0.0），
                // bionic/glibc 的 getaddrinfo 会把它当作有效地址返回，从而阻断 AAAA 解析；
                // 且域名已通过强校验、行格式确定，主流解析器不会将该行判为无效行丢弃，故保留
                commands.add("printf '%s\\n' \":: $domain\" >> $TMP_HOSTS_PATH")
            }
            commands.add("chmod 644 $TMP_HOSTS_PATH")

            // 4. 如果有 Magisk / KernelSU 模块路径，同时写入模块以保证重启生效
            if (syncToModule) {
                commands.add("if [ -d /data/adb/modules/FJ_HostsBlock/system/etc ]; then")
                commands.add("  cp -f $TMP_HOSTS_PATH $MODULE_HOSTS_PATH")
                commands.add("  chmod 644 $MODULE_HOSTS_PATH")
                commands.add("fi")
            }

            // 5. 防叠层：先 best-effort 卸载旧 bind mount（本应用上一次挂载在最上层），
            //    再重新挂载，避免多次 applyHosts 叠出多层 mount
            commands.add("umount /system/etc/hosts 2>/dev/null")
            commands.add("umount /etc/hosts 2>/dev/null")
            commands.add("mount -o bind $TMP_HOSTS_PATH /system/etc/hosts 2>/dev/null || mount -o bind $TMP_HOSTS_PATH /etc/hosts 2>/dev/null")
            // 挂载确实生效（/proc/mounts 出现本文件作为挂载源）才创建标记
            commands.add("grep -q '$TMP_HOSTS_PATH' /proc/mounts && touch $HOSTS_APPLIED_MARKER")

            SuShellExecutor.executeLines(commands)

            // 以标记文件作为「本会话已挂载」的权威判定，而非批次退出码
            val check = SuShellExecutor.execute("test -f $HOSTS_APPLIED_MARKER && echo APPLIED")
            val applied = check.outputText.contains("APPLIED")
            Log.d(TAG, "applyHosts applied=$applied domains=${validDomains.size}")
            applied
        } catch (e: Exception) {
            Log.e(TAG, "applyHosts error", e)
            false
        }
    }

    /**
     * 恢复默认原始 hosts
     */
    suspend fun revertHosts(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseContent = readBaseHosts(context)
            val commands = listOf(
                "mkdir -p $WORK_DIR",
                buildBase64WriteCommand(baseContent),
                "chmod 644 $TMP_HOSTS_PATH",
                // 仅在本会话确实 mount 过（标记存在）时才卸载；
                // 卸载后确认挂载源已消失再删标记，umount 未生效则保留标记等待下次清理
                buildGuardedUmountCommand(),
                "if [ -f $MODULE_HOSTS_PATH ]; then cp -f $TMP_HOSTS_PATH $MODULE_HOSTS_PATH; fi"
            )
            val res = SuShellExecutor.executeLines(commands)
            res.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "revertHosts error", e)
            false
        }
    }

    /**
     * 卸载本应用挂载的 hosts（带标记守护，仅当本会话确实 mount 过才执行）
     * 供 startProtection 在 DNS 预解析前调用：重新应用防护时先撤下上一轮的
     * 0.0.0.0 hosts，避免预解析再次被残留 hosts 污染（解析自锁的重复触发场景）
     */
    suspend fun unmountHosts(): Boolean = withContext(Dispatchers.IO) {
        // 单条单行命令，标记不存在时为空操作（if 条件不成立）
        val res = SuShellExecutor.execute(buildGuardedUmountCommand())
        res.isSuccess
    }

    /**
     * 生成「仅当本会话已挂载才卸载」的 shell 命令（单行）
     */
    private fun buildGuardedUmountCommand(): String =
        "if [ -f $HOSTS_APPLIED_MARKER ]; then umount /system/etc/hosts 2>/dev/null; umount /etc/hosts 2>/dev/null; grep -q '$TMP_HOSTS_PATH' /proc/mounts || rm -f $HOSTS_APPLIED_MARKER; fi"

    /**
     * 生成 base64 管道写入命令：内容不经过 shell 解析，替代 heredoc 定界符方案
     * （base64 字符集 A-Za-z0-9+/= 不含引号与 shell 元字符，单引号包裹安全）
     */
    private fun buildBase64WriteCommand(content: String): String {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "echo '$b64' | base64 -d > $TMP_HOSTS_PATH"
    }

    private fun readBaseHosts(context: Context): String {
        return try {
            context.assets.open("hosts.base").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "127.0.0.1 localhost\n::1 localhost\n"
        }
    }
}
