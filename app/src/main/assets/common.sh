#!/system/bin/sh
# FJ HostsBlock 公共函数库 v2.0

MODDIR=${MODDIR:-/data/adb/modules/FJ_HostsBlock}
RUN_DIR=/data/adb/FJ_HostsBlock
CONF="$RUN_DIR/config"
HOSTS="$MODDIR/system/etc/hosts"
HOSTS_BASE="$MODDIR/hosts.base"
BLOCKLIST="$MODDIR/blocklist.txt"
LOG="$RUN_DIR/ipset.log"
ERRLOG="$RUN_DIR/cgi.err"
SEGCACHE="$RUN_DIR/segcache"
DEFLIST="$MODDIR/blocklist.default.txt"
WHITELIST="$MODDIR/whitelist.txt"
WLHOSTS="$RUN_DIR/whitelist_hosts"
SETNAME=fj_blk
SETNAME6=fj_blk6
DNS1=223.5.5.5
DNS2=119.29.29.29

mkdir -p "$RUN_DIR" 2>/dev/null

ALL_SEGS="telemetry antifraud riskgate miuiad doh adsdk"
BAK_DIR="$RUN_DIR/backup"

[ -f "$CONF" ] || cat > "$CONF" <<EOF
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

# 老版本升级：补齐缺失的段开关。
# 这段每项都要 spawn 一个 grep，而本文件每次请求都会被 source，
# 所以用一个版本标记包起来，跑过就不再重跑。
CONFVER_FILE="$RUN_DIR/.confver"
CONFVER=4
if [ "$(cat "$CONFVER_FILE" 2>/dev/null)" != "$CONFVER" ]; then
  for s in $ALL_SEGS; do
    KEY="SEG_$(echo "$s" | tr 'a-z' 'A-Z')"
    grep -q "^$KEY=" "$CONF" 2>/dev/null || echo "$KEY=1" >> "$CONF"
  done
  grep -q '^IPV6_ENABLE=' "$CONF" 2>/dev/null || echo 'IPV6_ENABLE=1' >> "$CONF"
  # DNS 兜底老用户先给 0，避免升级上来突然断掉自建解析
  grep -q '^DNS_GUARD=' "$CONF" 2>/dev/null || echo 'DNS_GUARD=0' >> "$CONF"
  # 拦截日志默认开（带限速，不会刷爆内核日志）
  grep -q '^LOG_ENABLE=' "$CONF" 2>/dev/null || echo 'LOG_ENABLE=1' >> "$CONF"
  # 关键域名白名单开关（老用户补上，避免 LSPosed/GitHub 更新被误拦）
  grep -q '^WHITELIST_ENABLE=' "$CONF" 2>/dev/null || echo 'WHITELIST_ENABLE=1' >> "$CONF"
  echo "$CONFVER" > "$CONFVER_FILE" 2>/dev/null
fi

# 配置由下方 conf_load() 载入（conf_get 也会懒加载，此处不用提前调用）

# 配置一次性读进内存。原来每次 conf_get 都要 spawn 一个 grep，
# 一次请求里能跑十几次，累计就是卡顿的主因之一。
# 键和值都做字符白名单校验，所以 eval 无命令注入风险。
CONF_LOADED=0
conf_load() {
  CONF_LOADED=1
  [ -f "$CONF" ] || return 0
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    k=${line%%=*}
    v=${line#*=}
    case "$k" in ''|*[!A-Za-z0-9_]*) continue ;; esac
    case "$v" in *[!A-Za-z0-9_./:-]*) continue ;; esac
    eval "CF_$k='$v'"
  done < "$CONF"
}

conf_get() {
  [ "$CONF_LOADED" = "1" ] || conf_load
  eval "printf '%s' \"\${CF_$1:-}\""
}

conf_set() {
  k=$1; v=$2
  if grep -q "^$k=" "$CONF" 2>/dev/null; then
    sed -i "s|^$k=.*|$k=$v|" "$CONF"
  else
    echo "$k=$v" >> "$CONF"
  fi
  # 同步内存，同一个进程内后续 conf_get 立即拿到新值
  case "$k" in ''|*[!A-Za-z0-9_]*) : ;; *) eval "CF_$k='$v'" ;; esac
}

seg_on() {
  u=$(echo "$1" | tr a-z A-Z)
  [ "$(conf_get "SEG_$u")" = "1" ]
}

log() {
  [ -f "$LOG" ] || : > "$LOG"
  echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"
  n=$(wc -l < "$LOG" 2>/dev/null)
  case "$n" in ''|*[!0-9]*) n=0 ;; esac
  if [ "$n" -gt 1000 ]; then
    tail -n 500 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"
  fi
}

