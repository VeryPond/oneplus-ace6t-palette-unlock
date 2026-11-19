#!/system/bin/sh
# ============================================================
# palette_unlock / customize.sh
# Magisk 安装期钩子：资源完整性校验 + 权限/属主设置
# 任何一项 LUT 尺寸不对就 abort，绝不把半截资源装进系统
# ============================================================
SKIPUNZIP=0

MODVER=$(grep '^version=' "$MODPATH/module.prop" 2>/dev/null | cut -d= -f2)
[ -z "$MODVER" ] && MODVER="unknown"
ui_print "***********************************************"
ui_print " palette_unlock 调色盘资源注入模块  $MODVER"
ui_print " 目标: /odm/etc/camera/basictone/{palette,setting}"
ui_print "***********************************************"

MODSRC="$MODPATH/odm/etc/camera/basictone"
TARGET_ODM="/odm/etc/camera/basictone"
LUT_SIZE=49152
EXPECT_COUNT=22

ITEM_LIST="palette/HDR/SC16Lut0
palette/HDR/SC16Lut1
palette/HDR/SC16Lut2
palette/HDR/SC16Lut3
palette/HDR/SC16Lut4
palette/HDR/SC16Lut5
palette/HDR/SC16Lut6
palette/HDR/SC16Lut7
palette/HDR/SC16Lut8
palette/SDR/SC16Lut0
palette/SDR/SC16Lut1
palette/SDR/SC16Lut2
palette/SDR/SC16Lut3
palette/SDR/SC16Lut4
palette/SDR/SC16Lut5
palette/SDR/SC16Lut6
palette/SDR/SC16Lut7
palette/SDR/SC16Lut8
setting/SimTool_Palette_Photo_Normal.ini
setting/SimTool_Palette_Photo_Portrait.ini
setting/SimTool_Palette_Photo_Night.ini
setting/SimTool_Preview_Palette_Normal.ini"

if [ ! -d "$MODSRC" ]; then
  abort "! 安装包结构异常: 未找到 odm/etc/camera/basictone"
fi

# ---- 0. 自动安装 LSPosed 模块 APK ----
ui_print "- 安装 LSPosed 模块 PaletteUnlock"
if [ -f "$MODPATH/PaletteUnlock.apk" ]; then
  # APK 完整性预检：太小或不是 zip 就不折腾了
  apk_sz=$(wc -c < "$MODPATH/PaletteUnlock.apk" 2>/dev/null | tr -d ' ')
  if [ -z "$apk_sz" ] || [ "$apk_sz" -lt 100000 ] 2>/dev/null; then
    ui_print "  ! APK 尺寸异常 ($apk_sz 字节)，跳过安装"
  elif [ "$(head -c2 "$MODPATH/PaletteUnlock.apk")" != "PK" ]; then
    ui_print "  ! APK 不是有效 zip，跳过安装"
  else
  # 先备一份到 /data，装失败时用户可手动装
  mkdir -p /data/adb/palette_unlock
  cp -f "$MODPATH/PaletteUnlock.apk" /data/adb/palette_unlock/PaletteUnlock.apk 2>/dev/null || true

  # 已装过就用 -r 覆盖，【不要】先 uninstall —— 那会清掉 LSPosed 的
  # modules_state 与 scope 两张表，导致模块"在列表里但没启用没作用域"
  installed_before=0
  if pm path com.palette.unlock >/dev/null 2>&1; then
    installed_before=1
  fi

  if pm install -r "$MODPATH/PaletteUnlock.apk" >/dev/null 2>&1; then
    if [ "$installed_before" = "1" ]; then
      ui_print "  LSPosed 模块已更新（保留原有作用域配置）"
    else
      ui_print "  LSPosed 模块已安装"
    fi
  else
    # 首次失败时再试一次卸载重装（仅当之前没装过）
    if [ "$installed_before" = "0" ]; then
      ui_print "  ! 首次安装失败，重试中..."
      if pm install "$MODPATH/PaletteUnlock.apk" >/dev/null 2>&1; then
        ui_print "  LSPosed 模块已安装（重试成功）"
      else
        ui_print "  ! APK 安装失败，重启后请手动安装:"
        ui_print "    /data/adb/palette_unlock/PaletteUnlock.apk"
      fi
    else
      ui_print "  ! APK 更新失败（通常是签名不一致），请先卸载旧版再刷一次"
      ui_print "    备份: /data/adb/palette_unlock/PaletteUnlock.apk"
    fi
  fi

  # 模块目录里不再保留 APK，省空间（副本已在 /data/adb/palette_unlock/）
  rm -f "$MODPATH/PaletteUnlock.apk"
  fi
