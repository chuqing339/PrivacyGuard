#!/system/bin/sh
# FJ HostsBlock WebUI 后端 v1.9
#
# 环境不可靠：argv[0] 可能是绝对/相对/纯文件名，CWD 不确定；
# 两种调用方式：
#   1. busybox httpd 的 CGI（会设 GATEWAY_INTERFACE，需输出 HTTP 头）
#   2. KernelSU/APatch/Kitsune 的 ksu.exec 桥（stdout 必须是纯 JSON）
# 因此：MODDIR 三重探测；头只在 CGI 下打；错误落盘；永远吐合法 JSON。

# ---------- MODDIR 三重探测 ----------
MODDIR=""
[ -f /data/adb/modules/FJ_HostsBlock/common.sh ] && MODDIR=/data/adb/modules/FJ_HostsBlock
if [ -z "$MODDIR" ]; then
  d=$(cd "${0%/*}/../.." 2>/dev/null && pwd)
  [ -n "$d" ] && [ -f "$d/common.sh" ] && MODDIR="$d"
fi
if [ -z "$MODDIR" ]; then
  d=$(cat "${0%/*}/.moddir" 2>/dev/null)
  [ -n "$d" ] && [ -f "$d/common.sh" ] && MODDIR="$d"
fi
if [ -z "$MODDIR" ]; then
  d=$(cat "${0%/*}/../.moddir" 2>/dev/null)
  [ -n "$d" ] && [ -f "$d/common.sh" ] && MODDIR="$d"
fi

ERRLOG=/data/adb/FJ_HostsBlock/cgi.err
mkdir -p /data/adb/FJ_HostsBlock 2>/dev/null
SZ=$(wc -c < "$ERRLOG" 2>/dev/null)
case "$SZ" in ''|*[!0-9]*) SZ=0 ;; esac
if [ "$SZ" -gt 262144 ]; then
  tail -c 65536 "$ERRLOG" > "$ERRLOG.tmp" 2>/dev/null && mv -f "$ERRLOG.tmp" "$ERRLOG"
fi
exec 2>>"$ERRLOG"
echo "[$(date '+%m-%d %H:%M:%S')] start argv0=$0 cwd=$(pwd 2>/dev/null) ppid=$(cat /proc/$PPID/comm 2>/dev/null) cgi=${GATEWAY_INTERFACE:-no} qs=$QUERY_STRING" >> "$ERRLOG" 2>/dev/null

head_out() {
  [ -n "$GATEWAY_INTERFACE" ] || [ -n "$REQUEST_METHOD" ] || return 0
  printf 'Content-Type: application/json; charset=utf-8\r\n'
  printf 'Cache-Control: no-store\r\n'
  printf 'Access-Control-Allow-Origin: *\r\n'
  printf '\r\n'
}

if [ -z "$MODDIR" ] || [ ! -f "$MODDIR/common.sh" ]; then
  head_out
  printf '{"ok":false,"msg":"找不到模块目录 argv0=%s cwd=%s"}\n' "$0" "$(pwd 2>/dev/null)"
  exit 0
fi

. "$MODDIR/common.sh"

# 参数解析：argv 优先，再退到 QUERY_STRING。
# 顺序很关键！否则：CGI 请求 act=diag → control.sh 调 service.sh diag（继承 QUERY_STRING=act=diag）
# → service.sh 的 run_diag 再调 control.sh 'act=status'，但它会读到继承来的 QUERY_STRING=act=diag
# → 又跑 diag → 又调 service.sh diag …… 无限递归，进程爆炸、CPU 打满、整机卡死。
if [ -n "$*" ]; then
  QS=$(printf '%s' "$*" | tr ' ' '&' | tr -d '\r')
elif [ -n "$QUERY_STRING" ]; then
  QS=$(printf '%s' "$QUERY_STRING" | tr -d '\r')
else
  QS=""
fi

