package com.privacyguard.app.core.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

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

    /**
     * 检测设备是否已取得有效 ROOT 权限
     */
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execute("id")
            result.isSuccess && result.stdout.any { it.contains("uid=0") }
        } catch (e: Exception) {
            Log.e(TAG, "checkRoot failed", e)
            false
        }
    }

    /**
     * 检测 Root 方案类型 (Magisk / KernelSU / APatch / 普通 su)
     */
    suspend fun detectRootFlavor(): String = withContext(Dispatchers.IO) {
        try {
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
     * 执行单条或复合 shell 命令
     */
    suspend fun execute(command: String, timeoutMs: Long = 15000): CommandResult = withContext(Dispatchers.IO) {
        executeLines(listOf(command), timeoutMs)
    }

    /**
     * 批量执行多条指令
     */
    suspend fun executeLines(commands: List<String>, timeoutMs: Long = 20000): CommandResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val isReader = BufferedReader(InputStreamReader(process.inputStream))
            val esReader = BufferedReader(InputStreamReader(process.errorStream))

            for (cmd in commands) {
                os.writeBytes(cmd + "\n")
            }
            os.writeBytes("exit\n")
            os.flush()

            val outThread = Thread {
                try {
                    var line: String?
                    while (isReader.readLine().also { line = it } != null) {
                        line?.let { stdout.add(it) }
                    }
                } catch (_: Exception) {}
            }

            val errThread = Thread {
                try {
                    var line: String?
                    while (esReader.readLine().also { line = it } != null) {
                        line?.let { stderr.add(it) }
                    }
                } catch (_: Exception) {}
            }

            outThread.start()
            errThread.start()

            outThread.join(timeoutMs)
            errThread.join(timeoutMs)
            val exitCode = process.waitFor()

            CommandResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution error: ${commands.firstOrNull()}", e)
            CommandResult(-1, stdout, stderr + (e.message ?: "Execution failed"))
        } finally {
            process?.destroy()
        }
    }
}
