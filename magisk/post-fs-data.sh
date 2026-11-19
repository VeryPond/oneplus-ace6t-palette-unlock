#!/system/bin/sh
# ============================================================
# palette_unlock / post-fs-data.sh
# 用 overlay 把调色盘资源挂到只读的 /odm
#
# 【核心原理】/odm 是 erofs 只读，不能 cp/mkdir。
# 唯一可行：overlay mount。
#
# 【关键约束】lowerdir 不能用 /data/adb/...（SELinux 上下文冲突，
#   内核返回 EINVAL）。必须先把资源复制到 tmpfs（/dev 下），
#   tmpfs 与目标上下文兼容，overlay 才能成功。
#
# 【实测有效命令】
#   mount -t overlay overlay \
#     -o lowerdir=/dev/palette_src: /odm/etc/camera/basictone \
#     /odm/etc/camera/basictone
# 两个 lowerdir、无 upperdir，不需要 xattr 支持。
# ============================================================
MODDIR="${0%/*}"
[ -z "$MODDIR" ] || [ "$MODDIR" = "$0" ] && MODDIR="/data/adb/modules/palette_unlock"
[ -d "$MODDIR" ] || MODDIR="/data/adb/modules/palette_unlock"

SRC="$MODDIR/odm/etc/camera/basictone"
DST="/odm/etc/camera/basictone"
TMP="/dev/palette_src"
LOG="/data/adb/palette_unlock.log"

log() { echo "$(date "+%Y-%m-%d %H:%M:%S") $*" >> "$LOG" 2>/dev/null; }

log "==================== palette overlay ===================="
log "MODDIR=$MODDIR"

# 幂等：已挂载就退出
if mountpoint -q "$DST" 2>/dev/null; then
  log "SKIP 已经挂载"
  exit 0
fi

[ -d "$SRC" ] || { log "FATAL 源目录不存在: $SRC"; exit 0; }
[ -d "$DST" ] || { log "FATAL 目标目录不存在: $DST"; exit 0; }

# 清理旧 tmpfs
rm -rf "$TMP" 2>/dev/null
mkdir -p "$TMP" 2>/dev/null

# 复制资源到 tmpfs
if ! cp -a "$SRC/." "$TMP/" 2>>"$LOG"; then
  log "FATAL 复制到 tmpfs 失败"
  exit 0
fi

# 校验复制结果
n=0
for k in HDR SDR; do
  for j in 0 1 2 3 4 5 6 7 8; do
    [ -f "$TMP/palette/$k/SC16Lut$j" ] && n=$((n + 1))
  done
done
log "INFO tmpfs 中 LUT 数量: $n/18"
[ "$n" = "18" ] || { log "FATAL LUT 不完整，中止"; exit 0; }

# 执行 overlay 挂载
mount -t overlay overlay -o "lowerdir=$TMP:$DST" "$DST" 2>>"$LOG"
rc=$?
if [ $rc -ne 0 ]; then
  log "FAIL overlay 挂载失败 exit=$rc"
  exit 0
fi

# 验证
v=0
for k in HDR SDR; do
  for j in 0 1 2 3 4 5 6 7 8; do
    [ -f "$DST/palette/$k/SC16Lut$j" ] && v=$((v + 1))
  done
done
vi=0
for ini in SimTool_Palette_Photo_Normal.ini SimTool_Palette_Photo_Portrait.ini SimTool_Palette_Photo_Night.ini SimTool_Preview_Palette_Normal.ini; do
  [ -f "$DST/setting/$ini" ] && vi=$((vi + 1))
done
log "VERIFY LUT=$v/18  ini=$vi/4"
log "========================================================"
exit 0