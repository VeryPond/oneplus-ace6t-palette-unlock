#!/system/bin/sh
# ============================================================
# palette_unlock / action.sh
# Magisk 管理器里点"操作"按钮时执行: 打印当前资源状态
# ============================================================
DST="/odm/etc/camera/basictone"
LOG="/data/adb/palette_unlock.log"
LUT_SIZE=49152
FORCE_FLAG="/data/adb/palette_unlock.force"

echo "palette_unlock v1.0  资源状态"
echo "---------------------------------------"
if [ -d "$DST" ]; then
  echo "目标目录: $DST  存在"
  ok=0
  bad=0
  for k in HDR SDR; do
    for i in 0 1 2 3 4 5 6 7 8; do
      f="$DST/palette/$k/SC16Lut$i"
      if [ -f "$f" ]; then
        sz=$(wc -c < "$f" 2>/dev/null | tr -d ' ')
        [ "$sz" = "$LUT_SIZE" ] && ok=$((ok + 1)) || bad=$((bad + 1))
      fi
    done
  done
  echo "SC16Lut 就位: $ok/18   尺寸异常: $bad"
  for rel in setting/SimTool_Palette_Photo_Normal.ini setting/SimTool_Palette_Photo_Portrait.ini setting/SimTool_Palette_Photo_Night.ini setting/SimTool_Preview_Palette_Normal.ini; do
    [ -f "$DST/$rel" ] && echo "  OK   $rel" || echo "  MISS $rel"
  done
else
  echo "目标目录不存在: $DST"
  echo "该 ROM 可能不含 basictone/palette 框架"
fi
echo "---------------------------------------"
[ -f "$FORCE_FLAG" ] && echo "强制覆盖: 已开启" || echo "强制覆盖: 关闭 (保留 ROM 原文件)"
echo "最近日志:"
tail -n 15 "$LOG" 2>/dev/null || echo "  (无日志, 尚未重启过?)"