# ---------- 日志轮转（统一入口，定时 + 写入时双保险） ----------
# 只处理会持续增长的两个文件：ipset.log 和 cgi.err。
# 其余（diag.txt / uidmap.txt / ipmap.txt / segcache 等）都是“每次重建覆盖”，
# 基本固定大小，不需要清理。
# 保留上限：主日志 ~2000 行；cgi.err 超过 128KB 只留尾部 32KB。
rotate_logs() {
  # ipset.log：行数超限就截断保留末尾（主日志本身有 1000 行自带阀门，这里兼底）
  if [ -f "$LOG" ]; then
    n=$(wc -l < "$LOG" 2>/dev/null)
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    if [ "$n" -gt 2000 ]; then
      tail -n 1000 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"
    fi
  fi
  # cgi.err：字节数超限就截断保留末尾 32KB
  if [ -f "$ERRLOG" ]; then
    s=$(wc -c < "$ERRLOG" 2>/dev/null)
    case "$s" in ''|*[!0-9]*) s=0 ;; esac
    if [ "$s" -gt 131072 ]; then
      tail -c 32768 "$ERRLOG" > "$ERRLOG.tmp" 2>/dev/null && mv -f "$ERRLOG.tmp" "$ERRLOG"
    fi
  fi
}

# ---------- busybox 定位 ----------
# 关键：必须用自带 busybox 做 DNS 查询。
# 系统没有 nslookup，ping/getent 等又读 /etc/hosts（已被本模块写成 0.0.0.0），
# 结果永远解不出真实 IP，IP 层白干。
BB=""
BB_TMP=/data/local/tmp/.fj_busybox
find_bb() {
  for p in "$MODDIR/bin/busybox" /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /system/bin/busybox /system/xbin/busybox; do
    [ -x "$p" ] || continue
    if "$p" true 2>/dev/null; then BB="$p"; return 0; fi
    # 能读不能跑，多半是 noexec 或 SELinux 上下文不对：把二进制拷到 /data/local/tmp 再跑
    cp -f "$p" "$BB_TMP" 2>/dev/null && chmod 0755 "$BB_TMP" 2>/dev/null
    if [ -x "$BB_TMP" ] && "$BB_TMP" true 2>/dev/null; then
      BB="$BB_TMP"
      echo "$p" > "$RUN_DIR/.bb_src"
      return 0
    fi
  done
  if command -v busybox >/dev/null 2>&1 && busybox true 2>/dev/null; then BB=busybox; return 0; fi
  return 1
}
# 探针结果缓存到磁盘。每次 CGI/桥调用都会 source 本文件，
# 而探测要 spawn 好几个进程（busybox 还是 1.7MB 的二进制），
# 累计起来就是肉眼可见的卡。缓存命中时只读一个小文件。
BB_CACHE="$RUN_DIR/.bbpath"
BB=""
if [ -f "$BB_CACHE" ]; then
  _p=$(cat "$BB_CACHE" 2>/dev/null)
  case "$_p" in
    ''|none) : ;;
    *) [ -x "$_p" ] && BB="$_p" ;;
  esac
fi
if [ -z "$BB" ]; then
  find_bb || BB=""
  echo "${BB:-none}" > "$BB_CACHE" 2>/dev/null
fi
[ -n "$BB" ] || log "! 无可用 busybox，DNS 解析与 WebUI 不可用"

# ipset 优先用自带 busybox 的（很多 ROM 的 /system/bin 里没有这个命令）
IPSET=""
find_ipset() {
  if [ -n "$BB" ] && "$BB" ipset --help >/dev/null 2>&1; then IPSET="$BB ipset"; return 0; fi
  if command -v ipset >/dev/null 2>&1; then IPSET=ipset; return 0; fi
  IPSET=""; return 1
}
# JSON 字符串转义（control.sh / privacy.sh 共用）
jstr() { printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr -d '\n' | tr -d '\r' | tr -d '\t')"; }

IPSET_CACHE="$RUN_DIR/.ipsetpath"
IPSET=""
if [ -f "$IPSET_CACHE" ]; then
  _i=$(cat "$IPSET_CACHE" 2>/dev/null)
  case "$_i" in
    ''|none) : ;;
    *) IPSET="$_i" ;;
  esac
else
  find_ipset || IPSET=""
  echo "${IPSET:-none}" > "$IPSET_CACHE" 2>/dev/null
fi

# busybox 是精简编译的，某个 applet 可能在也可能不在，跑之前先探
bb_has() {
  [ -n "$BB" ] || return 1
  "$BB" "$1" --help >/dev/null 2>&1
}

