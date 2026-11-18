#!/system/bin/sh
# ============================================================
# palette_unlock / action.sh
# Magisk 管理器里点「操作」按钮时执行：全链路自检
#
# 覆盖三层：
#   A. 资源层 —— LUT 与 ini 是否就位、尺寸与 SHA 是否正确
#   B. 模块层 —— APK 是否安装、是否在 LSPosed 作用域
#   C. 运行层 —— hook 日志里每层 installed 计数
# ============================================================

DST="/odm/etc/camera/basictone"
RES_LOG="/data/adb/palette_unlock.log"
HOOK_LOG="/sdcard/Android/data/com.oplus.camera/files/palette_unlock.log"
FORCE_FLAG="/data/adb/palette_unlock.force"
LUT_SIZE=49152
PKG="com.palette.unlock"
SCOPE="com.oplus.camera"

# 官方 LUT 的 SHA256 前12位（与一加15 PLK110 OTA 一致）
LUT_SHA="694f087ef1d1 ad281da87cc9 8da8aca255cb b72680c2af5f 45c26b247cc6 fd4d798a6840 f3a7739006e1 7265cf25cfd3 38496c97d641"

echo "=================================="
echo " palette_unlock 全链路自检 v2.2"
echo "=================================="

echo ""
echo "【A. 资源层】"
if [ -d "$DST/palette" ]; then
  ok=0; bad=0; shaok=0; shabad=0
  idx=0
  for k in HDR SDR; do
    for i in 0 1 2 3 4 5 6 7 8; do
      f="$DST/palette/$k/SC16Lut$i"
      if [ -f "$f" ]; then
        sz=$(wc -c < "$f" 2>/dev/null | tr -d ' ')
        if [ "$sz" = "$LUT_SIZE" ]; then
          ok=$((ok + 1))
          if command -v sha256sum >/dev/null 2>&1; then
            want=$(echo "$LUT_SHA" | cut -d' ' -f$((i + 1)))
            got=$(sha256sum "$f" 2>/dev/null | cut -c1-12)
            if [ "$got" = "$want" ]; then shaok=$((shaok + 1)); else shabad=$((shabad + 1)); fi
          fi
        else
          bad=$((bad + 1))
        fi
      else
        bad=$((bad + 1))
      fi
    done
  done
  echo "  LUT 就位: $ok/18   尺寸异常: $bad"
  if command -v sha256sum >/dev/null 2>&1; then
    echo "  SHA 匹配官方: $shaok/18   不匹配: $shabad"
    [ "$shabad" != "0" ] && echo "  !! LUT 内容与官方不一致，调色效果会异常"
  fi
  n=0
  for rel in setting/SimTool_Palette_Photo_Normal.ini setting/SimTool_Palette_Photo_Portrait.ini setting/SimTool_Palette_Photo_Night.ini setting/SimTool_Preview_Palette_Normal.ini; do
    [ -f "$DST/$rel" ] && n=$((n + 1))
  done
  echo "  palette ini: $n/4"
else
  echo "  !! $DST/palette 不存在"
fi
[ -f "$FORCE_FLAG" ] && echo "  强制覆盖标记: 已开启" || echo "  强制覆盖标记: 关闭"

echo ""
echo "【B. 模块层】"
if pm path "$PKG" >/dev/null 2>&1; then
  apk=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
  echo "  LSPosed 模块已安装: $apk"
else
  echo "  !! LSPosed 模块未安装 ($PKG)"
fi
if [ -f /data/adb/lspd/config/modules_config.db ] && command -v sqlite3 >/dev/null 2>&1; then
  st=$(sqlite3 /data/adb/lspd/config/modules_config.db "select enabled from modules_state where module_pkg_name='$PKG';" 2>/dev/null)
  sc=$(sqlite3 /data/adb/lspd/config/modules_config.db "select count(*) from scope where module_pkg_name='$PKG' and app_pkg_name='$SCOPE';" 2>/dev/null)
  echo "  启用状态: ${st:-未知}   作用域含 $SCOPE: ${sc:-0}"
  [ "$st" = "1" ] || echo "  !! 模块未启用，去 LSPosed 管理器勾选"
  [ "$sc" = "1" ] || echo "  !! 作用域未勾选相机"
else
  echo "  (无 sqlite3 或 LSPosed 数据库，跳过)"
fi

echo ""
echo "【C. 运行层 hook 日志】"
if [ -f "$HOOK_LOG" ]; then
  echo "  -- 各层 installed 计数 --"
  grep -E 'Hook (done|installed)|installed=' "$HOOK_LOG" 2>/dev/null | tail -12 | sed 's/^/    /'
  echo "  -- 强制生效记录（前5条）--"
  grep -E '强制 true|-> true (forced)|OVERWRITE' "$HOOK_LOG" 2>/dev/null | head -5 | sed 's/^/    /'
  echo "  -- 异常 --"
  bad=$(grep -cE '原始=false|skipped|not found' "$HOOK_LOG" 2>/dev/null)
  echo "    可疑行数: ${bad:-0}"
else
  echo "  !! 无 hook 日志"
  echo "     说明相机还没在模块生效后启动过，或 LSPosed 未注入"
fi

echo ""
echo "【D. 资源部署日志】"
tail -n 8 "$RES_LOG" 2>/dev/null | sed 's/^/  /' || echo "  (无)"

echo ""
echo "=================================="
echo " 自检完成。若某项为 !!，请按提示处理。"
echo "=================================="
