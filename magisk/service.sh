#!/system/bin/sh
# ============================================================
# palette_unlock / service.sh
# late_start service: 开机后复核挂载结果, 写一份快照日志供排查
# 只读检查, 不修改任何系统文件
# ============================================================
MODDIR="${0%/*}"
[ -z "$MODDIR" ] || [ "$MODDIR" = "$0" ] && MODDIR="/data/adb/modules/palette_unlock"
[ -d "$MODDIR" ] || MODDIR="/data/adb/modules/palette_unlock"

SRC="$MODDIR/system/odm/etc/camera/basictone"
DST="/odm/etc/camera/basictone"
LOG="/data/adb/palette_unlock.log"
LUT_SIZE=49152

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

# 等 boot 完成, 最多 60 秒
i=0
while [ "$i" -lt 60 ]; do
  b=$(getprop sys.boot_completed 2>/dev/null | tr -d ' ')
  [ "$b" = "1" ] && break
  sleep 1
  i=$((i + 1))
done

log "-------------------- service (boot_completed=$i s) --------------------"

if [ ! -d "$DST" ]; then
  log "CHECK $DST 不存在: 该 ROM 没有 basictone/palette 目录, 模块资源不会生效"
  log "      (资源已随模块安装. 需要相机侧开关配合, 见 README)"
  exit 0
fi

n_ok=0; n_bad=0; n_missing=0
for k in HDR SDR; do
  i2=0
  while [ "$i2" -le 8 ]; do
    rel="palette/$k/SC16Lut$i2"
    f="$DST/$rel"
    if [ -f "$f" ]; then
      sz=$(wc -c < "$f" 2>/dev/null | tr -d ' ')
      if [ "$sz" = "$LUT_SIZE" ]; then
        n_ok=$((n_ok + 1))
      else
        log "CHECK $rel 尺寸异常 $sz"
        n_bad=$((n_bad + 1))
      fi
    else
      n_missing=$((n_missing + 1))
    fi
    i2=$((i2 + 1))
  done
done

log "CHECK LUT 就位=$n_ok/18  异常=$n_bad  缺失=$n_missing"
for rel in setting/SimTool_Palette_Photo_Normal.ini setting/SimTool_Palette_Photo_Portrait.ini setting/SimTool_Palette_Photo_Night.ini setting/SimTool_Preview_Palette_Normal.ini; do
  [ -f "$DST/$rel" ] && log "CHECK $rel OK" || log "CHECK $rel 缺失"
done

if [ "$n_ok" = "18" ]; then
  log "RESULT 全部 18 个 SC16Lut 已就位, 资源层正常"
else
  log "RESULT LUT 未全部就位, 若属实请检查 magisk mount 与 /odm 挂载"
fi
log "---------------------------------------------------------------------"
exit 0
