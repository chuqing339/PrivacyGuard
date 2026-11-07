#!/system/bin/sh
# 最后一级兵：common.sh 里连 busybox 都找不到时才会调到这里
#
# 警告：这里拿不到真实 IP。因为 hosts 已被本模块写成 0.0.0.0，
# 任何走系统解析器的工具（ping / getent / nc）都会拿到 0.0.0.0。
# 输出标了 fallback=1 让上看知道这是不可靠数据，不要写进拦截表。

D=$1
S=${2:-223.5.5.5}

for bb in "$MODDIR/bin/busybox" /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /system/bin/busybox; do
  if [ -x "$bb" ] && "$bb" true 2>/dev/null; then
    "$bb" nslookup "$D" "$S" 2>/dev/null
    exit 0
  fi
done

if command -v busybox >/dev/null 2>&1; then
  busybox nslookup "$D" "$S" 2>/dev/null
  exit 0
fi

# 没辙了，什么都不输出（不要拿 0.0.0.0 去污染拦截表）
exit 0
