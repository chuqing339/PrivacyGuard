package com.privacyguard.app.data.preference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "privacy_guard_settings")

/**
 * 防护全局配置模型
 * masterEnabled / bootAutoStart 默认 false：首装后未打开过 App 的设备重启，
 * 不应在用户毫不知情时自动拉起 root 防火墙
 */
data class GuardConfig(
    val masterEnabled: Boolean = false,
    val hostsEnabled: Boolean = true,
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = true,
    val dnsGuardEnabled: Boolean = true,
    val whitelistEnabled: Boolean = true,
    val logEnabled: Boolean = true,
    val bootAutoStart: Boolean = false,
    val lastRefreshTime: Long = 0L,
    val moduleSyncMode: Boolean = true // 是否同步将规则生成至 Magisk/KernelSU 模块目录
)

class SettingsDataStore(private val context: Context) {

    private object PreferencesKeys {
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val HOSTS_ENABLED = booleanPreferencesKey("hosts_enabled")
        val IPV4_ENABLED = booleanPreferencesKey("ipv4_enabled")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val DNS_GUARD_ENABLED = booleanPreferencesKey("dns_guard_enabled")
        val WHITELIST_ENABLED = booleanPreferencesKey("whitelist_enabled")
        val LOG_ENABLED = booleanPreferencesKey("log_enabled")
        val BOOT_AUTO_START = booleanPreferencesKey("boot_auto_start")
        val LAST_REFRESH_TIME = longPreferencesKey("last_refresh_time")
        val MODULE_SYNC_MODE = booleanPreferencesKey("module_sync_mode")
        // dmesg 非清除式读取的水位线：已处理的最大内核时间戳（开机起算的秒数）
        val DMESG_WATERMARK = doublePreferencesKey("dmesg_kernel_watermark")
    }

    val configFlow: Flow<GuardConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            GuardConfig(
                masterEnabled = preferences[PreferencesKeys.MASTER_ENABLED] ?: false,
                hostsEnabled = preferences[PreferencesKeys.HOSTS_ENABLED] ?: true,
                ipv4Enabled = preferences[PreferencesKeys.IPV4_ENABLED] ?: true,
                ipv6Enabled = preferences[PreferencesKeys.IPV6_ENABLED] ?: true,
                dnsGuardEnabled = preferences[PreferencesKeys.DNS_GUARD_ENABLED] ?: true,
                whitelistEnabled = preferences[PreferencesKeys.WHITELIST_ENABLED] ?: true,
                logEnabled = preferences[PreferencesKeys.LOG_ENABLED] ?: true,
                bootAutoStart = preferences[PreferencesKeys.BOOT_AUTO_START] ?: false,
                lastRefreshTime = preferences[PreferencesKeys.LAST_REFRESH_TIME] ?: 0L,
                moduleSyncMode = preferences[PreferencesKeys.MODULE_SYNC_MODE] ?: true
            )
        }

    suspend fun setMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.MASTER_ENABLED] = enabled }
    }

    suspend fun setHostsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.HOSTS_ENABLED] = enabled }
    }

    suspend fun setIpv4Enabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IPV4_ENABLED] = enabled }
    }

    suspend fun setIpv6Enabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IPV6_ENABLED] = enabled }
    }

    suspend fun setDnsGuardEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DNS_GUARD_ENABLED] = enabled }
    }

    suspend fun setWhitelistEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.WHITELIST_ENABLED] = enabled }
    }

    suspend fun setLogEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.LOG_ENABLED] = enabled }
    }

    suspend fun setBootAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BOOT_AUTO_START] = enabled }
    }

    suspend fun updateLastRefreshTime(timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[PreferencesKeys.LAST_REFRESH_TIME] = timestamp }
    }

    suspend fun setModuleSyncMode(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.MODULE_SYNC_MODE] = enabled }
    }

    /**
     * 读取 dmesg 水位线（已处理的最大内核时间戳，开机秒数；首次为 0）
     */
    suspend fun dmesgWatermark(): Double = try {
        context.dataStore.data.first()[PreferencesKeys.DMESG_WATERMARK] ?: 0.0
    } catch (_: IOException) {
        0.0
    }

    /**
     * 持久化 dmesg 水位线，App 进程重启后据此跳过已入库的内核日志，避免重复采集
     */
    suspend fun setDmesgWatermark(value: Double) {
        context.dataStore.edit { it[PreferencesKeys.DMESG_WATERMARK] = value }
    }
}
