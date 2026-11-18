#!/system/bin/sh
# ============================================================
# palette_unlock / post-fs-data.sh
# 把模块内的调色盘资源幂等铺到 /odm/etc/camera/basictone/
#
# 设计原则:
#   1) 幂等   : 目标已存在且 sha256 相同 -> 跳过, 不写盘
#   2) 保守   : 目标存在但内容不同(ROM 自带版本) -> 默认保留原文件, 只记日志
#               放开覆盖: touch /data/adb/palette_unlock.force 后重启
#   3) 可诊断 : 所有动作写入 /data/adb/palette_unlock.log
#   4) 安全   : 写前校验 SC16Lut8 == 49152 字节; 先写临时文件再 mv, 不留半截文件
#   5) 兜底   : 目标分区不可写时自动回退到 Magisk magic mount, 不报错退出
# ============================================================
MODDIR="${0%/*}"
[ -z "$MODDIR" ] || [ "$MODDIR" = "$0" ] && MODDIR="/data/adb/modules/palette_unlock"
[ -d "$MODDIR" ] || MODDIR="/data/adb/modules/palette_unlock"

SRC="$MODDIR/system/odm/etc/camera/basictone"
DST="/odm/etc/camera/basictone"
LOG="/data/adb/palette_unlock.log"
FORCE_FLAG="/data/adb/palette_unlock.force"
LUT_SIZE=49152

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

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

rot_log() {
  [ -f "$LOG" ] || return 0
  n=$(wc -l < "$LOG" 2>/dev/null | tr -d ' ')
  if [ -n "$n" ] && [ "$n" -gt 400 ] 2>/dev/null; then
    tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG" 2>/dev/null
  fi
  return 0
}

file_sha() {
  command -v sha256sum >/dev/null 2>&1 || return 1
  sha256sum "$1" 2>/dev/null | awk '{print $1}'
}

fsize() { wc -c < "$1" 2>/dev/null | tr -d ' '; }

same_file() {
  a=$(file_sha "$1")
  b=$(file_sha "$2")
  if [ -n "$a" ] && [ -n "$b" ]; then
    [ "$a" = "$b" ] && return 0
    return 1
  fi
  cmp -s "$1" "$2" 2>/dev/null
}

# 返回: 0=已就位/已写入 1=源异常或写失败 3=保留原文件 4=回退 overlay
deploy_one() {
  rel="$1"
  src="$SRC/$rel"
  dst="$DST/$rel"

  if [ ! -f "$src" ]; then
    log "SKIP  $rel  模块内源文件缺失"
    return 1
  fi

  ssz=$(fsize "$src")
  case "$rel" in
    palette/*)
      if [ "$ssz" != "$LUT_SIZE" ]; then
        log "FAIL  $rel  源尺寸 $ssz != $LUT_SIZE, 拒绝部署"
        return 1
      fi
      ;;
  esac

  if [ -f "$dst" ] && same_file "$src" "$dst"; then
    log "SAME  $rel  sha256 一致, 跳过写入"
    return 0
  fi

  if [ -f "$dst" ] && [ ! -f "$FORCE_FLAG" ]; then
    dsz=$(fsize "$dst")
    log "KEEP  $rel  ROM 自带 $dsz 字节 / 模块 $ssz 字节, 内容不同 -> 保留原文件"
    log "      需要强制覆盖: touch $FORCE_FLAG 然后重启"
    return 3
  fi

  mkdir -p "${dst%/*}" 2>/dev/null

  if ! cp -f "$src" "$dst.pl_tmp" 2>/dev/null; then
    log "WARN  $rel  目标分区不可写, 已回退到 Magisk overlay 生效"
    rm -f "$dst.pl_tmp" 2>/dev/null
    return 4
  fi

  chmod 0644 "$dst.pl_tmp" 2>/dev/null
  chown 0:0 "$dst.pl_tmp" 2>/dev/null

  if ! mv -f "$dst.pl_tmp" "$dst" 2>/dev/null; then
    log "WARN  $rel  重命名失败, 已回退到 Magisk overlay 生效"
    rm -f "$dst.pl_tmp" 2>/dev/null
    return 4
  fi

  chmod 0644 "$dst" 2>/dev/null
  chown 0:0 "$dst" 2>/dev/null
  command -v restorecon >/dev/null 2>&1 && restorecon "$dst" >/dev/null 2>&1

  if same_file "$src" "$dst"; then
    log "WRITE $rel  写入成功 ( $ssz 字节 )"
    return 0
  fi
  log "FAIL  $rel  写入后 sha256 校验不一致"
  return 1
}

# ---------------- main ----------------
rot_log
log "==================== boot post-fs-data ===================="
log "MODDIR=$MODDIR  SRC=$SRC  DST=$DST"

if [ ! -d "$SRC" ]; then
  log "FATAL 模块资源目录不存在, 本次不做任何改动"
  exit 0
fi

# 关键 LUT 尺寸前置校验 (相机 palette 分支依赖 SC16Lut8)
for k in HDR SDR; do
  f="$SRC/palette/$k/SC16Lut8"
  if [ -f "$f" ]; then
    sz=$(fsize "$f")
    if [ "$sz" != "$LUT_SIZE" ]; then
      log "FATAL palette/$k/SC16Lut8 = $sz 字节, 期望 $LUT_SIZE, 中止部署"
      exit 0
    fi
  else
    log "WARN  palette/$k/SC16Lut8 缺失"
  fi
done
log "PRECHECK SC16Lut8 HDR/SDR = $LUT_SIZE 字节 OK"

if [ -d "$DST" ]; then
  log "INFO 目标目录已存在: $DST"
else
  log "INFO 目标目录不存在: $DST (首次部署会尝试创建)"
fi

[ -f "$FORCE_FLAG" ] && log "INFO 检测到强制覆盖标记 $FORCE_FLAG"

c_ok=0; c_keep=0; c_fail=0; c_ovl=0
for rel in $ITEM_LIST; do
  deploy_one "$rel"
  case $? in
    0) c_ok=$((c_ok + 1)) ;;
    3) c_keep=$((c_keep + 1)) ;;
    4) c_ovl=$((c_ovl + 1)) ;;
    *) c_fail=$((c_fail + 1)) ;;
  esac
done

log "SUMMARY 就位=$c_ok  保留原文件=$c_keep  回退overlay=$c_ovl  失败=$c_fail  合计=$((c_ok + c_keep + c_ovl + c_fail))"
if command -v restorecon >/dev/null 2>&1; then
  restorecon -R "$DST" >/dev/null 2>&1
fi
log "=========================================================="
exit 0
