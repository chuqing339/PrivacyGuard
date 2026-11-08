#!/system/bin/sh
# FJ HostsBlock v2.6 post-fs-data — 被动伴随模式
# hosts 不再由脚本重建：system/etc/hosts 由 Magisk / KernelSU 的 magic mount
# 在开机阶段自动 systemless 挂载，内容全部由 PrivacyGuard App 运行时写入更新。
# 本脚本只确保引导文件存在且权限正确，并留一行日志。

MODDIR=${0%/*}
HOSTS="$MODDIR/system/etc/hosts"

if [ ! -f "$HOSTS" ]; then
  mkdir -p "${HOSTS%/*}" 2>/dev/null
  printf '127.0.0.1 localhost\n::1 localhost\n' > "$HOSTS" 2>/dev/null
fi
chmod 0644 "$HOSTS" 2>/dev/null
chown 0:0 "$HOSTS" 2>/dev/null

log -t FJ_HostsBlock "post-fs-data: hosts 引导文件就绪（被动伴随模式，规则由 App 管理）" 2>/dev/null
exit 0
