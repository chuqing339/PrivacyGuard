#!/system/bin/sh
# FJ HostsBlock v2.6 — 被动伴随模式（late_start service）
# 规则执行（iptables/ipset/hosts/应用冻结）已全部移交 PrivacyGuard App（com.privacyguard.app）。
# 本模块只提供 systemless hosts 引导文件（system/etc/hosts，开机由 magic mount 自动挂载，
# 内容由 App 运行时更新）。本脚本不再调用任何规则执行路径，也不启动任何服务。

MODDIR=${0%/*}
RUN_DIR=/data/adb/FJ_HostsBlock

say() {
  log -t FJ_HostsBlock "$1" 2>/dev/null
  echo "FJ_HostsBlock: $1"
}

# 清理历史版本（<= v2.5）遗留的本地网页服务进程、刷新守护进程与残锁。
# 升级场景下用户设备上旧进程可能还活着（模块文件已被覆盖，进程仍在内存里跑）：
# 旧版用自带 busybox 起了监听 127.0.0.1 的无鉴权 root 网页服务，另有一个常驻刷新循环。
# 新版模块里已无对应脚本，必须在这里收掉，不能留到重启前一直挂着。
cleanup_legacy() {
  # 1) 残锁：旧版把 PID 锁文件写在 RUN_DIR 根目录（个别版本在 run/ 子目录）
  for l in "$RUN_DIR/.web.lock" "$RUN_DIR/.daemon.lock" \
           "$RUN_DIR/run/.web.lock" "$RUN_DIR/run/.daemon.lock"; do
    [ -f "$l" ] || continue
    p=$(cat "$l" 2>/dev/null)
    case "$p" in
      ''|*[!0-9]*) : ;;
      *) [ "$p" != "$$" ] && kill "$p" 2>/dev/null ;;
    esac
    rm -f "$l" 2>/dev/null
  done
  # 2) 运行中的旧进程：busybox 网页服务的命令行里 "httpd" 在前、模块路径在后；
  #    旧脚本由 sh 解释执行，命令行里模块路径在前、脚本名在后。两种顺序都兜住。
  for d in /proc/[0-9]*; do
    [ -r "$d/cmdline" ] || continue
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null) || continue
    case "$c" in
      *FJ_HostsBlock*)
        case "$c" in
          *httpd*|*daemon.sh*|*webserver.sh*)
            p=${d#/proc/}
            [ "$p" != "$$" ] && kill "$p" 2>/dev/null
            ;;
        esac
        ;;
    esac
  done
  # 3) 旧版网页服务的端口/错误记录文件
  rm -f "$RUN_DIR/.web_port" "$RUN_DIR/.web.err" 2>/dev/null
}

say "PrivacyGuard 模块处于被动伴随模式，规则由 App 管理"
cleanup_legacy
exit 0
