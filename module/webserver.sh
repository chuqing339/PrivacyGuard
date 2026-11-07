#!/system/bin/sh
# FJ HostsBlock v2.9 独立 WebUI 服务器
#
# 端口策略（书签友好）：
#   1. 8686 永远第一优先，书签不会失效
#   2. 如果 8686 已经被我们自己的实例正确服务（自检返回 JSON），直接沿用
#   3. 万一被迫用备用端口，下次轮询只要 8686 空出来就自动迁回
#   4. 候选端口逐个试：起得来 + CGI 自检通过，才算成功

MODDIR=${0%/*}
chmod 0755 "$MODDIR/bin/busybox" 2>/dev/null
. "$MODDIR/common.sh"

P0=$(conf_get WEBUI_PORT)
case "$P0" in ''|*[!0-9]*) P0=8686 ;; esac
PORTS="$P0 18686 28686 8687 8688 8787 9696"
LOCK="$RUN_DIR/.web.lock"
PORTFILE="$RUN_DIR/.web_port"
ERR="$RUN_DIR/.web.err"

self_ok() {
  # 探测指定端口是否正由“能跑我们 CGI”的服务托管
  [ -n "$BB" ] || return 1
  s=$("$BB" wget -q -O - "http://127.0.0.1:$1/cgi-bin/control.sh?act=status" 2>&1 | head -c 400 | tr -d '\r')
  case "$s" in *'"ok":true'*) return 0 ;; esac
  return 1
}

# ---------- 已运行实例的处理 ----------
CUR=$(cat "$PORTFILE" 2>/dev/null)
case "$CUR" in ''|*[!0-9]*) CUR= ;; esac
if [ -f "$LOCK" ]; then
  old=$(cat "$LOCK" 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    # 已经在首选端口上 → 不动，书签有效
    [ "$CUR" = "$P0" ] && exit 0
    # 在备用端口上，试探能不能迁回首选端口
    if self_ok "$P0"; then
      # 首选端口已被别的服务占着（不是我们），维持现状
      exit 0
    fi
    log "WebUI 迁回首选端口 $P0（原 $CUR）"
    kill "$old" 2>/dev/null
    sleep 1
  fi
  rm -f "$LOCK"
fi

if [ -z "$BB" ]; then log "无 busybox，WebUI 跳过"; exit 0; fi
if ! "$BB" httpd --help >/dev/null 2>&1; then log "busybox 无 httpd applet，WebUI 跳过"; exit 0; fi

WEB="$MODDIR/webroot"
[ -d "$WEB" ] || { log "webroot 不存在，WebUI 跳过"; exit 0; }

chmod 0755 "$MODDIR" "$WEB" "$WEB/cgi-bin" 2>/dev/null
find "$WEB" -type d -exec chmod 0755 {} \; 2>/dev/null
find "$WEB" -type f -exec chmod 0644 {} \; 2>/dev/null
chmod 0755 "$WEB/cgi-bin/control.sh" 2>/dev/null
printf '%s' "$MODDIR" > "$WEB/cgi-bin/.moddir" 2>/dev/null

cd "$WEB" || { log "无法进入 $WEB"; exit 0; }

# 首选端口已经能正常服务（比如上一次启动的实例还在），直接采用
if self_ok "$P0"; then
  echo "$P0" > "$PORTFILE"
  log "WebUI 复用已有服务 http://127.0.0.1:$P0"
  exit 0
fi

OK=0
for p in $PORTS; do
  : > "$ERR"
  # -f 必须：否则父进程 fork 后立即退出，$! 是死 pid
  # -u 必须：以 root 启动时默认降权 nobody，Android 无此用户会直接退出
  "$BB" httpd -f -p "127.0.0.1:$p" -h "$WEB" -u root:root >/dev/null 2>"$ERR" &
  pid=$!
  sleep 1

  if ! kill -0 "$pid" 2>/dev/null; then
    log "端口 $p 起不来（被占或无权限）：$(tr '\n' ' ' < "$ERR" 2>/dev/null | cut -c1-150)"
    continue
  fi

  if self_ok "$p"; then
    echo "$pid" > "$LOCK"
    echo "$p" > "$PORTFILE"
    if [ "$p" = "$P0" ]; then
      log "WebUI 已启动 http://127.0.0.1:$p（首选端口，书签有效）"
    else
      log "WebUI 已启动 http://127.0.0.1:$p（首选 $P0 被占，已改用备用端口，空闲时会自动迁回）"
    fi
    OK=1
    break
  fi

  s=$("$BB" wget -q -O - "http://127.0.0.1:$p/cgi-bin/control.sh?act=status" 2>&1 | head -c 120 | tr -d '\r')
  [ -n "$s" ] && log "端口 $p 自检异常：$s" || log "端口 $p 自检空响应（可能未编 CGI）"
  kill "$pid" 2>/dev/null
  sleep 1
done

if [ "$OK" != "1" ]; then
  rm -f "$PORTFILE" 2>/dev/null
  log "! WebUI 全部候选端口失败，浏览器方式不可用（管理器内置 WebUI 仍可用）"
fi
exit 0
