package com.privacyguard.app.core.firewall

import android.content.Context
import android.util.Log
import com.privacyguard.app.core.root.SuShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object HostsManager {

    private const val TAG = "HostsManager"
    private const val WORK_DIR = "/data/adb/privacy_guard"
    private const val TMP_HOSTS_PATH = "$WORK_DIR/hosts"
    private const val MODULE_HOSTS_PATH = "/data/adb/modules/FJ_HostsBlock/system/etc/hosts"

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

            // 2. 拼接屏蔽域名
            val sb = StringBuilder(baseContent)
            sb.append("\n# === PrivacyGuard Auto Generated Rules (${domains.size} domains) ===\n")
            for (domain in domains) {
                if (domain.isNotBlank() && !domain.startsWith("#")) {
                    sb.append("0.0.0.0 $domain\n")
                    sb.append(":: $domain\n")
                }
            }

            // 3. 准备目标写入脚本
            val commands = mutableListOf(
                "mkdir -p $WORK_DIR",
                "cat << 'EOF' > $TMP_HOSTS_PATH\n$sb\nEOF",
                "chmod 644 $TMP_HOSTS_PATH"
            )

            // 4. 如果有 Magisk / KernelSU 模块路径，同时写入模块以保证重启生效
            if (syncToModule) {
                commands.add("if [ -d /data/adb/modules/FJ_HostsBlock/system/etc ]; then")
                commands.add("  cp -f $TMP_HOSTS_PATH $MODULE_HOSTS_PATH")
                commands.add("  chmod 644 $MODULE_HOSTS_PATH")
                commands.add("fi")
            }

            // 5. 立即生效（尝试通过 mount --bind 挂载，或者重挂载系统分区）
            commands.add("mount -o bind $TMP_HOSTS_PATH /system/etc/hosts 2>/dev/null || mount -o bind $TMP_HOSTS_PATH /etc/hosts 2>/dev/null")

            val result = SuShellExecutor.executeLines(commands)
            Log.d(TAG, "applyHosts result: isSuccess=${result.isSuccess}")
            result.isSuccess
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
                "cat << 'EOF' > $TMP_HOSTS_PATH\n$baseContent\nEOF",
                "chmod 644 $TMP_HOSTS_PATH",
                "umount /system/etc/hosts 2>/dev/null",
                "umount /etc/hosts 2>/dev/null",
                "if [ -f $MODULE_HOSTS_PATH ]; then cp -f $TMP_HOSTS_PATH $MODULE_HOSTS_PATH; fi"
            )
            val res = SuShellExecutor.executeLines(commands)
            res.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "revertHosts error", e)
            false
        }
    }

    private fun readBaseHosts(context: Context): String {
        return try {
            context.assets.open("hosts.base").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "127.0.0.1 localhost\n::1 localhost\n"
        }
    }
}
