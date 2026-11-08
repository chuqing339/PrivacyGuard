#!/system/bin/sh
# FJ HostsBlock v2.6 卸载收尾
# 模块已是被动伴随组件，卸载时需要"动手"的只剩四件事：
#   1) 收掉历史版本（<= v2.5）遗留的本地网页服务 / 刷新守护进程与残锁
#   2) 清理旧版写入的 iptables/ip6tables 链与 ipset 集合
#   3) 按旧版记录恢复被禁用 / 限制的包
#   4) 删除 /data/adb/FJ_HostsBlock 数据目录
# PrivacyGuard App 自己的记录（/data/adb/privacy_guard/ 下）由 App 的卸载流程处理，
# 本脚本不碰；App 卸载由 Android 系统负责。

MODDIR=/data/adb/modules/FJ_HostsBlock
RUN_DIR=/data/adb/FJ_HostsBlock

# ---------- 1) 旧版遗留进程 ----------
# busybox 网页服务进程与旧版脚本的命令行里都含模块路径，但 "httpd" 可能在模块路径之前；
# 关键词两种顺序都要兜住。收完进程再清锁、删目录。
for d in /proc/[0-9]*; do
  [ -r "$d/cmdline" ] || continue
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null) || continue
  case "$c" in
    *FJ_HostsBlock*)
      case "$c" in
        *httpd*|*daemon.sh*|*webserver.sh*) kill "${d#/proc/}" 2>/dev/null ;;
      esac
      ;;
  esac
done
for l in "$RUN_DIR/.web.lock" "$RUN_DIR/.daemon.lock" "$RUN_DIR/.lock" \
         "$RUN_DIR/run/.web.lock" "$RUN_DIR/run/.daemon.lock"; do
  [ -f "$l" ] || continue
  p=$(cat "$l" 2>/dev/null)
  case "$p" in
    ''|*[!0-9]*) : ;;
    *) kill "$p" 2>/dev/null ;;
  esac
  rm -f "$l" 2>/dev/null
done

# ---------- 2) iptables / ip6tables 残留链 ----------
# fj_blk_out  / fj_blk6_out：旧版 IPv4/IPv6 拦截链
# fj_wl_out  ：旧版白名单放行链（挂在 fj_blk_out 下，保险起见 OUTPUT 引用也清一遍）
# fj_blk_dns ：旧版 DNS 兜底链
for ch in fj_blk_out fj_blk6_out fj_wl_out fj_blk_dns; do
  iptables  -D OUTPUT -j "$ch" 2>/dev/null
  iptables  -F "$ch" 2>/dev/null
  iptables  -X "$ch" 2>/dev/null
  ip6tables -D OUTPUT -j "$ch" 2>/dev/null
  ip6tables -F "$ch" 2>/dev/null
  ip6tables -X "$ch" 2>/dev/null
done

# ---------- 3) ipset 集合销毁 ----------
# 旧版可能用模块自带 busybox 建的集合，系统 ipset 销毁不了；
# 优先用模块自带 busybox，失败回退系统 ipset。
BB=""
[ -x "$MODDIR/bin/busybox" ] && "$MODDIR/bin/busybox" true 2>/dev/null && BB="$MODDIR/bin/busybox"
for s in fj_blk fj_blk6; do
  if [ -n "$BB" ]; then
    "$BB" ipset destroy "$s" 2>/dev/null && continue
  fi
  ipset destroy "$s" 2>/dev/null
done

# ---------- 4) 恢复被本模块（旧 privacy 页）禁用/限制的包 ----------
# 旧版把动过的包记在 /data/adb/FJ_HostsBlock/ 下：
#   .disabled_pkgs    — pm disable-user 禁用的包
#   .restricted_<pkg> — appops 限制的包
# 不恢复的话，模块卸载后这些包会永远留在禁用/受限状态。
DL="$RUN_DIR/.disabled_pkgs"
if [ -f "$DL" ]; then
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    pm enable --user 0 "$p" >/dev/null 2>&1
  done < "$DL"
  rm -f "$DL" 2>/dev/null
fi
for f in "$RUN_DIR"/.restricted_*; do
  [ -f "$f" ] || continue
  p=$(basename "$f" | sed 's/^\.restricted_//')
  if [ -n "$p" ]; then
    for op in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK START_FOREGROUND FINE_LOCATION COARSE_LOCATION READ_PHONE_STATE GET_USAGE_STATS READ_SMS; do
      cmd appops set "$p" "$op" allow >/dev/null 2>&1
    done
  fi
  rm -f "$f" 2>/dev/null
done

# ---------- 5) 数据目录整删 ----------
rm -rf "$RUN_DIR" 2>/dev/null

# hosts 随模块目录移除自动回退系统原文件
exit 0
