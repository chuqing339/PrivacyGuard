# FJ HostsBlock (v2.5)

Magisk / KernelSU / APatch 模块，四层拦截广告与遥测，带 WebUI。

> 一句话原理：**先按块表把广告域名解析到 0.0.0.0（hosts 层），再把解析出的真实 IP 在 iptables 里封掉（IP 层）**，双保险；同时对 IPv6 和公共加密 DNS 做兜底。

## 四层拦截

1. **Hosts 层** — 按块表生成 `system/etc/hosts`，广告域名直接指 `0.0.0.0`，App 拿不到 IP。默认策略。
2. **IPv4 层** — `iptables`（或 `ipset`）封掉解析出的真实 IP，治硬编码 IP 与绕过 hosts 的直连。
3. **IPv6 层** — `ip6tables` 双栈同封，防走 v6 绕过。
4. **DNS 兜底** — 封公共 DNS 服务器 IP 与 DoT 853 / mDNS 5353，治 App 自建解析。**明文 53 不封**（否则路由器/运营商 DNS 一断就全机没网）。

四层是**串联**的：绝大多数请求在 hosts 层就被拦掉（包根本发不出去），所以「拦截事件流稀疏」是正常的；IP 层只兜底硬编码 IP / 自建 DNS 这类绕过。

## 关键域名白名单（防误伤）

`whitelist.txt` 里的域名**永不屏蔽**：

- 不会写进 hosts 挡表；
- 解析出的 IP 会在 `fj_blk_out` 链首 `RETURN` 放行（该 IP 同时也在挡表里时也不拦）。

用途：保护 **LSPosed 在线模块更新、GitHub、系统更新、小米账号**等关键功能，避免它们的 IP 与广告域名的 IP 撞车时被连带 REJECT（这就是「LSPosed 在线更新被屏蔽、关广告段就好了」的根因）。

默认已内置 GitHub 系（`github.com` / `api.github.com` / `codeload.github.com` / `raw.githubusercontent.com` / `objects.githubusercontent.com` / `jsdelivr.net` 等）、`account.xiaomi.com` / `update.miui.com`、`dl.google.com`。可以在 WebUI「块表」页最下面的白名单卡片里增删，或直接编辑 `/data/adb/modules/FJ_HostsBlock/whitelist.txt`。

## 块表分段

内置六段（WebUI 段落开关控制，手改 `config` 里的 `SEG_XXX` 也生效）：

| 段 | 内容 |
|---|---|
| `telemetry` | 小米多媒体/视频回传、手机管家数据上报、联通终端遥测 |
| `antifraud` | MIUI 反诈特征上报、信通院统一反诈接口 |
| `riskgate` | 第三方风控网关 |
| `miuiad` | MIUI 广告/曝光/点击监测、智能服务（Zeus/Mimo）广告接口 |
| `doh` | 公共加密 DNS 入口（配合 DNS 兜底封 IP） |
| `adsdk` | 通用广告 SDK（友盟/腾讯广点通/Google Ads/AppLovin/Unity/Adjust/Branch 等） |

关闭某段后：hosts 立即重建，同时**同步整表重建 IP 层**（不再沿用旧台账，关掉的段绝不会「过一会自己复活」）。

## WebUI

- **浏览器**：自带 busybox httpd，`http://127.0.0.1:8686`（首选端口，被占自动换备用端口并会迁回；页面内显示的为准）。
- **管理器内置 WebUI**（KernelSU / APatch / Kitsune）：走 `ksu.exec` 桥。

右上角可切**亮色/暗色主题**（记忆选择）。Kitsune/管理器里还有「浏览器打开」按钮。

页签：

- **状态** — 四个总开关（Hosts / IPv4 / IPv6 / DNS）、拦截计数、环境自检、重刷、查日志、一键诊断、单跑 DNS。
- **块表** — 分段开关（可展开看每段域名、增减、恢复内置）、自定义域名、**关键域名白名单**。
- **拦截** — 事件流（按目标+类型+应用聚合，显示来源应用，默认 20 组可展开）与命中排行（读 iptables 计数器，不依赖内核日志）。
- **隐私** — 采集宿主服务（SystemHelper 等）禁用/限制、运行中采集进程、能力边界说明。
- **备份** — 导出/导入/删除配置。

## 出问题先跑诊断

```sh
su -c "/data/adb/modules/FJ_HostsBlock/service.sh diag"
cat /data/adb/FJ_HostsBlock/diag.txt
```