# 把块表展开成 "段<TAB>域名" 缓存（$SEGCACHE）。
# 块表只在刷新/编辑时变，没必要每次请求都重新解析 6 遍。
build_segcache() {
  [ -f "$BLOCKLIST" ] || return 0
  awk '
    /^\[/ { s=$0; gsub(/^\[|\][[:space:]]*$/,"",s); cur=s; next }
    /^#/ { next }
    /^[[:space:]]*$/ { next }
    cur!="" && $1!="" { printf "%s\t%s\n", cur, $1 }
  ' "$BLOCKLIST" > "$SEGCACHE.tmp" 2>/dev/null && mv -f "$SEGCACHE.tmp" "$SEGCACHE"
}

# 从指定块表文件提取某段域名（不读缓存，用于内置规则母本）
seg_domains_from() {
  [ -f "$1" ] || return 0
  awk -v want="$2" '
    /^\[/ { s=$0; gsub(/^\[|\][[:space:]]*$/,"",s); cur=s; next }
    /^#/ { next }
    /^[[:space:]]*$/ { next }
    cur==want { print $1 }
  ' "$1"
}

# 整表恢复内置规则
bl_restore() {
  [ -f "$DEFLIST" ] || return 1
  cp -f "$DEFLIST" "$BLOCKLIST" 2>/dev/null || return 1
  chmod 0644 "$BLOCKLIST" 2>/dev/null
  build_segcache
  return 0
}

# 单段恢复：用内置该段补回被误删的，同时保留你自己加的
bl_seg_restore() {
  seg=$1
  [ -f "$DEFLIST" ] || return 1
  DSEG="$RUN_DIR/.segdef"; XSEG="$RUN_DIR/.segextra"; CSEG="$RUN_DIR/.segcur"
  seg_domains_from "$DEFLIST" "$seg" > "$DSEG" 2>/dev/null
  [ -s "$DSEG" ] || return 1
  seg_domains "$seg" > "$CSEG" 2>/dev/null
  grep -vxF -f "$DSEG" "$CSEG" 2>/dev/null > "$XSEG"
  awk -v want="$seg" -v d="$DSEG" -v x="$XSEG" '
    function put(  i) {
      if (put_done) return
      put_done = 1
      for (i = 1; i <= nd; i++) print def[i]
      for (i = 1; i <= ne; i++) print ext[i]
    }
    BEGIN {
      while ((getline l < d) > 0) if (l != "") def[++nd] = l
      while ((getline l < x) > 0) if (l != "") ext[++ne] = l
    }
    /^\[/ {
      s = $0; gsub(/^\[|\][[:space:]]*$/,"",s)
      if (cur == want) put()
      cur = s
      print
      next
    }
    {
      if (cur == want) next
      print
    }
    END { if (cur == want) put() }
  ' "$BLOCKLIST" > "$RUN_DIR/.bl.new" 2>/dev/null && mv -f "$RUN_DIR/.bl.new" "$BLOCKLIST"
  chmod 0644 "$BLOCKLIST" 2>/dev/null
  build_segcache
  return 0
}

# 从块表提取某个段的域名（优先读缓存，一次 awk 就够）
seg_domains() {
  if [ -f "$SEGCACHE" ]; then
    awk -F'\t' -v want="$1" '$1==want {print $2}' "$SEGCACHE"
    return 0
  fi
  [ -f "$BLOCKLIST" ] || return 0
  awk -v want="$1" '
    /^\[/ {
      s=$0; gsub(/^\[|\][[:space:]]*$/,"",s); cur=s; next
    }
    /^#/ {next}
    /^[[:space:]]*$/ {next}
    cur==want {print $1}
  ' "$BLOCKLIST"
}

# 只取开启段里的域名
enabled_seg_domains() {
  for s in $ALL_SEGS; do
    seg_on "$s" && seg_domains "$s"
  done
}

# ---------- 关键域名白名单（永不屏蔽） ----------
# 治「LSPosed 在线更新被拦」「GitHub 下不动」这类误伤。
# 白名单域名：①不会被写进 hosts 挡表；②解析出的 IP 会在 IP 层放行（见 service.sh）。
# 文件格式：一行一个域名，[分段] 与 # 注释被忽略。
wl_domains() {
  [ -f "$WHITELIST" ] || return 0
  awk '/^[[:space:]]*\[/ {next} /^[[:space:]]*#/ {next} /^[[:space:]]*$/ {next} {print $1}' "$WHITELIST"
}
wb_on() { [ "$(conf_get WHITELIST_ENABLE)" != "0" ]; }

# 重建 hosts：base + 已开段落 + extra_hosts（关键域名白名单永不进入）
rebuild_hosts() {
  TMP="$RUN_DIR/.hosts.new"
  if [ -f "$HOSTS_BASE" ]; then cat "$HOSTS_BASE" > "$TMP"; else printf '127.0.0.1 localhost\n::1 localhost\n' > "$TMP"; fi

  if wb_on; then wl_domains > "$WLHOSTS" 2>/dev/null; else : > "$WLHOSTS"; fi

  if [ "$(conf_get HOSTS_ENABLE)" != "1" ]; then
    cat "$TMP" > "$HOSTS"
    rm -f "$TMP"
    log "hosts 屏蔽关闭，仅保留 base"
    return 0
  fi

  echo "" >> "$TMP"
  echo "# ===== FJ HostsBlock (generated) =====" >> "$TMP"
  for s in $ALL_SEGS; do
    seg_on "$s" || continue
    echo "# --- $s ---" >> "$TMP"
    seg_domains "$s" | while IFS= read -r d; do
      [ -n "$d" ] && echo "0.0.0.0 $d" >> "$TMP"
    done
  done

  if [ -f "$RUN_DIR/extra_hosts" ]; then
    echo "# --- custom ---" >> "$TMP"
    while IFS= read -r line; do
      case "$line" in ''|'#'*) continue ;; esac
      d=$(echo "$line" | awk '{print $NF}' | tr -d '\r')
      [ -n "$d" ] && echo "0.0.0.0 $d" >> "$TMP"
    done < "$RUN_DIR/extra_hosts"
  fi

  echo "# ===== end =====" >> "$TMP"

  # 关键白名单过滤：若某域名同时出现在挡表里，删掉它的 0.0.0.0 行（放行）。
  # 宁可漏拦，不可误伤 LSPosed / GitHub / 小米账号等关键功能。
  if wb_on && [ -s "$WLHOSTS" ]; then
    awk 'NR==FNR { if($0!="") wl[$0]=1; next } ($1=="0.0.0.0" && ($2 in wl)) {next} {print}' "$WLHOSTS" "$TMP" > "$TMP.f" 2>/dev/null && mv -f "$TMP.f" "$TMP"
  fi

  cat "$TMP" > "$HOSTS"
  rm -f "$TMP"
  chmod 0644 "$HOSTS" 2>/dev/null
  chown 0:0 "$HOSTS" 2>/dev/null
  build_segcache
  log "hosts 重建完成，条目 $(grep -c '^0\.0\.0\.0 ' "$HOSTS" 2>/dev/null)"
}

list_domains() {
  [ -f "$HOSTS" ] || return 0
  awk '$1=="0.0.0.0" {print $2}' "$HOSTS" 2>/dev/null \
    | grep -v '^localhost$' | grep -v '^$' | sort -u
}

### ---------- 块表在线编辑 ----------

BL_TMP="$RUN_DIR/.bl.new"

# 校验域名
valid_domain() {
  case "$1" in ''|.*|*.|*..*|*-|-*|*_) return 1 ;; esac
  case "$1" in *[!A-Za-z0-9_.-]*) return 1 ;; esac
  echo "$1" | grep -qE '^[A-Za-z0-9_][A-Za-z0-9_.-]*[A-Za-z0-9_]$' && echo "$1" | grep -q '\.'
}

valid_seg() {
  case " $ALL_SEGS " in *" $1 "*) return 0 ;; esac
  return 1
}

