#!/system/bin/sh
# ============================================================
# palette_unlock / uninstall.sh
# 卸载清理: 只删本模块产生的状态文件
#
# 说明: 本模块资源通过 Magisk magic mount 提供, 卸载并重启后自动消失。
#       若 post-fs-data.sh 曾在可写分区上落过实体文件(极少数情况),
#       因 /odm 为只读分区, 重启后不会残留;
#       若需彻底回滚, 卸载后重启即可。
# ============================================================
rm -f /data/adb/palette_unlock.log 2>/dev/null
rm -f /data/adb/palette_unlock.log.tmp 2>/dev/null
rm -f /data/adb/palette_unlock.force 2>/dev/null
exit 0