url_dec() {
  printf '%s' "$1" | sed 's/+/ /g; s/%20/ /g; s|%2F|/|g; s|%2E|.|g; s|%2D|-|g; s|%5F|_|g; s|%3A|:|g'
}

qget() {
  v=$(echo "$QS" | tr '&' '\n' | grep -m1 "^$1=" | cut -d= -f2-)
  url_dec "$v"
}

ACT=$(qget act)
[ -n "$ACT" ] || ACT=status

jie() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr -d '\n' | tr -d '\r' | tr -d '\t'; }
num() { case "$1" in ''|*[!0-9]*) echo 0 ;; *) echo "$1" ;; esac; }
yn() { [ "$1" = "1" ] && echo true || echo false; }

# JSON 片段构造：不再手写嵌套引号，避免转义出错
jstr() { printf '"%s"' "$(jie "$1")"; }
ok_json() { printf '{"ok":true,"msg":%s}\n' "$(jstr "$1")"; }
ng_json() { printf '{"ok":false,"msg":%s}\n' "$(jstr "$1")"; }

count_entries() { num "$(grep -c '^0\.0\.0\.0 ' "$HOSTS" 2>/dev/null)"; }

IP=/system/bin/iptables
IP6=/system/bin/ip6tables

set_count() {
  if [ "$1" = "6" ]; then
    if [ -n "$IPSET" ] && $IPSET list $SETNAME6 >/dev/null 2>&1; then
      num "$($IPSET list $SETNAME6 2>/dev/null | grep -cE '^([0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}$')"
    else
      num "$($IP6 -S fj_blk6_out 2>/dev/null | grep -c '^\-A')"
    fi
  else
    if [ -n "$IPSET" ] && $IPSET list $SETNAME >/dev/null 2>&1; then
      num "$($IPSET list $SETNAME 2>/dev/null | grep -cE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$')"
    else
      num "$($IP -S fj_blk_out 2>/dev/null | grep -c '^\-A')"
    fi
  fi
}

# 一次 awk 把 6 个段的开关状态、条目数、域名列表全部拼完。
# 原来 segs_json + bl_json 要跑 12 次 awk + 6 次 conf_get，现在 1 次 awk。
blsegs_json() {
  [ -f "$SEGCACHE" ] || build_segcache
  [ -f "$SEGCACHE" ] || : > "$SEGCACHE"
  FLAGS=""
  for g in $ALL_SEGS; do
    FLAGS="$FLAGS $g=$(conf_get "SEG_$(echo "$g" | tr a-z A-Z)")"
  done
  awk -F'\t' -v order="$ALL_SEGS" -v flags="$FLAGS" '
    BEGIN {
      n = split(flags, fa, " ")
      for (i = 1; i <= n; i++) { split(fa[i], kv, "="); on[kv[1]] = kv[2] }
    }
    NF >= 2 { d[$1] = d[$1] "," "\"" $2 "\""; c[$1]++ }
    END {
      m = split(order, sa, " ")
      printf "["
      for (i = 1; i <= m; i++) {
        s = sa[i]
        body = d[s]; sub(/^,/, "", body)
        printf "%s{\"id\":\"%s\",\"on\":%s,\"n\":%d,\"d\":[%s]}", (i > 1 ? "," : ""), s, (on[s] == "1" ? "true" : "false"), c[s] + 0, body
      }
      printf "]"
    }
  ' "$SEGCACHE" 2>/dev/null
}

extra_json() {
  f="$RUN_DIR/extra_hosts"
  s=""
  [ -f "$f" ] || { printf '[]'; return; }
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    d=$(echo "$line" | awk '{print $NF}' | tr -d '\r')
    [ -n "$d" ] || continue
    [ -n "$s" ] && s="$s,"
    s="$s$(jstr "$d")"
  done < "$f"
  printf '[%s]' "$s"
}

wl_json() {
  s=""
  [ -f "$WHITELIST" ] || { printf '[]'; return; }
  while IFS= read -r d; do
    [ -n "$d" ] || continue
    [ -n "$s" ] && s="$s,"
    s="$s$(jstr "$d")"
  done <<EOF
$(wl_domains)
EOF
  printf '[%s]' "$s"
}

