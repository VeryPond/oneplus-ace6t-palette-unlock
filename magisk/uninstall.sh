#!/system/bin/sh
# ============================================================
# palette_unlock / uninstall.sh
# 卸载清理：删本模块产生的所有状态，并卸载 LSPosed 模块 APK
#
# 资源侧：本模块通过 Magisk overlay 提供 /odm 文件，卸载重启后自动消失。
# 代码侧：PaletteUnlock.apk 是独立安装的应用，必须显式卸载，
#         否则会留在系统里继续 hook 相机。
# ============================================================

LOG="/data/adb/palette_unlock.log"

echo "[uninstall] 开始清理" >> "$LOG" 2>/dev/null

# 1. 卸载 LSPosed 模块 APK（这是关键，否则 hook 仍在）
if pm path com.palette.unlock >/dev/null 2>&1; then
  if pm uninstall com.palette.unlock >/dev/null 2>&1; then
    echo "[uninstall] LSPosed 模块已卸载" >> "$LOG" 2>/dev/null
  else
    echo "[uninstall] WARN LSPosed 模块卸载失败，请手动卸载 com.palette.unlock" >> "$LOG" 2>/dev/null
  fi
fi

# 2. 删状态文件与备份目录
rm -f /data/adb/palette_unlock.log 2>/dev/null
rm -f /data/adb/palette_unlock.log.tmp 2>/dev/null
rm -f /data/adb/palette_unlock.force 2>/dev/null
rm -rf /data/adb/palette_unlock 2>/dev/null

# 3. 尽力清掉可能落盘的实体资源（/odm 只读时这些命令会失败，属正常）
rm -f /odm/etc/camera/basictone/palette/HDR/SC16Lut[0-8] 2>/dev/null
rm -f /odm/etc/camera/basictone/palette/SDR/SC16Lut[0-8] 2>/dev/null
rm -f /odm/etc/camera/basictone/setting/SimTool_Palette_Photo_*.ini 2>/dev/null
rm -f /odm/etc/camera/basictone/setting/SimTool_Preview_Palette_Normal.ini 2>/dev/null

# 4. 提示用户还需要做的
echo "[uninstall] 清理完成。请重启使 /odm overlay 撤除。" >> "$LOG" 2>/dev/null
echo "[uninstall] 若相机仍有异常，请在 LSPosed 管理器确认 com.palette.unlock 已移除。" >> "$LOG" 2>/dev/null

exit 0