# 向某段追加域名，不存在则创建段
bl_add() {
  seg=$1; dn=$2
  valid_seg "$seg" || return 1
  valid_domain "$dn" || return 2
  [ -f "$BLOCKLIST" ] || : > "$BLOCKLIST"
  # 全表已有就跳过
  awk -v want="$seg" -v d="$dn" '
    /^\[/ {s=$0; gsub(/^\[|\][[:space:]]*$/,"",s); cur=s; next}
    /^[[:space:]]*$/ {next}
    /^#/ {next}
    cur==want && $1==d {f=1}
    END{exit !f}
  ' "$BLOCKLIST" && return 3

  if grep -q "^\[$seg\]" "$BLOCKLIST" 2>/dev/null; then
    awk -v want="$seg" -v d="$dn" '
      BEGIN{done=0}
      /^\[/ {
        s=$0; gsub(/^\[|\][[:space:]]*$/,"",s);
        if (cur==want && !done) { print d; done=1 }
        cur=s; print; next
      }
      {print}
      END{ if (!done) print d }
    ' "$BLOCKLIST" > "$BL_TMP" && mv -f "$BL_TMP" "$BLOCKLIST"
  else
    printf '\n[%s]\n%s\n' "$seg" "$dn" >> "$BLOCKLIST"
  fi
  chmod 0644 "$BLOCKLIST" 2>/dev/null
  return 0
}

# 从某段删域名
bl_del() {
  seg=$1; dn=$2
  [ -f "$BLOCKLIST" ] || return 1
  awk -v want="$seg" -v d="$dn" '
    /^\[/ {s=$0; gsub(/^\[|\][[:space:]]*$/,"",s); cur=s; print; next}
    cur==want && $1==d {next}
    {print}
  ' "$BLOCKLIST" > "$BL_TMP" && mv -f "$BL_TMP" "$BLOCKLIST"
  chmod 0644 "$BLOCKLIST" 2>/dev/null
  return 0
}

# 整段清空（保留段头）
bl_clear_seg() {
  seg=$1
  [ -f "$BLOCKLIST" ] || return 1
  awk -v want="$seg" '
    /^\[/ {s=$0; gsub(/^\[|\][[:space:]]*$/,"",s); cur=s; print; next}
    cur==want && $1!="#" {next}
    {print}
  ' "$BLOCKLIST" > "$BL_TMP" && mv -f "$BL_TMP" "$BLOCKLIST"
  chmod 0644 "$BLOCKLIST" 2>/dev/null
  return 0
}

### ---------- DNS 层兜底 ----------
# 53/853/5353 端口拦截与公共 DNS IP 下发都在 service.sh 的 dns_guard 里

# 公共 DNS 服务器（明文 53 / DoT 853 / DoH 目标）
# 注意：DNS1/DNS2 不能写进来，它们是模块自己 refresh 时用的解析器，拦了会自锁
DNS_SERVERS_V4="8.8.8.8 8.8.4.4 1.1.1.1 1.0.0.1 9.9.9.9 149.112.112.112 208.67.222.222 208.67.220.220 94.140.14.14 94.140.15.15 180.76.76.76 114.114.114.114 114.114.115.115"
DNS_SERVERS_V6="2001:4860:4860::8888 2606:4700:4700::1111 2620:fe::fe"

# 读取本机当前生效的 DNS（运营商/路由器给的），作为额外解析目标
# 有些网络根本到不了 223.5.5.5，只信自己给的那个 resolver
sys_dns_v4() {
  for p in net.dns1 net.dns2 net.dns3 net.dns4; do
    v=$(getprop "$p" 2>/dev/null)
    case "$v" in
      ''|*[!0-9.]*) continue ;;
    esac
    echo "$v"
  done
  # 部分 ROM 走 dhcp 属性
  for p in dhcp.wlan0.dns1 dhcp.eth0.dns1 dhcp.rmnet0.dns1; do
    v=$(getprop "$p" 2>/dev/null)
    case "$v" in
      ''|*[!0-9.]*) continue ;;
    esac
    echo "$v"
  done
  # 兜底：直接读 resolv.conf
  [ -f /etc/resolv.conf ] && awk '/^nameserver/{print $2} /^nameserver/ { }' /etc/resolv.conf 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
}

# 依次尝试的解析器：系统自己给的排前面（它一定可达），自己的两个兵底。
# 去重，避免重复请求同一台服务器浪费时间。
all_dns_v4() {
  {
    sys_dns_v4
    printf '%s\n' "$DNS1" "$DNS2"
  } | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | awk '!seen[$0]++'
}

# 把已知 DNS 服务器 IP 解出真实地址后归一化（域名形式写也行）
dns_targets_v4() {
  SYS=$(sys_dns_v4 | sort -u)
  {
    for d in $DNS_SERVERS_V4; do
      case "$d" in
        *[!0-9.]*) resolve_domain "$d" ;;
        *) echo "$d" ;;
      esac
    done
  } | sort -u | while IFS= read -r ip; do
    [ -n "$ip" ] || continue
    # 本机正在用的 DNS 服务器绝不能封，封了就是全机断网
    if [ -n "$SYS" ] && echo "$SYS" | grep -qx "$ip"; then
      [ "$FJDNS_QUIET" = "1" ] || echo "skip sys dns $ip" >> "$RUN_DIR/.dns_skip" 2>/dev/null
      continue
    fi
    echo "$ip"
  done
}

