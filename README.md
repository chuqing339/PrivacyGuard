# 隐私守卫 (PrivacyGuard)

> **专为小米手机 (HyperOS / MIUI) 深度打造的 Root 级隐私护盾与反遥测武器**  
> 彻底阻断厂商隐秘偷跑、反诈特征哈希上报、多媒体回传、智能服务追踪与运营商探针。

---

## 📱 痛点背景与诞生初衷

近年来，主流安卓手机系统在后台不断增加对用户个人隐私的采集与上传机制，尤其以小米 HyperOS / MIUI 为甚：
1. **多媒体与视频私密遥测**：安全中心在后台频繁将相册、多媒体分析及设备行为日志回传（`flash.sec.miui.com`、`data.sec.miui.com` 等）。
2. **反诈特征与应用哈希秘密上报**：在用户安装/运行应用时，向信通院及反诈接口（`fraud-detection.engine.miui.com`、`eps.caict.ac.cn`）上传 APK 签名、特征指纹。
3. **广告曝光追踪与智能服务**：智能服务（`com.miui.systemAdSolution`）在各个系统应用（浏览器、应用商店、主题等）中强塞开屏广告并持续回传曝光点击数据（`monitor-check.ad.xiaomi.com`、`tracking.miui.com`）。
4. **系统级无界面采集暗桩**：
   - `com.mobiletools.systemhelper`：运营商定制版本系统常驻探针，后台持续嗅探基站、SIM卡、IMEI等底层硬件信息。
   - `com.miui.analytics`：全天候收集各应用使用时长、启动频次与操作习惯。
   - `com.miui.msa`：生成不可变更的广告追踪标识符 (OAID) 并持续推送定向广告。
   - `com.xiaomi.joyose`：云控性能调度与温控限制。

**《隐私守卫》** 将原有 Magisk Shell 模块全面重构成现代化的 Android 原生 Root 应用，结合小米 HyperOS 视觉语言，提供从底层驱动、内核 iptables/hosts 拦截到系统服务冻结的立体化隐私防护。

---

## 🛠️ 技术架构

本重构项目严格遵照指定技术栈：
- **编程语言**：`Kotlin 2.0.21`
- **UI 框架**：`Jetpack Compose` (声明式现代 UI)
- **视觉组件库**：`Miuix 0.9.4` (`top.yukonga.miuix.kmp:miuix-ui:0.9.4`，专为 Compose 打造的小米 HyperOS / MIUI 原生质感组件库)
- **本地数据库**：`Room 2.8.5` (持久化存储拦截日志流、Top 排行榜、分段规则与白名单)
- **配置持久化**：`Jetpack Preferences DataStore 1.1.2` (线程安全的高性能配置流)
- **Root 调度核心**：`SuShellExecutor` + `com.github.topjohnwu.libsu:core:6.0.0` (兼容 Magisk、KernelSU、APatch 与原生 su)

---

## 🛡️ 四层立体防御体系

```
       [ App 网络出站请求 (Output) ]
                     │
    ┌────────────────┴────────────────┐
    ▼                                 ▼
【L1: Hosts 域名解析屏蔽】      【L2: IPv4 硬件熔断层 (iptables/ipset)】
 域名直接映射为 0.0.0.0          封死解析所得真实 IP，掐断硬编码直连
 绝大多数请求在此直接终止        自动避让局域网(192.168/10/172/100.64/127)
    │                                 │
    └────────────────┬────────────────┘
                     │
    ┌────────────────┴────────────────┐
    ▼                                 ▼
【L3: IPv6 双栈同封】           【L4: 强制 DNS 兜底 (Anti-DoH/DoT)】
 ip6tables 双栈协同防护          封禁 853 端口 DoT、mDNS 及公共 DNS 直连
 阻断 AAAA 隧道偷跑行为          保留局域网标准 53 端口，杜绝恶意规避
```

### 关键域名白名单兜底（防误伤）
- 自动保护 **LSPosed 在线模块更新**、**GitHub**、**小米账号关键登录** (`account.xiaomi.com`)、**系统 OTA 关键通道** (`update.miui.com`)。
- 在拦截链最前沿使用 `RETURN` 豁免，即使 CDN 共享 IP 也绝不发生连带误伤。

---

## 🎯 核心功能特性

### 1. 仪表盘与全机防御控制台 (Dashboard)
- 实时展示 **L1 Hosts / L2 IPv4 / L3 IPv6 / L4 DNS兜底 / 关键白名单** 生效状态。
- 自动探测 Root 环境（KernelSU、Magisk、APatch、SU）。
- 今日偷跑拦截计数、累计阻断总数、当前生效规则数实时统计。
- 小米控制中心**快捷磁贴 (Quick Settings Tile)** 支持，下拉一键开关。

