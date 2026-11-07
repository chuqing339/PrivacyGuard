#!/system/bin/sh
# FJ HostsBlock v2.7 隐私体检 / 采集宿主管理
#
# 三类：
#   SAFE  可自由开关，低风险（不影响任何 App 启动）
#   OPT   可选优化，需手动开启（纯采集组件，无 UI 功能）
#   RISK  高风险，不建议禁用（开屏广告接口宿主）
#
# 所有禁用动作都记入 .disabled_pkgs，卸载模块时自动恢复。

MODDIR=${0%/*}
. "$MODDIR/common.sh"

PKG_SAFE="com.miui.msa com.miui.analytics"
PKG_OPT="com.mobiletools.systemhelper"
# 智能服务 com.miui.systemAdSolution 刻意不列入管理范围：
# 它是开屏广告的接口宿主，禁用后浏览器/应用商店/主题商店启动会等它超时，
# 卡在启动图很久。关广告靠域名屏蔽（块表已封 ad.xiaomi.com 等）即够，不动服务。
PKG_LIST="$PKG_SAFE $PKG_OPT"

DISABLED_LIST="$RUN_DIR/.disabled_pkgs"
APPOPS="RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK START_FOREGROUND FINE_LOCATION COARSE_LOCATION READ_PHONE_STATE GET_USAGE_STATS READ_SMS"

PROC_KEYS="securitycenter guardprovider systemhelper systemAdSolution analytics msa"

pkg_label() {
  case "$1" in
    com.mobiletools.systemhelper) echo "SystemHelper（联通终端遥测）" ;;
    com.miui.systemAdSolution)    echo "智能服务（开屏广告宿主）" ;;
    com.miui.msa)                 echo "MSA 广告标识服务" ;;
    com.miui.analytics)           echo "小米统计" ;;
    *) echo "$1" ;;
  esac
}

pkg_kind() {
  case " $PKG_SAFE " in *" $1 "*) echo safe; return ;; esac
  case " $PKG_OPT "  in *" $1 "*) echo opt;  return ;; esac
  case " $PKG_RISK " in *" $1 "*) echo risk; return ;; esac
  echo unknown
}

pkg_warn() {
  case "$1" in
    com.miui.systemAdSolution)
      echo "禁用后浏览器/应用商店/主题商店开屏会卡很久（它们在等这个服务回包）。关广告用域名屏蔽就够，不建议禁用。" ;;
    com.mobiletools.systemhelper)
      echo "联通预装采集探针，无界面无功能，禁用无副作用；仅影响运营商 OTA / SIM 远程配置。可随时启用。" ;;
    *) echo "" ;;
  esac
}

# enabled / disabled / restricted / absent
pkg_state() {
  p=$1
  if pm list packages -d --user 0 2>/dev/null | grep -q "^package:$p$"; then echo disabled; return; fi
  if ! pm list packages --user 0 2>/dev/null | grep -q "^package:$p$"; then echo absent; return; fi
  if [ -f "$RUN_DIR/.restricted_$p" ]; then echo restricted; return; fi
  echo enabled
}

# 记录本模块动过的包，卸载时恢复
track_off() {
  p=$1
  [ -f "$DISABLED_LIST" ] || : > "$DISABLED_LIST"
  grep -qxF "$p" "$DISABLED_LIST" 2>/dev/null || echo "$p" >> "$DISABLED_LIST"
}
track_on() {
  p=$1
  [ -f "$DISABLED_LIST" ] || return 0
  grep -vxF "$p" "$DISABLED_LIST" > "$DISABLED_LIST.tmp" 2>/dev/null && mv -f "$DISABLED_LIST.tmp" "$DISABLED_LIST"
}

pkg_json() {
  p=$1
  st=$(pkg_state "$p")
  pids=$(pidof "$p" 2>/dev/null | tr ' ' ',' | cut -c1-60)
  printf '{"pkg":%s,"label":%s,"state":%s,"pid":%s,"kind":%s,"warn":%s}' \
    "$(jstr "$p")" "$(jstr "$(pkg_label "$p")")" "$(jstr "$st")" "$(jstr "$pids")" \
    "$(jstr "$(pkg_kind "$p")")" "$(jstr "$(pkg_warn "$p")")"
}

procs_json() {
  s=""
  for k in $PROC_KEYS; do
    pids=$(pidof "$k" 2>/dev/null | tr ' ' ',')
    [ -n "$pids" ] || continue
    [ -n "$s" ] && s="$s,"
    s="$s{\"name\":$(jstr "$k"),\"pid\":$(jstr "$pids")}"
  done
  printf '[%s]' "$s"
}

xsof_json() { printf '{"state":%s}' "$(jstr "$(pkg_state io.github.yunyizea.xsofprivacy)")"; }

case "$1" in
  scan)
    printf '{"ok":true,"pkgs":['
    first=1
    for p in $PKG_LIST; do
      [ "$first" = "1" ] || printf ','; first=0
      pkg_json "$p"
    done
    printf '],"procs":%s,"xsof":%s}\n' "$(procs_json)" "$(xsof_json)"
    ;;
  toggle)
    pkg=$2; on=$3
    case " $PKG_LIST " in *" $pkg "*) : ;; *) echo '{"ok":false,"msg":"不允许操作该包名"}'; exit 0 ;; esac
    case "$on" in
      0)
        out=$(pm disable-user --user 0 "$pkg" 2>&1); am force-stop "$pkg" >/dev/null 2>&1
        st=$(pkg_state "$pkg")
        if [ "$st" = "disabled" ]; then track_off "$pkg"; log "隐私：$pkg 已禁用"; printf '{"ok":true,"msg":"已禁用","pkg":%s}\n' "$(jstr "$pkg")"
        else printf '{"ok":false,"msg":%s}\n' "$(jstr "禁用失败：$out")"; fi
        ;;
      2)
        # 限制模式：保留安装（可更新/可卸载），但冻结后台与敏感权限
        out=$(pm enable --user 0 "$pkg" 2>&1)
        for op in $APPOPS; do cmd appops set "$pkg" "$op" ignore >/dev/null 2>&1; done
        am force-stop "$pkg" >/dev/null 2>&1
        : > "$RUN_DIR/.restricted_$pkg" 2>/dev/null
        track_off "$pkg"
        log "隐私：$pkg 已限制（appops）"
        printf '{"ok":true,"msg":"已限制（保留安装）","pkg":%s}\n' "$(jstr "$pkg")"
        ;;
      *)
        pm enable --user 0 "$pkg" >/dev/null 2>&1
        for op in $APPOPS; do cmd appops set "$pkg" "$op" allow >/dev/null 2>&1; done
        rm -f "$RUN_DIR/.restricted_$pkg" 2>/dev/null
        track_on "$pkg"
        st=$(pkg_state "$pkg")
        if [ "$st" = "enabled" ]; then log "隐私：$pkg 已恢复"; printf '{"ok":true,"msg":"已启用","pkg":%s}\n' "$(jstr "$pkg")"
        else printf '{"ok":false,"msg":%s}\n' "$(jstr "启用失败")"; fi
        ;;
    esac
    ;;
  *)
    echo "usage: privacy.sh scan | toggle <pkg> <0禁用|1启用|2限制>"
    ;;
esac