dns_targets_v6() {
  for d in $DNS_SERVERS_V6; do
    case "$d" in
      *[!0-9a-fA-F:]*) resolve_domain6 "$d" ;;
      *) echo "$d" ;;
    esac
  done | sort -u
}

### ---------- 拦截日志：IP 反查 + 事件读取 ----------
# LOG 规则把被拦的包写进内核日志，这里负责读出来并把 IP 翻成域名。

IPMAP="$RUN_DIR/ipmap.txt"
BLKRAW="$RUN_DIR/.blkraw"
LOG_PREFIX="FJBLK"

ipmap_reset() { : > "$IPMAP" 2>/dev/null; }
ipmap_add() {
  [ -n "$1" ] && [ -n "$2" ] || return 0
  echo "$1 $2" >> "$IPMAP" 2>/dev/null
}

# ---------- UID → 应用名（事件流里“哪个应用发的请求”） ----------
# 内核 LOG 带 --log-uid 时会写 UID=xxxxx，用它反查包名。
# pm list packages -U 一次拿到全量映射，缓存 1 小时，避免每次刷页面都调 pm。
UIDMAP="$RUN_DIR/uidmap.txt"
build_uidmap() {
  f="$UIDMAP"; tf="$RUN_DIR/.uidmap.ts"
  now=$(date +%s 2>/dev/null); case "$now" in ''|*[!0-9]*) now=0 ;; esac
  last=$(cat "$tf" 2>/dev/null); case "$last" in ''|*[!0-9]*) last=0 ;; esac
  if [ -s "$f" ] && [ "$now" -gt 0 ] && [ $((now - last)) -lt 3600 ]; then return 0; fi
  out=$(pm list packages -U 2>/dev/null)
  [ -n "$out" ] || out=$(cmd package list packages -U 2>/dev/null)
  [ -n "$out" ] || return 1
  printf '%s\n' "$out" | awk '
    /^package:/ {
      p=$1; sub(/^package:/,"",p)
      u=""
      for (i=1;i<=NF;i++) if ($i ~ /^uid:/) { u=$i; sub(/^uid:/,"",u) }
      if (u!="" && p!="") print u "\t" p
    }' > "$f.tmp" 2>/dev/null
  if [ -s "$f.tmp" ]; then
    mv -f "$f.tmp" "$f"
    [ "$now" != "0" ] && echo "$now" > "$tf"
  fi
  return 0
}

