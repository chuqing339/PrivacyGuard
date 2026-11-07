#!/system/bin/sh
# FJ HostsBlock v1.9 post-fs-data
# 职责：权限修正 + 由块表重建 hosts + busybox 可执行位

MODDIR=${0%/*}

# 先补可执行位，再 source common.sh（find_bb 依赖 busybox 能执行）
chmod 0755 "$MODDIR/bin/busybox" 2>/dev/null
chmod 0755 "$MODDIR/webroot/cgi-bin/control.sh" 2>/dev/null

. "$MODDIR/common.sh"

chmod 0644 "$HOSTS" 2>/dev/null
chown 0:0 "$HOSTS" 2>/dev/null
mkdir -p "$BAK_DIR" 2>/dev/null

rebuild_hosts

chmod 0644 "$HOSTS" 2>/dev/null
chown 0:0 "$HOSTS" 2>/dev/null
exit 0
