#!/system/bin/sh
# FJ HostsBlock v1.9 周期刷新守护：定期重解 DNS，跟上 CDN 换 IP
# 由 service.sh 在后台拉起，单实例

MODDIR=${0%/*}
. "$MODDIR/common.sh"

LOCK="$RUN_DIR/.daemon.lock"

if [ -f "$LOCK" ]; then
  old=$(cat "$LOCK" 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    log "守护已在运行 pid=$old，退出本次"
    exit 0
  fi
fi

echo $$ > "$LOCK"
log "守护启动 pid=$$"

# 循环粒度固定 1 分钟：每分钟做一次日志轮转，每 REFRESH_HOURS 小时做一次 refresh。
# 这样日志不会无限增长，且即使无活动/无请求也能定时清理。
h=$(conf_get REFRESH_HOURS)
case "$h" in ''|*[!0-9]*) h=6 ;; esac
[ "$h" -lt 1 ] && h=1
REFRESH_SEC=$((h * 3600))
tick=0; last=0
while true; do
  sleep 60
  tick=$((tick + 1))
  # 每 30 分钟轮转一次日志（轻量，只 wc + 必要时 tail）
  if [ $((tick % 30)) -eq 0 ]; then rotate_logs; fi
  # 到间隔点做 refresh（内部重建 hosts + 重解 IP + DNS 兜底）
  if [ $((tick - last)) -ge $((REFRESH_SEC / 60)) ]; then
    "$MODDIR/service.sh" refresh >/dev/null 2>&1
    rotate_logs
    last=$tick
    log "周期刷新完成（间隔 ${h}h）"
  fi
done
