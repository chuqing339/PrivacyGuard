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
5. **电信终端注册与测试设备身份识别**：`com.xiaomi.registration` 向 `zzhc.vnet.cn` 上报 CTEI 终端注册信息，并经 `typdm.tydevice.com` 以**明文 HTTP 查询参数**提交 CTEI/MAC/IP/上联MAC/连接方式/固件版本；`com.miui.misightservice` 对测试机型经 `data.pt.xiaomi.com`、`nptapi.mioffice.cn` 做 OAID/序列号级身份识别。

**《隐私守卫》** 将原有 Magisk Shell 模块全面重构成现代化的 Android 原生 Root 应用，结合小米 HyperOS 视觉语言，提供从底层驱动、内核 iptables/hosts 拦截到系统服务冻结的立体化隐私防护。

---

## 🛠️ 技术架构

本重构项目严格遵照指定技术栈：
- **编程语言**：`Kotlin 2.4.20`
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
- 对小米系统预装的隐私采集组件进行全面扫描与健康评分（0-100分），覆盖 14 个重点组件：
  - 运营商探针 `com.mobiletools.systemhelper`、电信终端注册 `com.xiaomi.registration`
  - 统计与广告 `com.miui.analytics`、`com.miui.msa(.global)`、`com.miui.systemAdSolution`
  - 安全组件 `com.miui.guardprovider`（应用清单枚举/APK 样本采集/查杀报告上传）、`com.miui.securitycenter`（反诈行为记录入库）
  - 诊断与 AI `com.miui.misightservice`、`com.xiaomi.aicr`、`com.miui.contentcatcher`
  - 云控与日志 `com.xiaomi.joyose`、`com.miui.bugreport`、`com.miui.daemon`
- **一键深度优化**：自动安全冻结无界面常驻探针（`com.miui.analytics`、`com.miui.msa`、`com.miui.contentcatcher`、`com.mobiletools.systemhelper`）。
- **精细化控制模式**：
  - **彻底冻结** (`pm disable-user --user 0`)
  - **权限剥夺与后台限制** (`cmd appops` 剥夺后台自启、常驻唤醒、读取手机状态等权限)
  - **针对智能服务 (`systemAdSolution`) 的免卡顿治理**：保持组件存活避免自带应用开屏卡顿，使用精准域名阻断实现秒跳开屏！
- **活跃进程监控**：实时探测 `securitycenter`、`guardprovider`、`misightservice`、`aicr`、`registration` 等后台引擎 PID。

### 5. 本地采集残留深度清理 (Root 独有能力)
- 方法级 hook 类工具只能拦"上报"，**清不掉已落地的历史数据**：反诈行为记录（通话/屏幕共享时长等）会永久留存在手机管家本地库 `fraud_detection_data.db`。
- 隐私守卫用 Root 直达本地：将 `fraud_detection_data.db` 及 `-wal/-shm` 伴生文件**整库备份**到 `/data/adb/privacy_guard/fraud_backup/<时间戳>/` 后删除原文件，可随时手动还原。
- 逐文件"先备份后删除"，任一文件备份失败立即中止，不产生半清状态；只匹配实证库前缀，不误伤骚扰拦截等其他数据库。

---

## 📂 项目结构全览

```
PrivacyGuard/
├── app/
│   ├── build.gradle.kts                # 应用级依赖构建 (Kotlin 2.4.20 + Miuix 0.9.4 + Room 2.8.5)
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
├── module/                            # 被动伴随模块：提供开机 hosts 引导，规则由 App 管理
├── settings.gradle.kts
└── build.gradle.kts
```

---

## 🚀 编译与运行

### 编译要求
- Android Studio（需支持 Android Gradle Plugin 9.1.0）或更高版本
- JDK 17
- Android SDK 37 (compileSdk；最低运行支持 Android 8.0 / API 26)

### 编译 APK
```bash
./gradlew assembleDebug
# 输出路径: app/build/outputs/apk/debug/app-debug.apk
```

### Release 签名与 CI

- 本地执行 `./gradlew assembleRelease` 时，若未设置 `CI_KEYSTORE_*` 环境变量，release 包自动回退 **debug 签名**（仅供本地开发测试，不可用于覆盖安装正式签名版本）。
- CI（GitHub Actions）启用正式签名的方法：
  1. 生成正式 keystore，并**务必妥善离线备份**（丢失后将无法以相同签名升级应用）：
     ```bash
     keytool -genkeypair -v -keystore release.keystore -alias privacyguard -keyalg RSA -keysize 2048 -validity 10000
     ```
  2. 将 keystore 转为 base64 文本：
     ```bash
     # Linux
     base64 -w0 release.keystore
     # macOS
     base64 -i release.keystore -o - | tr -d '\n'
     ```
  3. 在仓库 **Settings → Secrets and variables → Actions → New repository secret** 中添加三个 secret：
     - `CI_KEYSTORE_BASE64`：上一步输出的 base64 全文
     - `CI_KEYSTORE_PASSWORD`：keystore 密码
     - `CI_KEYSTORE_ALIAS`：密钥别名（如 `privacyguard`）
  4. 配置完成后，CI 的 release 构建将自动使用该 keystore 签名；未配置时 CI 回退 debug 签名并输出警告。

---

## 🔒 隐私声明
本应用完全在本地执行，**不包含任何第三方统计、不收集任何用户数据、不连接任何远程服务器**，所有规则和配置均仅保存在本地设备中。