# 开机时刻（epoch），用来把内核日志的开机秒数换成墙上时间
boot_epoch() {
  up=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)
  now=$(date +%s 2>/dev/null)
  case "$up" in ''|*[!0-9.]*) echo "$now"; return ;; esac
  iup=${up%.*}
  case "$iup" in ''|*[!0-9]*) iup=0 ;; esac
  echo $((now - iup))
}

# 内核日志能不能读？三种途径依序探，都不行就返回 unreadable。
# Enforcing 的 SELinux 下 magisk 域经常被挡，不探清楚用户只会看到一个空列表。
kmsg_state() {
  if command -v dmesg >/dev/null 2>&1; then
    dmesg 2>/dev/null | head -n 1 | grep -q . && { echo ok; return 0; }
  fi
  if [ -n "$BB" ] && "$BB" dmesg 2>/dev/null | head -n 1 | grep -q .; then
    echo ok; return 0
  fi
  if command -v logcat >/dev/null 2>&1 && logcat -b kernel -d 2>/dev/null | head -n 1 | grep -q .; then
    echo ok; return 0
  fi
  echo unreadable
}

# 内核日志读取，三种途径轮着来
blocklog_raw() {
  n=${1:-80}
  if command -v dmesg >/dev/null 2>&1; then
    dmesg 2>/dev/null | grep -- "$LOG_PREFIX" | tail -n "$n" && return 0
  fi
  if [ -n "$BB" ] && "$BB" dmesg >/dev/null 2>&1; then
    "$BB" dmesg 2>/dev/null | grep -- "$LOG_PREFIX" | tail -n "$n" && return 0
  fi
  if command -v logcat >/dev/null 2>&1; then
    # -v epoch：行首就是 epoch 秒，好还原时间；-v brief 没有年份，还原不了
    logcat -b kernel -d -v epoch 2>/dev/null | grep -- "$LOG_PREFIX" | tail -n "$n" && return 0
  fi
  return 1
}

# 解析内核日志行 + IP 反查，输出 TSV：epoch ip domain proto dpt src
blocklog_join() {
  n=${1:-80}
  BE=$(boot_epoch)
  build_uidmap
  blocklog_raw "$n" > "$BLKRAW" 2>/dev/null || return 1
  [ -s "$BLKRAW" ] || return 0
  awk -v map="$IPMAP" -v uidmap="$UIDMAP" -v be="$BE" '
    function label(p) {
      if (p == "com.miui.systemAdSolution") return "智能服务"
      if (p == "com.miui.securitycenter") return "手机管家"
      if (p == "com.miui.guardprovider") return "系统安全组件"
      if (p == "com.mobiletools.systemhelper") return "SystemHelper"
      if (p == "com.miui.msa") return "MSA广告服务"
      if (p == "com.miui.analytics") return "小米统计"
      if (p == "com.android.systemui" || p == "com.miui.systemui") return "系统界面"
      if (p == "com.miui.home") return "系统桌面"
      if (p == "com.android.settings") return "设置"
      if (p == "com.android.phone") return "电话"
      if (p ~ /^com\.xiaomi\./) return "小米应用"
      if (p ~ /^com\.miui\./) return "MIUI 组件"
      if (p ~ /^com\.android\./) return "安卓系统组件"
      if (p ~ /^com\.google\./) return "谷歌服务"
      return ""
    }
    BEGIN {
      while ((getline line < map) > 0) {
        split(line, a, " ")
        if (a[1] != "") dom[a[1]] = a[2]
      }
      close(map)
      while ((getline line < uidmap) > 0) {
        split(line, u, "\t")
        if (u[1] != "") uidpkg[u[1]] = u[2]
      }
      close(uidmap)
    }
    {
      dst=""; proto=""; dpt=""; src=""; uid=""
      for (i=1;i<=NF;i++) {
        if ($i ~ /^DST=/) { dst=$i; sub(/^DST=/,"",dst) }
        else if ($i ~ /^SRC=/) { src=$i; sub(/^SRC=/,"",src) }
        else if ($i ~ /^PROTO=/) { proto=$i; sub(/^PROTO=/,"",proto) }
        else if ($i ~ /^DPT=/) { dpt=$i; sub(/^DPT=/,"",dpt) }
        else if ($i ~ /^UID=/) { uid=$i; sub(/^UID=/,"",uid) }
      }
      if (dst == "") next
      # 内核日志时间戳是两段式的：[  123.456789]，整行提一次就行。
      # 以前按字段找 /^\[/ 只能拿到一个孤立的 "["，结果所有事件都退回成开机时间。
      t = be; have = 0
      if (match($0, /\[[ ]*[0-9]+(\.[0-9]+)?\]/)) {
        ts = substr($0, RSTART + 1, RLENGTH - 2)
        gsub(/[^0-9.]/, "", ts)
        split(ts, q, ".")
        if (q[1] != "") { t = be + q[1]; have = 1 }
      }
      # logcat -v epoch 的行首直接是 epoch 秒
      if (!have) {
        if (split($1, e1, ".") == 2 && length(e1[1]) >= 9 && e1[1] ~ /^[0-9]+$/) { t = e1[1] + 0; have = 1 }
      }
      name = (dst in dom) ? dom[dst] : ""
      pkg = (uid in uidpkg) ? uidpkg[uid] : ""
      app = ""
      if (pkg != "") {
        lb = label(pkg)
        app = (lb != "") ? (lb "（" pkg "）") : pkg
      } else if (uid != "") {
        if (uid == "0") app = "root"
        else if (uid == "1000") app = "system（系统）"
        else if (uid == "1001") app = "radio（电话）"
        else if (uid == "1013") app = "media（媒体）"
        else app = "uid " uid
      }
      # 必须用 TAB：域名可能为空，空格会让下游 read 折叠导致字段错位
      OFS = "\t"
      print t, dst, name, proto, dpt, src, uid, app
    }
  ' "$BLKRAW"
}