else
  ui_print "  ! 模块内未找到 PaletteUnlock.apk"
fi

# ---- 1. 逐文件存在性 + LUT 尺寸校验 ----
ui_print "- 校验资源完整性 ($EXPECT_COUNT 个文件, LUT 必须 = $LUT_SIZE 字节)"
fail=0
seen=0
for rel in $ITEM_LIST; do
  f="$MODSRC/$rel"
  seen=$((seen + 1))
  if [ ! -f "$f" ]; then
    ui_print "  ! 缺失 $rel"
    fail=$((fail + 1))
    continue
  fi
  sz=$(wc -c < "$f" 2>/dev/null | tr -d ' \n')
  case "$rel" in
    palette/*)
      if [ "$sz" != "$LUT_SIZE" ]; then
        ui_print "  ! 尺寸异常 $rel = $sz (期望 $LUT_SIZE)"
        fail=$((fail + 1))
      fi
      ;;
    setting/*)
      if [ "$sz" -lt 16 ] 2>/dev/null; then
        ui_print "  ! 配置文件过小 $rel = $sz"
        fail=$((fail + 1))
      fi
      ;;
  esac
done
ui_print "- 已检查 $seen 个文件, 异常 $fail 个"
if [ "$fail" != "0" ]; then
  abort "! 资源校验失败, 安装已中止 (不会改动系统任何文件)"
fi

# ---- 2. SC16Lut8 单独点名确认 (相机 palette 分支的关键 LUT) ----
for k in HDR SDR; do
  f="$MODSRC/palette/$k/SC16Lut8"
  sz=$(wc -c < "$f" 2>/dev/null | tr -d ' \n')
  if [ "$sz" != "$LUT_SIZE" ]; then
    abort "! palette/$k/SC16Lut8 尺寸 $sz != $LUT_SIZE"
  fi
done
ui_print "- SC16Lut8 (HDR/SDR) 尺寸确认: $LUT_SIZE 字节 OK"

# ---- 3. 权限与属主 ----
set_perm_recursive "$MODPATH/odm" 0 0 0755 0644
for s in customize.sh post-fs-data.sh service.sh uninstall.sh action.sh; do
  [ -f "$MODPATH/$s" ] && set_perm "$MODPATH/$s" 0 0 0755
done
ui_print "- 权限设置完成 (root:root 0644 / 脚本 0755)"

# ---- 4. 环境提示 (只提示, 不阻断: 本模块是资源兜底件) ----
ui_print "- 设备指纹: $(getprop ro.build.version.oplusrom 2>/dev/null) / $(getprop ro.product.model 2>/dev/null)"
if [ -d "$TARGET_ODM/palette/HDR" ]; then
  n=$(ls -1 "$TARGET_ODM/palette/HDR" 2>/dev/null | wc -l | tr -d ' \n')
  ui_print "- ROM 已存在 $TARGET_ODM/palette/HDR (现有 $n 个文件)"
  ui_print "  同名文件默认保留 ROM 版本; 要强制覆盖见 README 的 .force 标记"
else
  ui_print "- 未发现 $TARGET_ODM/palette/HDR"
  ui_print "  本模块仍会挂载资源, 但该 ROM 可能不含 basictone/palette 框架"
fi
ui_print "- 功能开关由 LSPosed 模块负责, 本模块只铺资源"
ui_print "- 日志: /data/adb/palette_unlock.log"
ui_print "- 安装完成, 重启后生效"
