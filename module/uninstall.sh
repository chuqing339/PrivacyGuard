#!/system/bin/sh
# FJ HostsBlock v1.4 卸载收尾：清链、停守护、释放 WebUI 端口
for f in /data/adb/modules/FJ_HostsBlock/service.sh; do
  [ -x "$f" ] && "$f" stop >/dev/null 2>&1
done
for l in /data/adb/FJ_HostsBlock/.daemon.lock /data/adb/FJ_HostsBlock/.web.lock /data/adb/FJ_HostsBlock/.lock; do
  [ -f "$l" ] && { kill "$(cat "$l" 2>/dev/null)" 2>/dev/null; rm -f "$l"; }
done
# 清 iptables/ip6tables 残留链，防卸载后断网
for ch in fj_blk_out fj_blk6_out; do
  iptables   -D OUTPUT -j $ch 2>/dev/null; iptables   -F $ch 2>/dev/null; iptables   -X $ch 2>/dev/null
  ip6tables  -D OUTPUT -j $ch 2>/dev/null; ip6tables  -F $ch 2>/dev/null; ip6tables  -X $ch 2>/dev/null
done
command -v ipset >/dev/null 2>&1 && { ipset destroy fj_blk 2>/dev/null; ipset destroy fj_blk6 2>/dev/null; }
# 配置、块表备份与日志保留（方便重装后恢复）；要彻底清就手动删 /data/adb/FJ_HostsBlock
rm -f /data/adb/FJ_HostsBlock/.ipset_mode /data/adb/FJ_HostsBlock/.ipset_mode6 /data/adb/FJ_HostsBlock/.hosts.new 2>/dev/null

# 恢复被本模块禁用的系统包。
# 不恢复的话，模块卸载后那些包会被永久留着禁用状态，用户根本不知道。
DL=/data/adb/FJ_HostsBlock/.disabled_pkgs
if [ -f "$DL" ]; then
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    pm enable --user 0 "$p" >/dev/null 2>&1
  done < "$DL"
  rm -f "$DL" 2>/dev/null
fi
# 清掉限制模式标记对应的 appops 也恢复一遍
for f in /data/adb/FJ_HostsBlock/.restricted_*; do
  [ -f "$f" ] || continue
  p=$(basename "$f" | sed 's/^\.restricted_//')
  if [ -n "$p" ]; then
    for op in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK START_FOREGROUND FINE_LOCATION COARSE_LOCATION READ_PHONE_STATE GET_USAGE_STATS READ_SMS; do
      cmd appops set "$p" "$op" allow >/dev/null 2>&1
    done
  fi
  rm -f "$f" 2>/dev/null
done

# hosts 随模块目录移除自动回退系统原文件
exit 0
