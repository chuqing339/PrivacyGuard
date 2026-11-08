# FJ HostsBlock（v2.6.0）

Magisk / KernelSU / APatch 模块，**PrivacyGuard App（com.privacyguard.app）的被动伴随组件**。

模块本身不执行任何规则：没有后台服务、没有本地网页服务、不写 iptables/ipset、不改应用冻结状态。唯一职责是提供一个 systemless 的 hosts 引导文件，覆盖「App 尚未运行」的开机窗口；规则全部由 App 通过 root shell 下发与管理。

## 模块做什么

- 提供 `system/etc/hosts`：仓库里只保留引导占位（`127.0.0.1 localhost` / `::1 localhost`），这是 App 首次运行之前开机时系统的 hosts 内容。
- 开机时 Magisk / KernelSU 的 magic mount 自动把它 systemless 挂载到 `/system/etc/hosts`，无需脚本参与挂载。
- `post-fs-data.sh` 只确保引导文件存在且权限正确，不重建内容。
- `service.sh` 仅输出一行「被动伴随模式」日志，并清理 v2.5 及更早版本遗留的本地网页服务进程与残锁（升级场景保护）。

## 为什么移除了本地网页服务

v2.5 及之前模块内置的本地 webserver 走的是无鉴权 root 网页接口，且响应头带 `Access-Control-Allow-Origin: *`，同机任意应用都能借浏览器跨域直接调用 root 能力，属于无法在脚本层面修好的 P0 安全问题。v2.6 起整套本地网页服务从模块中删除，管理入口只保留 App 一处。

## App 如何更新 hosts

1. App 运行时按用户的屏蔽配置，直接写入 `/data/adb/modules/FJ_HostsBlock/system/etc/hosts`（模块 id 固定为 `FJ_HostsBlock`，App 依赖该路径，不可改名）。
2. 新写入的 hosts 在下一次开机时由 magic mount 生效。
3. 域名解析、规则合并、防火墙下发、应用冻结全部在 App 内完成，模块不参与。

## 文件清单

| 路径 | 说明 |
|---|---|
| `module.prop` | 模块元信息（id 固定 `FJ_HostsBlock`，App 硬编码依赖） |
| `system/etc/hosts` | hosts 引导占位，内容由 App 更新 |
| `post-fs-data.sh` | 确保 hosts 引导文件存在且权限正确（不重建内容） |
| `service.sh` | 被动模式日志 + 清理旧版残留进程/残锁 |
| `uninstall.sh` | 卸载收尾（见下） |
| `bin/busybox` | 仅供卸载脚本销毁旧版创建的 ipset 集合 |

## 卸载行为

卸载模块时 `uninstall.sh` 会：

1. 收掉旧版本遗留的本地网页服务 / 刷新守护进程与残锁；
2. 清理旧版写入的 iptables / ip6tables 链：`fj_blk_out`、`fj_blk6_out`、`fj_wl_out`、`fj_blk_dns`（逐条 `-D OUTPUT` + `-F` + `-X`）；
3. 销毁 ipset 集合 `fj_blk` / `fj_blk6`（优先用模块自带 busybox，失败回退系统 ipset）；
4. 按旧版记录恢复被禁用 / 限制的包：`.disabled_pkgs` 里的包执行 `pm enable`，`.restricted_*` 对应的包把相关 appops 全部恢复 `allow`；
5. 删除数据目录 `/data/adb/FJ_HostsBlock/`（含旧版配置、日志、网页服务残留文件）。

PrivacyGuard App 自己的记录（`/data/adb/privacy_guard/` 下）由 App 的卸载流程处理，模块卸载脚本不碰。卸载模块不影响已安装的 App。

## 安装

Magisk / KernelSU → 模块 → 从本地安装 → 选择模块 zip → 重启。安装脚本只放文件、设权限，不创建任何运行时数据；旧版数据目录留在原处，直到卸载时统一清理。
