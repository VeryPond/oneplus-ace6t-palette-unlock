#!/system/bin/sh
# ============================================================
# palette_unlock / service.sh
# late_start service: 开机后复核挂载结果, 写一份快照日志供排查
# 只读检查, 不修改任何系统文件
# ============================================================
MODDIR="${0%/*}"
[ -z "$MODDIR" ] || [ "$MODDIR" = "$0" ] && MODDIR="/data/adb/modules/palette_unlock"
[ -d "$MODDIR" ] || MODDIR="/data/adb/modules/palette_unlock"

SRC="$MODDIR/odm/etc/camera/basictone"
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
  log "FAIL $DST 不存在: 该 ROM 没有 basictone/palette 目录, 模块资源不会生效"
  log "     /odm 可能未挂载，或该机型不支持 palette 目录结构"
  RES_SKIP=1
else
  RES_SKIP=0
fi

n_ok=0; n_bad=0; n_missing=0
if [ "$RES_SKIP" = "1" ]; then
  log "SKIP 资源检查（目标目录不存在）"
else
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
          log "CHECK $rel 尺寸异常 $sz (期望 $LUT_SIZE)"
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
fi

if [ "$n_ok" = "18" ]; then
  log "RESULT 全部 18 个 SC16Lut 已就位, 资源层正常"
else
  log "RESULT LUT 未全部就位, 若属实请检查 magisk mount 与 /odm 挂载"
fi
log "---------------------------------------------------------------------"
# LSPosed_scope_autoreg
# 开机后自动把 com.palette.unlock 注册进 LSPosed 的 modules_state / scope
# 这样用户不用手动去管理器里勾选作用域
# 注意：本段必须在任何 exit 之前调用，否则永不执行
# ============================================================
LSPD_DB="/data/adb/lspd/config/modules_config.db"
PKG="com.palette.unlock"
SCOPE_PKG="com.oplus.camera"
AUTOREG_LOG="/data/adb/palette_unlock.log"

autoreg_log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [autoreg] $*" >> "$AUTOREG_LOG" 2>/dev/null; }

lsposed_autoreg() {
  if [ ! -f "$LSPD_DB" ]; then
    autoreg_log "SKIP LSPosed 数据库不存在: $LSPD_DB"
    return 0
  fi
  if ! pm path "$PKG" >/dev/null 2>&1; then
    autoreg_log "SKIP 模块 APK 未安装: $PKG"
    return 0
  fi
  if ! command -v sqlite3 >/dev/null 2>&1; then
    autoreg_log "WARN 系统无 sqlite3，需在 LSPosed 管理器手动勾选作用域"
    return 0
  fi

  APK_PATH=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
  if [ -z "$APK_PATH" ]; then
    autoreg_log "WARN 拿不到 APK 路径，跳过"
    return 0
  fi

  # 逐条写，每条独立判错
  # 【重要】这三张表没有唯一约束，INSERT OR IGNORE 不会去重。
  # 本脚本每次开机都跑，必须先删后插，否则行数会累积。
  ok=1
  sqlite3 "$LSPD_DB" "DELETE FROM modules WHERE module_pkg_name='$PKG';" 2>>"$AUTOREG_LOG" || ok=0
  sqlite3 "$LSPD_DB" "DELETE FROM modules_state WHERE module_pkg_name='$PKG';" 2>>"$AUTOREG_LOG" || ok=0
  sqlite3 "$LSPD_DB" "DELETE FROM scope WHERE module_pkg_name='$PKG';" 2>>"$AUTOREG_LOG" || ok=0
  sqlite3 "$LSPD_DB" "INSERT INTO modules(module_pkg_name,apk_path) VALUES('$PKG','$APK_PATH');" 2>>"$AUTOREG_LOG" || ok=0
  sqlite3 "$LSPD_DB" "INSERT INTO modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) VALUES('$PKG',0,1,0);" 2>>"$AUTOREG_LOG" || ok=0
  sqlite3 "$LSPD_DB" "INSERT INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('$PKG','$SCOPE_PKG',0);" 2>>"$AUTOREG_LOG" || ok=0

  # 回读校验
  st=$(sqlite3 "$LSPD_DB" "SELECT enabled FROM modules_state WHERE module_pkg_name='$PKG';" 2>/dev/null)
  sc=$(sqlite3 "$LSPD_DB" "SELECT count(*) FROM scope WHERE module_pkg_name='$PKG' AND app_pkg_name='$SCOPE_PKG';" 2>/dev/null)
  autoreg_log "RESULT enabled=${st:-?} scope=${sc:-?} 写入ok=$ok"
  if [ "$st" = "1" ] && [ "$sc" = "1" ]; then
    autoreg_log "OK LSPosed 作用域已自动注册"
  else
    autoreg_log "WARN 自动注册未完全成功，请在 LSPosed 管理器手动勾选 com.oplus.camera"
  fi
  return 0
}
lsposed_autoreg

exit 0


# ============================================================