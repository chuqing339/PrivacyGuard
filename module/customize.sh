#!/system/bin/sh
SKIPUNZIP=0

ui_print "*******************************"
ui_print "  FJ HostsBlock v2.5"
ui_print "  上报端拦截 + 采集宿主管理"
ui_print "*******************************"

for f in system/etc/hosts hosts.base blocklist.txt blocklist.default.txt bin/busybox; do
  if [ ! -f "$MODPATH/$f" ]; then
    ui_print "! 缺 $f"
    abort "! 安装中止"
  fi
done

set_perm "$MODPATH/system/etc/hosts" 0 0 0644
set_perm "$MODPATH/hosts.base" 0 0 0644
set_perm "$MODPATH/blocklist.txt" 0 0 0644
set_perm "$MODPATH/blocklist.default.txt" 0 0 0644
[ -f "$MODPATH/whitelist.txt" ] && set_perm "$MODPATH/whitelist.txt" 0 0 0644

for f in post-fs-data.sh service.sh daemon.sh webserver.sh common.sh resolve_fallback.sh privacy.sh uninstall.sh customize.sh; do
  [ -f "$MODPATH/$f" ] && set_perm "$MODPATH/$f" 0 0 0755
done
[ -f "$MODPATH/webroot/cgi-bin/control.sh" ] && set_perm "$MODPATH/webroot/cgi-bin/control.sh" 0 0 0755
[ -f "$MODPATH/bin/busybox" ] && set_perm "$MODPATH/bin/busybox" 0 0 0755

if [ -f "$MODPATH/bin/busybox" ]; then
  "$MODPATH/bin/busybox" true 2>/dev/null && ui_print "- 内置 busybox 可执行" || ui_print "! 内置 busybox 无法执行（架构不符？）"
else
  ui_print "! 未内置 busybox，DNS 解析可能失效"
fi

BBX="$MODPATH/bin/busybox"
[ -x "$BBX" ] || BBX=/data/adb/magisk/busybox
if [ -x "$BBX" ] && "$BBX" true 2>/dev/null; then
  ui_print "- busybox: OK ($BBX)"
  if "$BBX" nslookup www.baidu.com 223.5.5.5 >/dev/null 2>&1; then
    ui_print "- DNS 探针: 通"
  else
    ui_print "! DNS 探针失败，可能装了 wingtech/内网环境"
  fi
else
  ui_print "! 无可用 busybox"
fi

ui_print "- 块表段落: $(grep -c '^\[' "$MODPATH/blocklist.txt") 个"
ui_print "- 块表域名: $(grep -cE '^[A-Za-z0-9_]' "$MODPATH/blocklist.txt") 条"

mkdir -p /data/adb/FJ_HostsBlock /data/adb/FJ_HostsBlock/backup
if [ ! -f /data/adb/FJ_HostsBlock/config ]; then
  cat > /data/adb/FJ_HostsBlock/config <<EOF
HOSTS_ENABLE=1
ENFORCE_ENABLE=1
IPV6_ENABLE=1
DNS_GUARD=1
LOG_ENABLE=1
WEBUI_PORT=8686
REFRESH_HOURS=6
SEG_TELEMETRY=1
SEG_ANTIFRAUD=1
SEG_RISKGATE=1
SEG_MIUIAD=1
SEG_DOH=1
SEG_ADSDK=1
WHITELIST_ENABLE=1
EOF
fi
[ -f /data/adb/FJ_HostsBlock/extra_hosts ] || : > /data/adb/FJ_HostsBlock/extra_hosts
chmod 0755 /data/adb/FJ_HostsBlock

if [ -x "$MODPATH/bin/busybox" ] && "$MODPATH/bin/busybox" ipset --help >/dev/null 2>&1; then
  ui_print "- ipset: 内置 busybox 可提供"
elif command -v ipset >/dev/null 2>&1; then
  ui_print "- ipset: 系统自带"
else
  ui_print "- ipset: 无，iptables 直写模式"
fi
command -v ip6tables >/dev/null 2>&1 && ui_print "- ip6tables 可用，IPv6 同步封" || ui_print "- 无 ip6tables，仅 IPv4"

# 升级场景：旧配置存在时先存一份，出问题可在 WebUI 备份页回退
if [ -f /data/adb/FJ_HostsBlock/config ]; then
  mkdir -p /data/adb/FJ_HostsBlock/backup 2>/dev/null
  PRE=/data/adb/FJ_HostsBlock/backup/pre-upgrade_$(date +%Y%m%d_%H%M%S).bak
  {
    echo "# FJ HostsBlock v1.4 EXPORT"
    echo "### CONFIG"
    cat /data/adb/FJ_HostsBlock/config 2>/dev/null
    echo "### EXTRA"
    cat /data/adb/FJ_HostsBlock/extra_hosts 2>/dev/null
    echo "### BLOCKLIST"
    cat /data/adb/modules/FJ_HostsBlock/blocklist.txt 2>/dev/null
    echo "### END"
  } > "$PRE" 2>/dev/null
  [ -s "$PRE" ] && ui_print "- 已存升级前备份: $(basename "$PRE")"
fi

ui_print "- WebUI: http://127.0.0.1:8686"
ui_print "- 块表可在线改，不用 adb"
ui_print "- 安装完成，重启生效"