bak_json() {
  s=""
  [ -d "$BAK_DIR" ] || { printf '[]'; return; }
  for f in "$BAK_DIR"/*.bak; do
    [ -f "$f" ] || continue
    b=$(basename "$f")
    sz=$(wc -c < "$f" 2>/dev/null)
    ts=$(date -r "$f" '+%Y-%m-%d %H:%M' 2>/dev/null || echo '-')
    [ -n "$s" ] && s="$s,"
    s="$s{\"f\":$(jstr "$b"),\"sz\":$(num "$sz"),\"t\":$(jstr "$ts")}"
  done
  printf '[%s]' "$s"
}

stat_json() {
  D=$(count_entries); I4=$(set_count 4); I6=$(set_count 6)
  [ -f "$RUN_DIR/.ipset_mode" ] && M=$(cat "$RUN_DIR/.ipset_mode") || M=none
  [ -f "$RUN_DIR/.ipset_mode6" ] && M6=$(cat "$RUN_DIR/.ipset_mode6") || M6=none
  [ -n "$BB" ] && BBS=yes || BBS=no
  [ -n "$IPSET" ] && ISS=yes || ISS=no
  N4=$(num "$(cat "$RUN_DIR/.last_n4" 2>/dev/null)")
  WP=$(cat "$RUN_DIR/.web_port" 2>/dev/null)
  case "$WP" in ''|*[!0-9]*) WP= ;; esac
  T=$(tail -n 14 "$LOG" 2>/dev/null | jie)
  printf '{"ok":true,"domains":%s,"ip4":%s,"ip6":%s,"last4":%s,"hosts_on":%s,"enforce_on":%s,"v6_on":%s,"dns_on":%s,"log_on":%s,"mode":%s,"mode6":%s,"bb":%s,"ipset":%s,"argv0":%s,"web_port":%s,"blsegs":%s,"extra":%s,"baks":%s,"wl_on":%s,"wl":%s,"log_tail":"%s"}\n' \
    "$D" "$I4" "$I6" "$N4" \
    "$(yn "$(conf_get HOSTS_ENABLE)")" "$(yn "$(conf_get ENFORCE_ENABLE)")" \
    "$(yn "$(conf_get IPV6_ENABLE)")" "$(yn "$(conf_get DNS_GUARD)")" "$(yn "$(conf_get LOG_ENABLE)")" \
    "$(jstr "$M")" "$(jstr "$M6")" "$(jstr "$BBS")" "$(jstr "$ISS")" "$(jstr "$0")" "$(jstr "$WP")" \
    "$(blsegs_json)" "$(extra_json)" "$(bak_json)" "$(yn "$(conf_get WHITELIST_ENABLE)")" "$(wl_json)" "$T"
}

# ---------- 拦截日志 ----------
# 输出事件数组本体，便于和排行榜合并成一个响应，少一次桥往返
blocklog_events_json() {
  N=${1:-150}
  TAB=$(printf '\t')
  RAW=$(blocklog_join "$N" 2>/dev/null)
  arr=""; cnt=0
  if [ -n "$RAW" ]; then
    while IFS="$TAB" read -r t ip name proto dpt src uid app; do
      [ -n "$ip" ] || continue
      [ -n "$arr" ] && arr="$arr,"
      arr="$arr{\"t\":$(num "$t"),\"ip\":$(jstr "$ip"),\"name\":$(jstr "$name"),\"proto\":$(jstr "$proto"),\"dpt\":$(jstr "$dpt"),\"src\":$(jstr "$src"),\"uid\":$(jstr "$uid"),\"app\":$(jstr "$app")}"
      cnt=$((cnt+1))
      [ "$cnt" -ge 300 ] && break
    done <<EOF
$RAW
EOF
  fi
  printf '[%s]' "$arr"
}

# DNS 兜底台账（这些 IP 的命中是「公共 DNS 被拦」，不是广告域名）
dns_ips_json() {
  f="$RUN_DIR/.dns_ips"
  s=""
  [ -f "$f" ] || { printf '[]'; return; }
  while IFS= read -r ip; do
    [ -n "$ip" ] || continue
    [ -n "$s" ] && s="$s,"
    s="$s$(jstr "$ip")"
  done < "$f"
  printf '[%s]' "$s"
}

top_rows_json() {
  TAB=$(printf '\t')
  if [ "$(cat "$RUN_DIR/.ipset_mode" 2>/dev/null)" = "ipset" ]; then
    printf '[{"pk":0,"ip":"","name":"ipset 集合模式：内核不逐 IP 计数，排行榜不适用","proto":"","kind":"note"}]'
    return
  fi
  RAW=$(iptables -L fj_blk_out -v -n -x 2>/dev/null | awk -v map="$IPMAP" -v dnsf="$RUN_DIR/.dns_ips" '
    BEGIN {
      OFS="\t"
      while ((getline l < map) > 0) { split(l,a," "); if (a[1]!="") dom[a[1]]=a[2] }
      close(map)
      while ((getline l < dnsf) > 0) { gsub(/[ \t]+/,"",l); if (l!="") dns[l]=1 }
      close(dnsf)
    }
    ($3=="REJECT" || $3=="DROP") && $1+0 > 0 {
      ip=""
      for (i=1;i<=NF;i++) {
        if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(\/[0-9]+)?$/) ip=$i
      }
      if (ip=="") next
      sub(/\/[0-9]+$/,"",ip)
      kind="blk"; nm=(ip in dom ? dom[ip] : "")
      if (ip in dns) { kind="dns"; nm="DNS 服务器（公共解析）" }
      print $1, ip, nm, $4, kind
    }' | sort -rn | head -n 40)
  arr=""
  if [ -n "$RAW" ]; then
    while IFS="$TAB" read -r pk ip name proto kind; do
      [ -n "$ip" ] || continue
      [ -n "$arr" ] && arr="$arr,"
      arr="$arr{\"pk\":$(num "$pk"),\"ip\":$(jstr "$ip"),\"name\":$(jstr "$name"),\"proto\":$(jstr "$proto"),\"kind\":$(jstr "$kind")}"
    done <<EOF
$RAW
EOF
  fi
  printf '[%s]' "$arr"
}

case "$ACT" in
  status) head_out; stat_json ;;
  wl_list) head_out; printf '{"ok":true,"wl_on":%s,"wl":%s}\n' "$(yn "$(conf_get WHITELIST_ENABLE)")" "$(wl_json)" ;;
  toggle)
    KEY=$(qget key); VAL=$(qget val)
    case "$KEY" in
      HOSTS_ENABLE|ENFORCE_ENABLE|IPV6_ENABLE|DNS_GUARD|LOG_ENABLE|WHITELIST_ENABLE)
        case "$VAL" in 0|1) conf_set "$KEY" "$VAL" ;; *) VAL=1 ;; esac
        if [ "$KEY" = "ENFORCE_ENABLE" ] && [ "$VAL" = "0" ]; then
          "$MODDIR/service.sh" stop >/dev/null 2>&1
        elif [ "$KEY" = "HOSTS_ENABLE" ]; then
          MODDIR="$MODDIR" "$MODDIR/post-fs-data.sh" >/dev/null 2>&1
        elif [ "$KEY" = "LOG_ENABLE" ]; then
          "$MODDIR/service.sh" resolveonly >/dev/null 2>&1 &
        else
          "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
        fi
        head_out; printf '{"ok":true,"key":%s,"val":%s}\n' "$(jstr "$KEY")" "$(jstr "$VAL")"
        ;;
      *) head_out; ng_json "bad key" ;;
    esac
    ;;
  seg)
    ID=$(qget id); VAL=$(qget val)
    if valid_seg "$ID"; then
      case "$VAL" in 0|1) conf_set "SEG_$(echo "$ID" | tr a-z A-Z)" "$VAL" ;; *) VAL=1 ;; esac
      rebuild_hosts
      # 关闭段：立即整表重建并按当前结果下发 IP 层（不再沿用旧台账，不会复活）。
      # 开启段：首次解析可能较慢，放后台。两种都以当前开关为准，不靠“再开再关”补救。
      if [ "$VAL" = "0" ]; then
        "$MODDIR/service.sh" resolveonly >/dev/null 2>&1
      else
        "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
      fi
      head_out; printf '{"ok":true,"id":%s,"val":%s}\n' "$(jstr "$ID")" "$(jstr "$VAL")"
    else
      head_out; ng_json "bad seg"
    fi
    ;;
  bl_list) head_out; printf '{"ok":true,"blsegs":%s}\n' "$(blsegs_json)" ;;
  bl_add)
    SG=$(qget seg); DN=$(qget d)
    if ! valid_domain "$DN"; then head_out; ng_json "域名格式不对"; exit 0; fi
    bl_add "$SG" "$DN"; r=$?
    case "$r" in
      0) rebuild_hosts; "$MODDIR/service.sh" refresh >/dev/null 2>&1 & head_out; printf '{"ok":true,"msg":"已加入 %s","blsegs":%s}\n' "$(jie "$DN")" "$(blsegs_json)" ;;
      3) head_out; printf '{"ok":true,"msg":"已存在","blsegs":%s}\n' "$(blsegs_json)" ;;
      *) head_out; ng_json "段落或域名不合法" ;;
    esac
    ;;
  bl_del)
    SG=$(qget seg); DN=$(qget d)
    bl_del "$SG" "$DN"; rebuild_hosts
    "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
    head_out; printf '{"ok":true,"msg":"已移除 %s","blsegs":%s}\n' "$(jie "$DN")" "$(blsegs_json)"
    ;;
  bl_seg_clear)
    SG=$(qget seg); bl_clear_seg "$SG"; rebuild_hosts
    "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
    head_out; printf '{"ok":true,"msg":"段 %s 已清空","blsegs":%s}\n' "$(jie "$SG")" "$(blsegs_json)"
    ;;
  bl_restore)
    if bl_restore; then
      rebuild_hosts
      "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
      head_out; printf '{"ok":true,"msg":"已恢复内置规则","blsegs":%s}\n' "$(blsegs_json)"
    else
      head_out; ng_json "内置规则母本缺失"
    fi
    ;;
  bl_seg_restore)
    SG=$(qget seg)
    if bl_seg_restore "$SG"; then
      rebuild_hosts
      "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
      head_out; printf '{"ok":true,"msg":"段 %s 已恢复内置规则","blsegs":%s}\n' "$(jie "$SG")" "$(blsegs_json)"
    else
      head_out; ng_json "该段没有内置规则可恢复"
    fi
    ;;
  extra_list) head_out; printf '{"ok":true,"extra":%s}\n' "$(extra_json)" ;;
  extra_add)
    DN=$(qget d)
    if ! valid_domain "$DN"; then head_out; ng_json "域名格式不对"; exit 0; fi
    f="$RUN_DIR/extra_hosts"
    [ -f "$f" ] || : > "$f"
    if awk -v d="$DN" '{for(i=1;i<=NF;i++) if($i==d) f=1} END{exit !f}' "$f" 2>/dev/null; then
      head_out; printf '{"ok":true,"msg":"已存在","extra":%s}\n' "$(extra_json)"; exit 0
    fi
    echo "0.0.0.0 $DN" >> "$f"; rebuild_hosts
    head_out; printf '{"ok":true,"msg":"已加入 %s","extra":%s}\n' "$(jie "$DN")" "$(extra_json)"
    ;;
  extra_del)
    DN=$(qget d); f="$RUN_DIR/extra_hosts"
    if [ -f "$f" ]; then
      awk -v d="$DN" '{keep=1; for(i=1;i<=NF;i++) if($i==d) keep=0; if(keep) print}' "$f" > "$f.tmp" 2>/dev/null && mv -f "$f.tmp" "$f"
      rebuild_hosts
      head_out; printf '{"ok":true,"msg":"已删除 %s","extra":%s}\n' "$(jie "$DN")" "$(extra_json)"
    else
      head_out; ng_json "无自定义表"
    fi
    ;;
  extra_clear)
    : > "$RUN_DIR/extra_hosts"; rebuild_hosts
    head_out; printf '{"ok":true,"msg":"已清空自定义域名","extra":%s}\n' "$(extra_json)"
    ;;
  wl_add)
    DN=$(qget d)
    if ! valid_domain "$DN"; then head_out; ng_json "域名格式不对"; exit 0; fi
    [ -f "$WHITELIST" ] || : > "$WHITELIST"
    if wl_domains | grep -qxF "$DN"; then
      head_out; printf '{"ok":true,"msg":"已存在","wl":%s}\n' "$(wl_json)"; exit 0
    fi
    echo "$DN" >> "$WHITELIST"
    rebuild_hosts; "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
    head_out; printf '{"ok":true,"msg":"已加入白名单 %s","wl":%s}\n' "$(jie "$DN")" "$(wl_json)"
    ;;
  wl_del)
    DN=$(qget d)
    if [ -f "$WHITELIST" ]; then
      awk -v d="$DN" '{keep=1; if($1==d) keep=0; if(keep) print}' "$WHITELIST" > "$WHITELIST.tmp" 2>/dev/null && mv -f "$WHITELIST.tmp" "$WHITELIST"
      rebuild_hosts; "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
      head_out; printf '{"ok":true,"msg":"已从白名单删除 %s","wl":%s}\n' "$(jie "$DN")" "$(wl_json)"
    else
      head_out; ng_json "无白名单文件"
    fi
    ;;
  bak_list) head_out; printf '{"ok":true,"baks":%s}\n' "$(bak_json)" ;;
  bak_export)
    mkdir -p "$BAK_DIR" 2>/dev/null
    OUT="$BAK_DIR/FJ_HostsBlock_$(date +%Y%m%d_%H%M%S).bak"
    R=$(backup_export "$OUT")
    if [ -n "$R" ]; then
      head_out; printf '{"ok":true,"msg":"已导出 %s","file":%s,"baks":%s}\n' "$(jie "$(basename "$R")")" "$(jstr "$R")" "$(bak_json)"
    else
      head_out; ng_json "导出失败"
    fi
    ;;
  bak_import)
    F=$(qget f)
    case "$F" in ''|*/*|*..*) head_out; ng_json "文件名不合法"; exit 0 ;; esac
    if [ -f "$BAK_DIR/$F" ]; then
      backup_import "$BAK_DIR/$F"
      if [ "$?" = "0" ]; then
        "$MODDIR/service.sh" refresh >/dev/null 2>&1 &
        head_out; ok_json "已导入 $F"
      else
        head_out; ng_json "格式不对，导入中断"
      fi
    else
      head_out; ng_json "文件不存在"
    fi
    ;;
  bak_del)
    F=$(qget f)
    case "$F" in ''|*/*|*..*) head_out; ng_json "文件名不合法"; exit 0 ;; esac
    if [ -f "$BAK_DIR/$F" ]; then
      rm -f "$BAK_DIR/$F"
      head_out; printf '{"ok":true,"msg":"已删除","baks":%s}\n' "$(bak_json)"
    else
      head_out; ng_json "文件不存在"
    fi
    ;;
  bak_dump)
    F=$(qget f)
    case "$F" in ''|*/*|*..*) head_out; ng_json "文件名不合法"; exit 0 ;; esac
    if [ -f "$BAK_DIR/$F" ]; then
      head_out
      printf '{"ok":true,"name":%s,"size":%s,"content":%s}\n' "$(jstr "$F")" "$(num "$(wc -c < "$BAK_DIR/$F")")" "$(jstr "$(cat "$BAK_DIR/$F")")"
    else
      head_out; ng_json "文件不存在"
    fi
    ;;
  # 拦截页签一次性把事件流和排行榜都拿回去，只发一次桥调用
  intercept)
    head_out
    N=$(qget n); case "$N" in ''|*[!0-9]*) N=150 ;; esac
    printf '{"ok":true,"log_on":%s,"kmsg":%s,"rules":%s,"events":%s,"rows":%s,"dns":%s}\n' \
      "$(yn "$(conf_get LOG_ENABLE)")" "$(jstr "$(kmsg_state)")" \
      "$(num "$(iptables -S fj_blk_out 2>/dev/null | grep -c '^-A')")" \
      "$(blocklog_events_json "$N")" "$(top_rows_json)" "$(dns_ips_json)"
    ;;
  blocklog)
    head_out; printf '{"ok":true,"log_on":%s,"events":%s}\n' "$(yn "$(conf_get LOG_ENABLE)")" "$(blocklog_events_json "$(qget n)")"
    ;;
  top)
    head_out; printf '{"ok":true,"rows":%s}\n' "$(top_rows_json)"
    ;;
  privacy)
    head_out
    OUT=$("$MODDIR/privacy.sh" scan 2>/dev/null)
    case "$OUT" in
      *'"ok":true'*) printf '%s\n' "$OUT" ;;
      *) ng_json "体检失败：$(printf '%s' "$OUT" | head -c 200)" ;;
    esac
    ;;
  priv_toggle)
    P=$(qget pkg); ON=$(qget on)
    case "$ON" in 0|1) : ;; *) ON=0 ;; esac
    head_out
    "$MODDIR/privacy.sh" toggle "$P" "$ON" 2>/dev/null
    ;;
  counter_reset)
    iptables -w 5 -Z fj_blk_out 2>/dev/null
    command -v ip6tables >/dev/null 2>&1 && ip6tables -w 5 -Z fj_blk6_out 2>/dev/null
    head_out; ok_json "计数器已清零"
    ;;
  retryresolve)
    "$MODDIR/service.sh" retryresolve >/dev/null 2>&1 &
    head_out; ok_json "解析重试已拉起"
    ;;
  diag)
    # 双保险：即使哪条路径又把 QUERY_STRING 带进来，也不会再递归第二次
    if [ -n "$FJ_DIAG_GUARD" ]; then
      head_out; ng_json "诊断已在运行，已拒绝重复触发（防递归）"
      exit 0
    fi
    FJ_DIAG_GUARD=1; export FJ_DIAG_GUARD
    "$MODDIR/service.sh" diag >/dev/null 2>&1
    if [ -f "$RUN_DIR/diag.txt" ]; then
      head_out; printf '{"ok":true,"msg":"诊断已生成","diag":%s}\n' "$(jstr "$(cat "$RUN_DIR/diag.txt" 2>/dev/null)")"
    else
      head_out; ng_json "诊断生成失败"
    fi
    ;;
  refresh) "$MODDIR/service.sh" refresh >/dev/null 2>&1 & head_out; ok_json "已开始重新解析" ;;
  resolveonly) "$MODDIR/service.sh" resolveonly >/dev/null 2>&1 & head_out; ok_json "已开始重跑 IP 层" ;;
  dnsguard) "$MODDIR/service.sh" dnsguard >/dev/null 2>&1 & head_out; ok_json "已开始 DNS 兜底" ;;
  stop) "$MODDIR/service.sh" stop >/dev/null 2>&1; head_out; ok_json "规则已清空" ;;
  log) head_out; printf '{"ok":true,"log":%s}\n' "$(jstr "$(tail -n 120 "$LOG" 2>/dev/null)")" ;;
  cgierr) head_out; printf '{"ok":true,"content":%s}\n' "$(jstr "$(tail -n 60 "$ERRLOG" 2>/dev/null)")" ;;
  *) head_out; ng_json "unknown act" ;;
esac

exit 0