### 2. 规则与块表中心 (Rules Center)
- **六大精细化分段控制**：
  1. `telemetry`：小米多媒体/视频回传、相册人脸识别回传、手机管家数据上报。
  2. `antifraud`：MIUI 反诈行为特征上报、信通院统一反诈接口。
  3. `riskgate`：微知等第三方嵌入式风控探针。
  4. `miuiad`：智能服务 (Zeus/Mimo) 广告、曝光点击监测、落地页追踪。
  5. `doh`：公共加密 DNS 入口（配合 DNS 兜底）。
  6. `adsdk`：友盟 Umeng、腾讯广点通、Google Ads 等通用跟踪 SDK。
- **自定义规则**：支持随时手动添加、删除拦截域名。
- **白名单管理**：支持自定义增删放行域名。

### 3. 拦截监控中心 (Intercept Monitor)
- **实时事件流**：捕获 iptables 限速 LOG，展示阻断时间、目标 IP/域名、协议端口、拦截层级以及来源应用（UID 自动映射至具体 App 中文名）。
- **Top 偷跑排行**：自动聚合各应用偷跑被阻断的频次，一目了然看清哪款应用在疯狂后台尝试上传数据。

### 4. 小米隐私深度体检 (HyperOS / MIUI Dissector)
- 对小米系统预装的隐私采集组件进行全面扫描与健康评分（0-100分）。
- **一键深度优化**：自动安全冻结无界面常驻探针（`com.miui.analytics`、`com.miui.msa`、`com.mobiletools.systemhelper`）。
- **精细化控制模式**：
  - **彻底冻结** (`pm disable-user --user 0`)
  - **权限剥夺与后台限制** (`cmd appops` 剥夺后台自启、常驻唤醒、读取手机状态等权限)
  - **针对智能服务 (`systemAdSolution`) 的免卡顿治理**：保持组件存活避免自带应用开屏卡顿，使用精准域名阻断实现秒跳开屏！
- **活跃进程监控**：实时探测 `securitycenter`、`guardprovider` 等后台引擎 PID。

---

## 📂 项目结构全览

```
PrivacyGuard/
├── app/
│   ├── build.gradle.kts                # 应用级依赖构建 (Kotlin 2.0.21 + Miuix 0.9.4 + Room 2.8.5)
│   ├── src/main/
│   │   ├── AndroidManifest.xml        # 权限声明、前台服务、开机广播、快捷磁贴
│   │   ├── assets/                    # 内置默认 blocklist.txt, whitelist.txt, hosts.base
│   │   └── java/com/privacyguard/app/
│   │       ├── PrivacyGuardApp.kt     # 全局 Application 单例
│   │       ├── MainActivity.kt        # 主界面与 Miuix 导航底栏
│   │       ├── core/
│   │       │   ├── root/              # Root 权限与 Shell 调度 (SuShellExecutor)
│   │       │   ├── firewall/          # 四层防御 (HostsManager, IptablesManager, DnsGuardManager)
│   │       │   ├── privacy/           # 小米隐私宿主与进程管控 (XiaomiPrivacyManager)
│   │       │   ├── receiver/          # 开机自启监听 (BootReceiver)
│   │       │   └── service/           # 前台状态服务与下拉快捷磁贴 (GuardTileService)
│   │       ├── data/
│   │       │   ├── local/             # Room 2.8.5 数据库与 DAO
│   │       │   ├── preference/        # Preferences DataStore 全局配置流
│   │       │   ├── model/             # 数据实体与枚举
│   │       │   └── repository/        # 仓库层 (Protection, Rule, Privacy, InterceptLog)
│   │       └── ui/
│   │           ├── components/        # HyperCard、ProtectionBanner、StatGauge
│   │           ├── navigation/        # 屏幕路由定义
│   │           └── screens/           # 首页、规则、监控、体检、设置页面
├── module/                            # 内置配套 Magisk / KernelSU / APatch 模块资源
├── settings.gradle.kts
└── build.gradle.kts
```

---

## 🚀 编译与运行

### 编译要求
- Android Studio Ladybug (2024.2.1+) 或更高版本
- JDK 17
- Android SDK 35 (最低支持 Android 8.0 / API 26)

### 编译 APK
```bash
./gradlew assembleDebug
# 输出路径: app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 隐私声明
本应用完全在本地执行，**不包含任何第三方统计、不收集任何用户数据、不连接任何远程服务器**，所有规则和配置均仅保存在本地设备中。