会 dump：busybox applet 表、nslookup 实测、iptables 真实报错、SELinux 上下文、系统 DNS、端口占用、内核日志可读性、内网豁免/白名单/LOG 规则数等。

## 命令

```sh
M=/data/adb/modules/FJ_HostsBlock
su -c "$M/service.sh refresh"     # 重建 hosts + 重解析 IP + DNS 兜底
su -c "$M/service.sh resolveonly" # 只重建 IP 层（含 LOG/REJECT 两段式下发）
su -c "$M/service.sh dnsguard"    # 只跑 DNS 兜底
su -c "$M/service.sh rebuild"     # 只重建 hosts
su -c "$M/service.sh diag"        # 诊断 → /data/adb/FJ_HostsBlock/diag.txt
su -c "$M/service.sh stop"        # 清空全部规则

# 后端直调（浏览器 CGI 与桥模式都能用）
su -c "$M/webroot/cgi-bin/control.sh 'act=status'"
su -c "$M/webroot/cgi-bin/control.sh 'act=seg&id=adsdk&val=0'"
su -c "$M/webroot/cgi-bin/control.sh 'act=wl_add&d=github.com'"
```

## 文件

| 路径 | 说明 |
|---|---|
| `/data/adb/modules/FJ_HostsBlock/bin/busybox` | 内置 busybox（AArch64 静态），DNS 查询/httpd/ipset 都靠它 |
| `/data/adb/modules/FJ_HostsBlock/blocklist.txt` | 屏蔽块表（段落式） |
| `/data/adb/modules/FJ_HostsBlock/whitelist.txt` | 关键域名白名单（永不屏蔽） |
| `/data/adb/FJ_HostsBlock/config` | 开关配置 |
| `/data/adb/FJ_HostsBlock/extra_hosts` | 自定义屏蔽域名 |
| `/data/adb/FJ_HostsBlock/ipset.log` | 运行日志（超 1000 行自动截断，守护每 30 分钟兜底轮转） |
| `/data/adb/FJ_HostsBlock/cgi.err` | CGI/WebUI 调试日志（超 128KB 只留尾部 32KB，定时轮转） |
| `/data/adb/FJ_HostsBlock/backup/` | 备份目录 |

日志不会无限增长：`ipset.log` 超 2000 行截断到 1000，`cgi.err` 超 128KB 截断到 32KB；`daemon.sh` 每分钟醒一次、每 30 分钟轮转一次，即使无活动也会定时清理。

## 已知设计取舍

- **去掉了「解析为空时沿用上一轮 IP 台账」**。若某次刷新恰好断网、一个 IP 都没解出，那一轮 IP 层会暂时为空（hosts 层仍在），网络恢复的下一轮自动重建。这是刻意的：**宁可短暂少一层 IP 拦截，也绝不让已关闭的段自己复活**。
- **推送类广告拦不住**：智能服务等的推送广告走小米推送(MiPush)通道，封通道会误伤正常推送；这类只能在系统设置里关通知/广告开关。
- **IP 层按域名解析结果封 IP**：同一 CDN 上如果同时有广告域名和正常域名，可能连带误伤；遇到就把对应域名加进白名单。

## 安装 / 卸载

- 安装：`Magisk / KernelSU → 模块 → 从本地安装 → 选 FJ_HostsBlock_vX.zip → 重启`。
- 卸载：模块页卸载即可。卸载脚本会清 `iptables/ip6tables` 残留链（含白名单链）与 `ipset`，并恢复本模块禁用的系统包（记在 `.disabled_pkgs`）。备份与日志默认保留，要彻底清手动删 `/data/adb/FJ_HostsBlock`。

## 版本要点（近期）

- v2.5：
- 新增关键域名白名单（治 LSPosed/GitHub 在线更新被 IP 层误伤）。
- 新增隐私，采集宿主服务（SystemHelper 等）禁用/限制、运行中采集进程、能力边界说明。
- 修「关闭段后 IP 规则自己复活」（去掉沿用旧台账 + ipset 按台账同步 + 关闭段同步重建）。
- viptables/ip6tables 全部 `-w` 等锁 + 建链重试，修开机 xtables 锁竞争导致 IP 层建链失败。
- `ipset.log` / `cgi.err` 定时轮转，防止无限增长。
- 修 `act=diag` 无限递归（浏览器点诊断会卡死整机）。
- 拦截事件流按应用聚合、显示来源应用；修 LOG 限速位置错误导致日志刷爆。
