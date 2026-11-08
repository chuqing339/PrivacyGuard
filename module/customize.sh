#!/system/bin/sh
# FJ HostsBlock v2.6 安装脚本 — PrivacyGuard App 的被动伴随组件
SKIPUNZIP=0

ui_print "*******************************"
ui_print "  FJ HostsBlock v2.6.0"
ui_print "  PrivacyGuard App 被动伴随组件"
ui_print "*******************************"

for f in module.prop system/etc/hosts service.sh post-fs-data.sh uninstall.sh; do
  if [ ! -f "$MODPATH/$f" ]; then
    ui_print "! 缺 $f"
    abort "! 安装中止"
  fi
done

set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/system/etc/hosts" 0 0 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
[ -f "$MODPATH/README.md" ] && set_perm "$MODPATH/README.md" 0 0 0644
# bin/busybox 保留，仅供卸载脚本销毁旧版创建的 ipset 集合
[ -f "$MODPATH/bin/busybox" ] && set_perm "$MODPATH/bin/busybox" 0 0 0755

# 旧版（<= v2.5）可能在 /data/adb/FJ_HostsBlock 留有配置、日志与网页服务残留。
# 安装时不清理（不误删用户数据），卸载本模块时由 uninstall.sh 统一清理。
if [ -d /data/adb/FJ_HostsBlock ]; then
  ui_print "- 检测到旧版数据目录 /data/adb/FJ_HostsBlock"
  ui_print "- 旧版配置/日志/网页服务残留将在卸载模块时清理"
fi

ui_print "- 本模块是 PrivacyGuard App 的伴随组件"
ui_print "- hosts / 防火墙规则 / 应用冻结全部由 App 管理"
ui_print "- 模块仅提供开机 hosts 引导（systemless 挂载）"
ui_print "- 无后台服务，安装完成，重启生效"
