package com.privacyguard.app.core.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val isSuccess: Boolean get() = exitCode == 0
    val outputText: String get() = stdout.joinToString("\n")
}

object SuShellExecutor {

    private const val TAG = "SuShellExecutor"

    init {
        // 配置全局单例 Root Shell 参数：合并 stderr，默认超时 20 秒
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
    }

    /**
     * 检测设备是否已取得有效 ROOT 权限
     * 使用 libsu 单例 shell 缓存，避免重复触发授权弹窗
     */
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Shell.isAppGrantedRoot() == true) return@withContext true
            val shell = Shell.getShell()
            shell.isRoot
        } catch (e: Exception) {
            Log.e(TAG, "checkRoot failed", e)
            false
        }
    }

    fun isRootGranted(): Boolean = Shell.isAppGrantedRoot() == true

    /**
     * 检测 Root 方案类型 (Magisk / KernelSU / APatch / 普通 su)
     */
    suspend fun detectRootFlavor(): String = withContext(Dispatchers.IO) {
        try {
            if (!checkRoot()) return@withContext "None"

            val ksuCheck = execute("test -e /data/adb/ksu && echo KSU")
            if (ksuCheck.outputText.contains("KSU")) return@withContext "KernelSU"

            val apCheck = execute("test -e /data/adb/ap && echo APATCH")
            if (apCheck.outputText.contains("APATCH")) return@withContext "APatch"

            val magiskCheck = execute("which magisk && magisk -v")
            if (magiskCheck.isSuccess && magiskCheck.outputText.isNotEmpty()) {
                return@withContext "Magisk (${magiskCheck.stdout.firstOrNull() ?: ""})"
            }

            return@withContext "Standard SU"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * 执行单条 shell 命令
     */
    suspend fun execute(command: String): CommandResult = withContext(Dispatchers.IO) {
        executeLines(listOf(command))
    }

    /**
     * 批量执行多条指令，全部运行在全局唯一的 Root Shell 会话中，绝不重复启动 su 进程
     */
    suspend fun executeLines(commands: List<String>): CommandResult = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(*commands.toTypedArray()).exec()
            CommandResult(
                exitCode = result.code,
                stdout = result.out,
                stderr = result.err
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command execution error: ${commands.firstOrNull()}", e)
            CommandResult(-1, emptyList(), listOf(e.message ?: "Execution failed"))
        }
    }
}
