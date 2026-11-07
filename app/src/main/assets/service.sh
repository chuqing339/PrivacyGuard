#!/system/bin/sh
# FJ HostsBlock v1.5  late_start service
# 双栈 IP 层 + DNS 层兜底，全部走模块自带 busybox

MODDIR=${0%/*}
. "$MODDIR/common.sh"

ipt_wait() {
  i=0
  while [ $i -lt 30 ]; do
    iptables -w 5 -L OUTPUT -n >/dev/null 2>&1 && return 0
    sleep 1; i=$((i+1))
  done
  return 1
}

USE_SET=0
USE_SET6=0

# 私网 / 保留 / 回环地址永不下发 IP 规则。
# 常见坑：校园网/企业网/认证页的 DNS 会把查不到的域名劫持成一个内网地址，
# 一旦把这个内网地址（往往是网关或内网 DNS 本身）封掉，整个局域网和 DNS 都会出事。
# 域名层（hosts）已经拦住域名了，这里没必要再封内网 IP。
is_lan_ip() {
  case "$1" in
    10.*|127.*|192.168.*|169.254.*|0.*|22[4-9].*|23[0-9].*|24[0-9].*|25[0-5].*) return 0 ;;
    172.1[6-9].*|172.2[0-9].*|172.3[01].*) return 0 ;;
    100.6[4-9].*|100.[7-9][0-9].*|100.1[01][0-9].*|100.12[0-7].*) return 0 ;;
  esac
  return 1
}
is_lan_ip6() {
  case "$1" in
    ::1|::|fe80:*|fc*|fd*|ff*) return 0 ;;
  esac
  return 1
}
LAN_SKIP_TOLD=""

# 确保 IPv4 链存在并挂到 OUTPUT 上。
# 开机时 xtables 锁常被别的进程（netd / 其他模块）短暂占用，
# 即使 apply_rules 里建链失败，这里也能补建，不至于整条 IP 层报废。
ensure_chain4() {
  iptables -w 5 -S fj_blk_out >/dev/null 2>&1 && return 0
  iptables -w 5 -N fj_blk_out 2>/dev/null
  for net in 127.0.0.0/8 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 100.64.0.0/10; do
    iptables -w 5 -C fj_blk_out -d $net -j RETURN 2>/dev/null || iptables -w 5 -A fj_blk_out -d $net -j RETURN 2>/dev/null
  done
  iptables -w 5 -C OUTPUT -j fj_blk_out 2>/dev/null || iptables -w 5 -I OUTPUT 1 -j fj_blk_out 2>/dev/null
  return 0
}

# 内网豁免规则（RETURN）必须在链首，否则 REJECT 先命中，局域网/DNS 就被封了。
# 不用 -C 判断（部分内核上 -C 会误判），直接看 -S 文本。
ensure_lan_returns() {
  iptables -w 5 -S fj_blk_out >/dev/null 2>&1 || return 0
  [ "$(iptables -w 5 -S fj_blk_out 2>/dev/null | grep -c -- '-d 10.0.0.0/8 -j RETURN')" = "0" ] || return 0
  for net in 100.64.0.0/10 192.168.0.0/16 172.16.0.0/12 10.0.0.0/8 127.0.0.0/8; do
    iptables -w 5 -I fj_blk_out 1 -d $net -j RETURN 2>/dev/null
  done
  log "已补插内网豁免规则（RETURN）"
}

# ---------- 关键域名白名单（IP 层放行） ----------
# 白名单域名解析出的 IP 会在 fj_blk_out 链首插入 RETURN，
# 防止这些 IP（如 GitHub 边缘 IP）与挡表里本士广告域名的 IP 撞车而被连带 REJECT。
ensure_wl_chain() {
  wb_on || return 0
  iptables -w 5 -S fj_wl_out >/dev/null 2>&1 || iptables -w 5 -N fj_wl_out 2>/dev/null
  iptables -w 5 -C fj_blk_out -j fj_wl_out 2>/dev/null || iptables -w 5 -I fj_blk_out 1 -j fj_wl_out 2>/dev/null
  return 0
}

WL_APPLIED=""
apply_whitelist_ips() {
  wb_on || return 0
  [ "$(conf_get ENFORCE_ENABLE)" = "1" ] || return 0
  [ -n "$WL_APPLIED" ] && return 0   # 本轮已解析过，不再重复（refresh 里 emit 会被调两次）
  WL_APPLIED=1
  ensure_wl_chain
  iptables -w 5 -S fj_wl_out >/dev/null 2>&1 || return 0
  iptables -w 5 -F fj_wl_out 2>/dev/null
  wl_domains | while IFS= read -r d; do
    [ -n "$d" ] || continue
    for ip in $(resolve_domain "$d" 2>/dev/null); do
      case "$ip" in ''|0.0.0.0|127.*) continue ;; esac
      iptables -w 5 -A fj_wl_out -d "$ip" -j RETURN 2>/dev/null
    done
  done
  return 0
}

init_sets() {
  USE_SET=0; USE_SET6=0
  find_ipset
  if [ -n "$IPSET" ]; then
    $IPSET create $SETNAME hash:ip family inet -exist 2>/dev/null && USE_SET=1
    if [ "$(conf_get IPV6_ENABLE)" = "1" ]; then
      $IPSET create $SETNAME6 hash:ip family inet6 -exist 2>/dev/null && USE_SET6=1
    fi
  fi
  if [ "$USE_SET" = "1" ]; then echo ipset > "$RUN_DIR/.ipset_mode"; else echo iptables > "$RUN_DIR/.ipset_mode"; fi
  if [ "$USE_SET6" = "1" ]; then echo ipset > "$RUN_DIR/.ipset_mode6"; else echo ip6tables > "$RUN_DIR/.ipset_mode6"; fi
}

flush_rules() {
  if iptables -w 5 -S 2>/dev/null | grep -q 'fj_blk_out'; then
    iptables -w 5 -D OUTPUT -j fj_blk_out 2>/dev/null
    iptables -w 5 -F fj_blk_out 2>/dev/null
    iptables -w 5 -X fj_blk_out 2>/dev/null
  fi
  if command -v ip6tables >/dev/null 2>&1 && ip6tables -w 5 -S 2>/dev/null | grep -q 'fj_blk6_out'; then
    ip6tables -w 5 -D OUTPUT -j fj_blk6_out 2>/dev/null
    ip6tables -w 5 -F fj_blk6_out 2>/dev/null
    ip6tables -w 5 -X fj_blk6_out 2>/dev/null
  fi
  # 不能依赖 USE_SET：apply_rules 里 flush 跑在 init_sets 之前，那时还是 0
  if [ -n "$IPSET" ] && $IPSET list $SETNAME >/dev/null 2>&1; then
    $IPSET flush $SETNAME 2>/dev/null
    $IPSET flush $SETNAME6 2>/dev/null
  fi
  # 白名单链也一并清掉（关闭白名单时不能残留旧 RETURN 规则）
  iptables -w 5 -F fj_wl_out 2>/dev/null; iptables -w 5 -X fj_wl_out 2>/dev/null
  rm -f "$RUN_DIR/.ipset_mode" "$RUN_DIR/.ipset_mode6" "$RUN_DIR/.dns_ips" 2>/dev/null
  iptables -w 5 -F fj_logtest 2>/dev/null; iptables -w 5 -X fj_logtest 2>/dev/null
  # 【关键】不再保留上一轮台账。
  # 以前这里会把旧台账存底、解析为空时再恢复，结果「你刚关掉的段」会在下一轮
  # 网络抖动/刷新时整套 IP 规则被复活——这就是「开关是关的、实际却还在拦」的根因。
  # 现在彻底不沿用旧台账：只按当前块表/开关重新解析，解析失败就是空（宁可少拦，绝不复活已关的）。
  rm -f "$RUN_DIR/.ips4.prev" "$RUN_DIR/ipmap.prev" 2>/dev/null
  : > "$RUN_DIR/.ips4" 2>/dev/null
  ipmap_reset
}

apply_rules() {
  [ "$(conf_get ENFORCE_ENABLE)" = "1" ] || { log "ENFORCE 关，跳过 IP 层拦截"; return 0; }
  ipt_wait || { log "iptables 未就绪，跳过 IP 层拦截"; return 1; }

  flush_rules
  init_sets

  # ---- IPv4 ----
  # 建链用 -w 等锁 + 最多重试 8 次（共约 40 秒）。
  # 开机早期 netd / 其他模块会长时间占用 xtables 锁，不加 -w 就会像日志里那样
  # “Can't lock xtables.lock: Try again”，建链失败，后续所有规则全报 No chain。
  ok=0
  for _try in 1 2 3 4 5 6 7 8; do
    if iptables -w 5 -N fj_blk_out 2>/dev/null; then ok=1; break; fi
    iptables -w 5 -S fj_blk_out >/dev/null 2>&1 && { ok=1; break; }
    sleep 5
  done
  if [ "$ok" != "1" ]; then
    log "! 建链 fj_blk_out 失败（重试 8 次仍被 xtables 锁占用），IP 层本轮跳过"
  fi
  # 局域网/回环先放行，保住宅内 DNS 与本地代理
  for net in 127.0.0.0/8 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 100.64.0.0/10; do
    iptables -w 5 -C fj_blk_out -d $net -j RETURN 2>/dev/null || iptables -w 5 -A fj_blk_out -d $net -j RETURN 2>/dev/null
  done
  if [ "$USE_SET" = "1" ]; then
    # ipset 模式也要有 LOG，否则看不到拦了什么
    if [ "$(conf_get LOG_ENABLE)" = "1" ]; then
      log_uid_flag
      iptables -w 5 -A fj_blk_out -m set --match-set $SETNAME dst -m limit --limit 30/min --limit-burst 10 -j LOG --log-prefix "$LOG_PREFIX " --log-level 4 $LOG_UID 2>/dev/null
    fi
    iptables -w 5 -A fj_blk_out -m set --match-set $SETNAME dst -j REJECT --reject-with icmp-port-unreachable 2>/dev/null
    if ! iptables -w 5 -C fj_blk_out -m set --match-set $SETNAME dst -j REJECT --reject-with icmp-port-unreachable 2>/dev/null; then
      log "xt_set 不可用，IPv4 降级直写"
      iptables -w 5 -F fj_blk_out 2>/dev/null
      USE_SET=0
      echo iptables > "$RUN_DIR/.ipset_mode"
    fi
  fi
  err=$(iptables -w 5 -I OUTPUT 1 -j fj_blk_out 2>&1)
  [ -n "$err" ] && log "! iptables -I OUTPUT 失败: $err"
  if iptables -w 5 -C OUTPUT -j fj_blk_out >/dev/null 2>&1; then
    log "IPv4 模式 $(cat "$RUN_DIR/.ipset_mode" 2>/dev/null)"
  else
    err=$(iptables -w 5 -C OUTPUT -j fj_blk_out 2>&1)
    log "! IPv4 OUTPUT 里找不到 fj_blk_out（iptables 报错: $err）"
    log "! 环境：$(iptables -V 2>&1 | head -1) | selinux=$(getenforce 2>/dev/null)"
  fi

  # 白名单链：确保 fj_wl_out 已建且掛在 fj_blk_out 链首
  ensure_wl_chain

  # ---- IPv6 ----
  if [ "$(conf_get IPV6_ENABLE)" = "1" ] && command -v ip6tables >/dev/null 2>&1; then
    ip6tables -w 5 -N fj_blk6_out 2>/dev/null
    for net in ::1/128 fe80::/10 fc00::/7; do
      ip6tables -w 5 -C fj_blk6_out -d $net -j RETURN 2>/dev/null || ip6tables -w 5 -A fj_blk6_out -d $net -j RETURN 2>/dev/null
    done
    if [ "$USE_SET6" = "1" ]; then
      ip6tables -w 5 -A fj_blk6_out -m set --match-set $SETNAME6 dst -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null
      if ! ip6tables -w 5 -C fj_blk6_out -m set --match-set $SETNAME6 dst -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null; then
        log "xt_set 不可用，IPv6 降级直写"
        ip6tables -w 5 -F fj_blk6_out 2>/dev/null
        USE_SET6=0
        echo ip6tables > "$RUN_DIR/.ipset_mode6"
      fi
    fi
    ip6tables -w 5 -I OUTPUT 1 -j fj_blk6_out 2>/dev/null
    log "IPv6 模式 $(cat "$RUN_DIR/.ipset_mode6" 2>/dev/null)"
  fi
}

# 错误只报一次，否则一百个域名能刷爆日志
E4_LOGGED=0
E6_LOGGED=0

# 探测内核 LOG 是否支持 --log-uid（能把发包进程 UID 写进日志，WebUI 靠它显示应用名）。
# 必须以普通语句调用（用 $(...) 会丢全局值，导致每条规则都重新探测）。
LOG_UID=""
LOG_UID_SET=""
log_uid_flag() {
  [ -n "$LOG_UID_SET" ] && return 0
  LOG_UID_SET=1
  iptables -w 5 -N fj_logtest 2>/dev/null
  if iptables -w 5 -A fj_logtest -m limit --limit 1/min -j LOG --log-prefix "FJTEST " --log-level 4 --log-uid 2>/dev/null; then
    LOG_UID="--log-uid"
    log "内核 LOG 支持 --log-uid，拦截事件可显示来源应用"
  else
    LOG_UID=""
    log "内核 LOG 不支持 --log-uid，拦截事件不显示应用名"
  fi
  iptables -w 5 -F fj_logtest 2>/dev/null
  iptables -w 5 -X fj_logtest 2>/dev/null
}

# 同一组匹配下发 LOG（可选，带限速）+ REJECT。
# 注意两点：
#   1. 必须 LOG 在前。包一撞 REJECT 就终止遍历，后面的 LOG 永远轮不上。
#   2. -m limit 必须写在 -j LOG 之前。写在后面会被当成 LOG 的目标参数，限速可能不生效，
#      结果一个 DNS 查询就刷一行，日志被刷爆（你就看到了 80 多条）。
add_chain_rule() {
  if [ "$(conf_get LOG_ENABLE)" = "1" ]; then
    log_uid_flag
    iptables -w 5 -C fj_blk_out $1 -m limit --limit 30/min --limit-burst 10 -j LOG --log-prefix "$LOG_PREFIX " --log-level 4 $LOG_UID 2>/dev/null \
      || iptables -w 5 -A fj_blk_out $1 -m limit --limit 30/min --limit-burst 10 -j LOG --log-prefix "$LOG_PREFIX " --log-level 4 $LOG_UID 2>/dev/null
  fi
  err=$(iptables -w 5 -C fj_blk_out $1 -j REJECT --reject-with icmp-port-unreachable 2>/dev/null \
    || iptables -w 5 -A fj_blk_out $1 -j REJECT --reject-with icmp-port-unreachable 2>&1)
  if [ -n "$err" ]; then
    [ "$E4_LOGGED" = "0" ] && { log "! 写规则失败: $err"; E4_LOGGED=1; }
    return 1
  fi
  return 0
}

# 直接模式下只记账，统一下发（ipset 模式下直接进集合）
add_ip4() {
  case "$1" in ''|*[!0-9.]*) return 1 ;; esac
  if is_lan_ip "$1"; then
    if [ -z "$LAN_SKIP_TOLD" ]; then
      log "跳过内网/保留地址（不下发 IP 规则）：解析到 $1，hosts 层已拦域名，封内网 IP 会伤到网关/DNS"
      LAN_SKIP_TOLD=1
    fi
    return 1
  fi
  # 【关键】无论哪种模式都写台账。ipset 模式下台账用于 sync_sets 重建集合，
  # 旧代码在 ipset 模式直接 return，导致 .ips4 为空、sync 无法清理已关闭的 IP。
  echo "$1" >> "$RUN_DIR/.ips4" 2>/dev/null
  if [ "$USE_SET" = "1" ] && [ -n "$IPSET" ]; then
    $IPSET add $SETNAME "$1" -exist 2>/dev/null
  fi
  return 0
}

emit_ip4_rules() {
  ensure_chain4
  # 白名单放行：链首 RETURN（两种模式都要，且要在早返回之前）
  ensure_wl_chain
  apply_whitelist_ips
  # ipset 模式走集合匹配，不再逐条写 iptables 规则（避免和集合双份拦截、污染计数器）
  [ "$USE_SET" = "1" ] && return 0
  [ -s "$RUN_DIR/.ips4" ] || return 0
  sort -u "$RUN_DIR/.ips4" > "$RUN_DIR/.ips4.u" 2>/dev/null && mv -f "$RUN_DIR/.ips4.u" "$RUN_DIR/.ips4"
  while IFS= read -r ip; do
    [ -n "$ip" ] || continue
    add_chain_rule "-d $ip"
  done < "$RUN_DIR/.ips4"
}

# ipset 模式：集合不会像 iptables-直接模式那样被 flush_rules 一并清空，
# 旧规则会在集合里「超生」；这里按当前台账重建集合，保证与块表/开关一致。
sync_sets() {
  [ "$(conf_get ENFORCE_ENABLE)" = "1" ] || return 0
  [ -n "$IPSET" ] || return 0
  if [ "$(cat "$RUN_DIR/.ipset_mode" 2>/dev/null)" = "ipset" ]; then
    $IPSET flush $SETNAME 2>/dev/null
    [ -s "$RUN_DIR/.ips4" ] && while IFS= read -r ip; do
      [ -n "$ip" ] || continue
      $IPSET add $SETNAME "$ip" -exist 2>/dev/null
    done < "$RUN_DIR/.ips4"
  fi
  return 0
}

add_ip6() {
  case "$1" in ''|*[!0-9a-fA-F:]*) return 1 ;; esac
  is_lan_ip6 "$1" && return 1
  if [ "$USE_SET6" = "1" ] && [ -n "$IPSET" ]; then
    $IPSET add $SETNAME6 "$1" -exist 2>/dev/null && return 0
  fi
  ip6tables -w 5 -C fj_blk6_out -d "$1" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null && return 0
  err=$(ip6tables -w 5 -A fj_blk6_out -d "$1" -j REJECT --reject-with icmp6-adm-prohibited 2>&1)
  if [ -n "$err" ]; then
    [ "$E6_LOGGED" = "0" ] && { log "! 写 IPv6 规则失败: $err"; E6_LOGGED=1; }
    return 1
  fi
  return 0
}

resolve_all() {
  [ "$(conf_get ENFORCE_ENABLE)" = "1" ] || return 0

  DOMS=$(domains_snapshot)
  if [ -z "$DOMS" ]; then log "无域名可解析"; return 0; fi

  STRAT=$(pick_strategy)
  log "解析策略: $STRAT"

  # 本次解析的 IP 台账与 IP→域名映射，日志反查靠它
  ipmap_reset
  : > "$RUN_DIR/.ips4" 2>/dev/null

  case "$STRAT" in
    hostsswap)
      # 把 hosts 换成 base，让系统解析器能拿到真实 IP（否则读到的就是我们写的 0.0.0.0）
      hosts_release
      sleep 1
      ;;
    none)
      log "! 无可用解析手段，IP 层跳过（hosts 层仍在工作）"
      [ "$STRAT" = "none" ] && log "! 提示：跑 service.sh diag 看 nslookup 实测"
      return 0
      ;;
  esac

  n4=0; n6=0
  for d in $DOMS; do
    if [ "$STRAT" = "hostsswap" ]; then
      ips=$(resolve_via_ping "$d")
      ips6=$(resolve6_via_ping "$d")
    else
      ips=$(resolve_domain "$d")
      ips6=$(resolve_domain6 "$d")
    fi
    for ip in $ips; do
      [ -n "$ip" ] || continue
      ipmap_add "$ip" "$d"
      add_ip4 "$ip" && n4=$((n4+1))
    done
    if [ "$(conf_get IPV6_ENABLE)" = "1" ] && command -v ip6tables >/dev/null 2>&1; then
      for ip in $ips6; do
        [ -n "$ip" ] || continue
        add_ip6 "$ip" && n6=$((n6+1))
      done
    fi
  done

  [ "$STRAT" = "hostsswap" ] && hosts_restore

  # 不再「沿用上一轮台账」：哪怕本轮 0 条，也如实下发当前结果，
  # 避免已关闭的段被旧 IP 复活（见 flush_rules 注释）。

  # 两段式下发：先全部 LOG，再全部 REJECT；ipset 模式则按台账重建集合
  emit_ip4_rules
  sync_sets

  echo "$n4" > "$RUN_DIR/.last_n4" 2>/dev/null
  echo "$n6" > "$RUN_DIR/.last_n6" 2>/dev/null
  log "解析完成：IPv4 $n4 条，IPv6 $n6 条（策略 $STRAT）"
}

# ---------- 网络就绪判定与重试 ----------
# 开机 late_start 阶段网络往往还没连上，那时 nslookup 和 ping 全失败，
# 结果就是 0 条。这不是解析器的问题，是时机问题。
net_ready() {
  # 注意：Android 走策略路由，默认路由在 table 1002/1015 那些副表里，
  # 主表 ip route 经常是空的，拿它判断“没网”是错的。
  # 直接以“能不能解出域名”为准，这才是我们真正关心的事。
  [ "$(pick_strategy)" != "none" ]
}

wait_net() {
  t=0
  while [ "$t" -lt "$1" ]; do
    net_ready && return 0
    sleep 2
    t=$((t+2))
  done
  return 1
}

retry_resolve() {
  LK="$RUN_DIR/.retry.lock"
  if [ -f "$LK" ]; then
    p=$(cat "$LK" 2>/dev/null)
    [ -n "$p" ] && kill -0 "$p" 2>/dev/null && exit 0
  fi
  echo $$ > "$LK"
  i=0
  while [ "$i" -lt 15 ]; do
    i=$((i+1))
    sleep 20
    n=$(cat "$RUN_DIR/.last_n4" 2>/dev/null)
    case "$n" in ''|0|*[!0-9]*) : ;; *) log "解析重试结束，已有 $n 条 IPv4"; break ;; esac
    net_ready || continue
    log "网络就绪，解析重试第 $i 次"
    resolve_all
    if [ "$(cat "$RUN_DIR/.last_n4" 2>/dev/null)" != "0" ] && [ -n "$(cat "$RUN_DIR/.last_n4" 2>/dev/null)" ]; then
      log "解析重试成功，IP 层已就绪"
      dns_guard >/dev/null 2>&1
      break
    fi
  done
  rm -f "$LK" 2>/dev/null
}

# DNS 兜底：把已知公共 DNS 的 IP 也写进同一张表，顺便拦 853/5353 常见加密解析端口
dns_guard() {
  [ "$(conf_get DNS_GUARD)" = "1" ] || { log "DNS 兜底关，跳过"; return 0; }
  [ "$(conf_get ENFORCE_ENABLE)" = "1" ] || return 0

  ensure_lan_returns
  n4=0; n6=0
  # DNS 兜底台账：排行榜里给这类命中打上「DNS 服务器」标签，避免用户以为是广告域名被拦
  : > "$RUN_DIR/.dns_ips" 2>/dev/null
  for ip in $(dns_targets_v4); do
    [ -n "$ip" ] || continue
    # 跳掉模块自己用的解析器，否则下轮 refresh 的 nslookup 会被自己拦死
    [ "$ip" = "$DNS1" ] && continue
    [ "$ip" = "$DNS2" ] && continue
    echo "$ip" >> "$RUN_DIR/.dns_ips" 2>/dev/null
    add_ip4 "$ip" && n4=$((n4+1))
  done
  emit_ip4_rules
  sync_sets

  # 只拦 DoT 853 与 mDNS 5353；明文 53 不动，否则路由器/运营商 DNS 一断全机没网
  for p in 853 5353; do
    add_chain_rule "-p udp --dport $p"
    add_chain_rule "-p tcp --dport $p"
  done

  if [ "$(conf_get IPV6_ENABLE)" = "1" ] && command -v ip6tables >/dev/null 2>&1; then
    for ip in $(dns_targets_v6); do
      [ -n "$ip" ] || continue
      if [ "$USE_SET6" = "1" ]; then
        $IPSET add $SETNAME6 "$ip" -exist 2>/dev/null
      else
        ip6tables -w 5 -C fj_blk6_out -d "$ip" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null \
          || ip6tables -w 5 -A fj_blk6_out -d "$ip" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null
      fi
      n6=$((n6+1))
    done
    for p in 853 5353; do
      ip6tables -w 5 -C fj_blk6_out -p udp --dport $p -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null \
        || ip6tables -w 5 -A fj_blk6_out -p udp --dport $p -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null
    done
  fi
  log "DNS 兜底完成：IPv4 $n4 个，IPv6 $n6 个，已拦 853/5353"
}

start_daemon() {
  [ -x "$MODDIR/daemon.sh" ] && "$MODDIR/daemon.sh" >/dev/null 2>&1 &
  [ -x "$MODDIR/webserver.sh" ] && "$MODDIR/webserver.sh" >/dev/null 2>&1 &
  return 0
}

run_diag() {
  D="$RUN_DIR/diag.txt"
  DOMS_PROBE=$(domains_snapshot)
  {
    echo "模块版本: $(cat "$MODDIR/module.prop" 2>/dev/null | grep -m1 '^version=')"
    echo "==== FJ HostsBlock diag $(date '+%F %T') ===="
    echo "--- 身份与环境 ---"
    id 2>&1
    echo "selinux=$(getenforce 2>&1)"
    echo "context=$(cat /proc/self/attr/current 2>&1)"
    uname -a 2>&1
    echo "mount flags: $(mount 2>/dev/null | grep -m1 ' /data ' | cut -c1-200)"

    echo "--- busybox ---"
    echo "BB=${BB:-无}"
    [ -f "$RUN_DIR/.bb_src" ] && echo "BB 原路径=$(cat "$RUN_DIR/.bb_src")"
    if [ -n "$BB" ]; then
      "$BB" true 2>&1; echo "true rc=$?"
      for a in nslookup httpd ipset awk sed grep ping nc wget; do
        "$BB" $a --help >/dev/null 2>&1; echo "applet $a rc=$?"
      done
      echo "--list: $("$BB" --list 2>/dev/null | tr '\n' ' ' | cut -c1-900)"
    fi

    echo "--- nslookup 实测 ---"
    for srv in $(all_dns_v4); do
      [ -n "$srv" ] || continue
      o=$( [ -n "$BB" ] && "$BB" nslookup www.baidu.com "$srv" 2>&1 | tr '\n' '|' | cut -c1-240 )
      echo "[$srv] $o"
    done

    echo "--- 系统 DNS ---"
    all_dns_v4 | tr '\n' ' '; echo

    echo "--- ping 实测（www.baidu.com 为对照，不在屏蔽表里）---"
    out=$(ping -c 1 -W 2 www.baidu.com 2>&1); echo "ping -W rc=$? -> $(echo "$out" | head -2 | tr '\n' '|')"
    out=$(ping -c 1 -w 2 www.baidu.com 2>&1); echo "ping -w rc=$? -> $(echo "$out" | head -2 | tr '\n' '|')"
    out=$(ping -c 1 www.baidu.com 2>&1); echo "ping 裸 rc=$? -> $(echo "$out" | head -2 | tr '\n' '|')"
    [ -n "$BB" ] && { out=$("$BB" ping -c 1 -W 2 www.baidu.com 2>&1); echo "bb ping rc=$? -> $(echo "$out" | head -2 | tr '\n' '|')"; }

    echo "--- hosts 放行对照实验（核心）---"
    D1=$(echo "$DOMS_PROBE" | head -1)
    [ -n "$D1" ] || D1=flash.sec.miui.com
    echo "被屏蔽域名: $D1"
    out=$(ping -c 1 -W 2 "$D1" 2>&1); echo "  [放行前] rc=$? -> $(echo "$out" | head -1)"
    hosts_release
    sleep 1
    out=$(ping -c 1 -W 2 "$D1" 2>&1); echo "  [放行后] rc=$? -> $(echo "$out" | head -1)"
    out=$(ping -c 1 -W 2 www.baidu.com 2>&1); echo "  [放行后对照 baidu] rc=$? -> $(echo "$out" | head -1)"
    hosts_restore
    echo "  hosts 已恢复"

    echo "--- 内核日志（事件流依赖）---"
    echo "kmsg_state=$(kmsg_state)"
    echo "dmesg 可用=$(command -v dmesg >/dev/null 2>&1 && echo yes || echo no)"
    if command -v dmesg >/dev/null 2>&1; then
      out=$(dmesg 2>&1 | head -n 2 | tr '\n' '|'); echo "  dmesg 输出: $out"
    fi
    iptables -S fj_blk_out 2>&1 | grep -c '^-A' | while read n; do echo "  链内规则数: $n"; done
    echo "  LOG 规则数: $(iptables -S fj_blk_out 2>/dev/null | grep -c '\-j LOG')"
    echo "  kernel 日志里 FJBLK 行数: $(dmesg 2>/dev/null | grep -c FJBLK)"
    echo "  内网豁免(RETURN)规则数: $(iptables -S fj_blk_out 2>/dev/null | grep -c -- '-j RETURN')"
    echo "  LOG 带 --log-uid 的规则数: $(iptables -S fj_blk_out 2>/dev/null | grep -c -- '--log-uid')"
    echo "  fj_blk_out 前 6 条（顺序很关键）:"
    iptables -S fj_blk_out 2>/dev/null | head -n 6 | sed 's/^/    /'
    echo "  REJECT 规则数: $(iptables -S fj_blk_out 2>/dev/null | grep -c '\-j REJECT')"
    echo "  REJECT 计数器非零项:"
    iptables -L fj_blk_out -v -n -x 2>/dev/null | awk '$3=="REJECT" && $1+0>0 {print "    " $1, $2, $NF}' | head -n 8

    echo "--- CGI 实测 ---"
    P=$(conf_get WEBUI_PORT); case "$P" in ''|*[!0-9]*) P=8686 ;; esac
    echo "手工跑 CGI:"
    # 关键：先清掉继承来的 CGI 环境变量。
    # 如果不清理，内层 control.sh 会读到上层（act=diag）的 QUERY_STRING，把 diag 再跑一遍。
    ( unset QUERY_STRING GATEWAY_INTERFACE REQUEST_METHOD; "$MODDIR/webroot/cgi-bin/control.sh" 'act=status' ) 2>&1 | head -c 400; echo
    echo "经 httpd 请求:"
    [ -n "$BB" ] && "$BB" wget -O - "http://127.0.0.1:$P/cgi-bin/control.sh?act=status" 2>&1 | head -c 400; echo
    echo "cgi.err 尾 20 行:"
    tail -n 20 "$RUN_DIR/cgi.err" 2>&1

    echo "--- iptables ---"
    iptables -V 2>&1 | head -1
    echo "OUTPUT 前 12 条:"
    iptables -w 5 -S OUTPUT 2>&1 | head -12
    echo "fj_blk_out 前 8 条:"
    iptables -w 5 -S fj_blk_out 2>&1 | head -8

      echo "--- iptables 写入实测（这个是关键）---"
    e=$(iptables -w 5 -N fj_diagtest 2>&1); echo "N rc=$? err=[$e]"
    e=$(iptables -w 5 -I OUTPUT 1 -j fj_diagtest 2>&1); echo "I rc=$? err=[$e]"
    iptables -w 5 -C OUTPUT -j fj_diagtest >/dev/null 2>&1; echo "C rc=$?"
    iptables -w 5 -D OUTPUT -j fj_diagtest 2>/dev/null; iptables -w 5 -X fj_diagtest 2>/dev/null

    echo "--- 端口/进程 ---"
    [ -n "$BB" ] && "$BB" netstat -ltn 2>/dev/null | head -8
    ps -A 2>/dev/null | grep -E 'httpd|nginx' | head -5

    echo "--- 模块文件 ---"
    ls -lZ "$MODDIR/bin" 2>&1
    ls -l "$MODDIR/webroot" "$MODDIR/webroot/cgi-bin" 2>&1
    echo "--- hosts 头 12 行 ---"
    head -12 "$HOSTS" 2>&1
    echo "--- 日志尾 30 行 ---"
    tail -30 "$LOG" 2>&1
    echo "==== end ===="
  } > "$D" 2>&1
  log "诊断已写入 $D"
  echo "==== diag ($D) ===="
  cat "$D" 2>/dev/null
}

case "$1" in
  refresh)
    rebuild_hosts
    apply_rules; resolve_all; dns_guard; start_daemon
    ;;
  rebuild)
    rebuild_hosts
    ;;
  dnsguard)
    dns_guard
    ;;
  resolveonly)
    apply_rules; resolve_all; dns_guard
    ;;
  diag)
    run_diag
    ;;
  stop)
    flush_rules
    rm -f "$RUN_DIR/.daemon.lock" "$RUN_DIR/.web.lock" 2>/dev/null
    log "规则已清空，守护已停"
    ;;
  *)
    log "busybox: ${BB:-无} | ipset: ${IPSET:-无} | selinux=$(getenforce 2>/dev/null) | caller=$(cat /proc/$PPID/comm 2>/dev/null)"
    apply_rules
    if wait_net 45; then
      resolve_all
    else
      log "等待网络就绪超时，先记账，后台重试接上"
      echo 0 > "$RUN_DIR/.last_n4" 2>/dev/null
    fi
    dns_guard; start_daemon
    n=$(cat "$RUN_DIR/.last_n4" 2>/dev/null)
    case "$n" in ''|0|*[!0-9]*) "$MODDIR/service.sh" retryresolve >/dev/null 2>&1 & log "已拉起后台解析重试" ;; esac
    ;;
  retryresolve)
    retry_resolve
    ;;
esac
exit 0