### ---------- 备份导出 / 导入 ----------

EXPORT_HEADER='# FJ HostsBlock v1.4 EXPORT'

backup_export() {
  OUT="$1"
  [ -n "$OUT" ] || OUT="/sdcard/Download/FJ_HostsBlock_$(date +%Y%m%d_%H%M%S).bak"
  mkdir -p "$(dirname "$OUT")" 2>/dev/null
  {
    echo "$EXPORT_HEADER"
    echo "### CONFIG"
    cat "$CONF" 2>/dev/null
    echo "### EXTRA"
    cat "$RUN_DIR/extra_hosts" 2>/dev/null
    echo "### BLOCKLIST"
    cat "$BLOCKLIST" 2>/dev/null
    echo "### END"
  } > "$OUT" 2>/dev/null
  [ -s "$OUT" ] || return 1
  echo "$OUT"
}

backup_import() {
  SRC=$1
  [ -f "$SRC" ] || return 1
  head -1 "$SRC" | grep -q 'FJ HostsBlock' || return 2

  awk -v d="$RUN_DIR" '
    /^### CONFIG/ {m="config"; next}
    /^### EXTRA/ {m="extra"; next}
    /^### BLOCKLIST/ {m="block"; next}
    /^### END/ {m=""; next}
    /^# FJ HostsBlock/ {next}
    m=="config" {print > (d"/.imp_config")}
    m=="extra" {print > (d"/.imp_extra")}
    m=="block" {print > (d"/.imp_block")}
  ' "$SRC"

  if [ -s "$RUN_DIR/.imp_config" ]; then
    grep -E '^(HOSTS_ENABLE|ENFORCE_ENABLE|IPV6_ENABLE|DNS_GUARD|WEBUI_PORT|REFRESH_HOURS|SEG_[A-Z]+)=' "$RUN_DIR/.imp_config" > "$CONF.tmp" 2>/dev/null
    [ -s "$CONF.tmp" ] && mv -f "$CONF.tmp" "$CONF"
  fi
  [ -s "$RUN_DIR/.imp_extra" ] && mv -f "$RUN_DIR/.imp_extra" "$RUN_DIR/extra_hosts"
  [ -s "$RUN_DIR/.imp_block" ] && mv -f "$RUN_DIR/.imp_block" "$BLOCKLIST"
  rm -f "$RUN_DIR/.imp_config" "$RUN_DIR/.imp_extra" "$RUN_DIR/.imp_block" 2>/dev/null

  chmod 0644 "$BLOCKLIST" "$RUN_DIR/extra_hosts" 2>/dev/null
  rebuild_hosts
  return 0
}

resolve_domain() {
  d=$1
  out=""
  used=""
  # 直问 DNS 服务器，绕开本机 hosts
  if bb_has nslookup; then
    for srv in $(all_dns_v4); do
      [ -n "$srv" ] || continue
      out=$("$BB" nslookup "$d" "$srv" 2>/dev/null)
      if echo "$out" | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}'; then used="$srv"; break; fi
    done
  elif command -v nslookup >/dev/null 2>&1; then
    for srv in $(all_dns_v4); do
      [ -n "$srv" ] || continue
      out=$(nslookup "$d" "$srv" 2>/dev/null)
      if echo "$out" | grep -qE '([0-9]{1,3}\.){3}[0-9]{1,3}'; then used="$srv"; break; fi
    done
  else
    "$MODDIR/resolve_fallback.sh" "$d" "$DNS1" 2>/dev/null
    return 0
  fi
  [ -n "$out" ] || return 0
  echo "$out" \
    | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' \
    | grep -vx "$used" \
    | grep -vx '0\.0\.0\.0' \
    | grep -vx '127\.0\.0\.1' \
    | sort -u
}

### ---------- 解析策略：hosts 临时放行 + 系统解析器 ----------
# 适用场景：busybox nslookup 不可用、或自带的 DNS 服务器连不上。
# 原理：把 hosts 临时换回 base（只剩 localhost），系统解析器就能拿到真实 IP，
#       解析完立即恢复，hosts 层的拦截不会丢。

domains_snapshot() {
  [ -f "$HOSTS" ] || return 0
  awk '$1=="0.0.0.0" {print $2}' "$HOSTS" 2>/dev/null | grep -v '^localhost$' | grep -v '^$' | sort -u \
    > "$RUN_DIR/.doms"
  cat "$RUN_DIR/.doms"
}

hosts_release() {
  [ -f "$HOSTS" ] || return 1
  cat "$HOSTS" > "$RUN_DIR/.hosts.full" 2>/dev/null
  if [ -f "$HOSTS_BASE" ]; then cat "$HOSTS_BASE" > "$HOSTS"
  else printf '127.0.0.1 localhost\n::1 localhost\n' > "$HOSTS"; fi
  chmod 0644 "$HOSTS" 2>/dev/null
  chown 0:0 "$HOSTS" 2>/dev/null
  return 0
}

hosts_restore() {
  [ -s "$RUN_DIR/.hosts.full" ] || return 0
  cat "$RUN_DIR/.hosts.full" > "$HOSTS" 2>/dev/null
  chmod 0644 "$HOSTS" 2>/dev/null
  chown 0:0 "$HOSTS" 2>/dev/null
  return 0
}

# 用 ping 从系统解析器拿 IP（hosts 必须处于放行状态）
resolve_via_ping() {
  # 只要 ping 打到第一行 PING 就够了（那里就写着解出的地址），
  # 不需要等它回包。用 timeout 硬卡 1 秒，否则 90 个域名会拖到十几分钟。
  out=""
  if command -v timeout >/dev/null 2>&1; then
    out=$(timeout 1 ping -c 1 -W 1 "$1" 2>/dev/null)
    [ -n "$out" ] || out=$(timeout 1 ping -c 1 "$1" 2>/dev/null)
  else
    out=$(ping -c 1 -W 1 "$1" 2>/dev/null)
    [ -n "$out" ] || out=$(ping -c 1 -w 1 "$1" 2>/dev/null)
  fi
  [ -n "$out" ] || return 0
  # 纯 cut 取括号里的地址，不依赖 sed 正则，避开转义坑
  ip=$(echo "$out" | grep -i '^PING' | head -1 | cut -d'(' -f2 | cut -d')' -f1 | tr -d ' \t')
  case "$ip" in
    ''|0.0.0.0) return 0 ;;
    *[!0-9.]*) return 0 ;;
  esac
  echo "$ip"
}

resolve6_via_ping() {
  out=$(ping6 -c 1 -W 2 "$1" 2>/dev/null)
  [ -n "$out" ] || out=$(ping -6 -c 1 -W 2 "$1" 2>/dev/null)
  if [ -z "$out" ] && [ -n "$BB" ]; then
    out=$("$BB" ping6 -c 1 -W 2 "$1" 2>/dev/null)
  fi
  [ -n "$out" ] || return 0
  echo "$out" | grep -oE '([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}' \
    | grep -viE '^(::1?|::)$' | grep -viE '^(f[cd][0-9a-f]{2}|fe80)' | sort -u | grep ':'
}

# 探测一次，决定本次用哪条路
pick_strategy() {
  probe=""
  if bb_has nslookup; then
    for srv in $(all_dns_v4); do
      probe=$("$BB" nslookup www.baidu.com "$srv" 2>/dev/null | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' | grep -vx "$srv" | grep -vx '0\.0\.0\.0' | head -1)
      [ -n "$probe" ] && { echo nslookup; return 0; }
    done
  fi
  if command -v ping >/dev/null 2>&1 && command -v awk >/dev/null 2>&1; then
    echo hostsswap; return 0
  fi
  echo none
}

# 直接问 DNS 拿 AAAA
resolve_domain6() {
  d=$1
  out=""
  if bb_has nslookup; then
    for srv in $(all_dns_v4); do
      out=$("$BB" nslookup -type=AAAA "$d" "$srv" 2>/dev/null)
      [ -n "$out" ] && break
    done
  elif command -v nslookup >/dev/null 2>&1; then
    for srv in $(all_dns_v4); do
      out=$(nslookup -type=AAAA "$d" "$srv" 2>/dev/null)
      [ -n "$out" ] && break
    done
  else
    return 0
  fi
  [ -n "$out" ] || return 0
  echo "$out" \
    | grep -oiE '([0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}' \
    | grep -viE '^(::1?|::)$' \
    | grep -viE '^(f[cd][0-9a-f]{2}|fe80)' \
    | sort -u | grep -E ':'
}
