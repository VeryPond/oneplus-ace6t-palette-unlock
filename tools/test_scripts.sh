#!/bin/bash
# ============================================================
# tools/test_scripts.sh
# 本地桩环境测试 Magisk 脚本（不需要真机）
#
# 用法: ./tools/test_scripts.sh /path/to/magisk_mod
#
# 为什么需要：静态 code review 发现不了"无唯一约束导致 INSERT OR IGNORE
# 失效"、"exit 之后的死代码"、"sed 误替换 system/odm 子串"这类问题。
# ============================================================
set -u
MOD_SRC="${1:-}"
if [ -z "$MOD_SRC" ] || [ ! -d "$MOD_SRC" ]; then
  echo "用法: $0 <magisk_mod_dir>"; exit 1
fi
MOD_SRC=$(cd "$MOD_SRC" && pwd)
T=$(mktemp -d)
T=$(echo "$T" | tr "\\\\" "/")
trap 'rm -rf "$T"' EXIT

PYEXE=""
# 排除 Windows Store 的 App Execution Alias 空壳（会打印 "Python was not found"）
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1; then
    if "$c" -c "import sqlite3" >/dev/null 2>&1; then PYEXE=$c; break; fi
  fi
done
if [ -z "$PYEXE" ]; then
  for p in /c/Users/*/AppData/Local/Programs/Python/Python*/python.exe; do
    if [ -x "$p" ] && "$p" -c "import sqlite3" >/dev/null 2>&1; then PYEXE=$p; break; fi
  done
fi

echo "测试根目录: $T"
echo "python: ${PYEXE:-未找到}"
mkdir -p "$T/bin" "$T/data" "$T/odm/etc/camera/basictone" "$T/mod"
cp -r "$MOD_SRC"/. "$T/mod"/ 2>/dev/null || true

# ---------- 桩命令 ----------
printf '#!/bin/sh\necho 1\n' > "$T/bin/getprop"
printf '#!/bin/sh\nexit 0\n' > "$T/bin/restorecon"
printf '#!/bin/sh\nexit 0\n' > "$T/bin/mountpoint"
cat > "$T/bin/pm" <<EOF
#!/bin/sh
MARK="$T/data/.pkg_installed"
case "\$1" in
  path) if [ -f "\$MARK" ]; then echo "package:/data/app/x/base.apk"; exit 0; else exit 1; fi ;;
  install) touch "\$MARK"; exit 0 ;;
  uninstall) rm -f "\$MARK"; exit 0 ;;
esac
exit 0
EOF
cat > "$T/bin/sqlite3" <<EOF
#!/bin/sh
echo "SQL: \$*" >> "$T/data/sql_trace.log"
exit 0
EOF
cat > "$T/bin/magisk_stubs.sh" <<'EOF'
ui_print() { echo "UI: $*"; }
abort() { echo "ABORT: $*"; exit 1; }
set_perm() { :; }
set_perm_recursive() { :; }
EOF
chmod +x "$T"/bin/*

# ---------- 路径改写 ----------
# 关键：先保护 "system/odm" 子串，否则 s|/odm/...|$T/odm/...| 会把
# SRC="$MODDIR/system/odm/..." 里的 /odm 也换掉，得到坏路径。
rewrite() {
  sed -e "s|^#!/system/bin/sh|#!/bin/sh\n. $T/bin/magisk_stubs.sh|" \
      -e "s|system/odm/etc/camera/basictone|__SYSODM__|g" \
      -e "s|\$MODPATH|$T/mod|g" \
      -e "s|/odm/etc/camera/basictone|$T/odm/etc/camera/basictone|g" \
      -e "s|__SYSODM__|system/odm/etc/camera/basictone|g" \
      -e "s|/data/adb/palette_unlock.log|$T/data/palette_unlock.log|g" \
      -e "s|/data/adb/palette_unlock.force|$T/data/palette_unlock.force|g" \
      -e "s|/data/adb/palette_unlock|$T/data/palette_unlock|g" \
      -e "s|/data/adb/lspd/config/modules_config.db|$T/data/modules_config.db|g" \
      "$1"
}

export PATH="$T/bin:$PATH"
PASS=0; FAIL=0
ck() { if [ "$2" = "$3" ]; then echo "  PASS $1"; PASS=$((PASS+1)); else echo "  FAIL $1 (期望=$3 实得=$2)"; FAIL=$((FAIL+1)); fi; }

# ---------- 假 LSPosed 数据库 ----------
DBP="$T/data/modules_config.db"
if [ -n "$PYEXE" ]; then
  "$PYEXE" -c "
import sqlite3,sys
con=sqlite3.connect(sys.argv[1])
con.execute('CREATE TABLE modules(module_pkg_name TEXT, apk_path TEXT)')
con.execute('CREATE TABLE modules_state(module_pkg_name TEXT, user_id INTEGER, enabled INTEGER, scope_request_blocked INTEGER)')
con.execute('CREATE TABLE scope(module_pkg_name TEXT, app_pkg_name TEXT, user_id INTEGER)')
con.commit();con.close()
" "$DBP"
fi
[ -f "$DBP" ] && echo "假数据库: $(wc -c < "$DBP") 字节" || echo "假数据库: 未建立（autoreg 测试会 SKIP）"

# ---------- 测试 post-fs-data.sh ----------
echo
echo "=== post-fs-data.sh ==="
if [ -f "$MOD_SRC/post-fs-data.sh" ]; then
  rewrite "$MOD_SRC/post-fs-data.sh" > "$T/mod/_pfd.sh"
  sh "$T/mod/_pfd.sh" >/dev/null 2>&1
  n=$(find "$T/odm/etc/camera/basictone" -type f 2>/dev/null | wc -l | tr -d ' ')
  ck "写入文件数" "$n" "22"
  # 校验写入内容与源完全一致（防止截断/错位写入）
  same=0; diff=0
  for ns in HDR SDR; do
    for i in 0 1 2 3 4 5 6 7 8; do
      a="$T/mod/system/odm/etc/camera/basictone/palette/$ns/SC16Lut$i"
      b="$T/odm/etc/camera/basictone/palette/$ns/SC16Lut$i"
      if cmp -s "$a" "$b" 2>/dev/null; then same=$((same+1)); else diff=$((diff+1)); fi
    done
  done
  ck "LUT 内容一致数" "$same" "18"
  ck "LUT 内容不一致数" "$diff" "0"
  # ini 也要一致
  isame=0
  for f in SimTool_Palette_Photo_Normal.ini SimTool_Palette_Photo_Portrait.ini SimTool_Palette_Photo_Night.ini SimTool_Preview_Palette_Normal.ini; do
    cmp -s "$T/mod/system/odm/etc/camera/basictone/setting/$f" "$T/odm/etc/camera/basictone/setting/$f" 2>/dev/null && isame=$((isame+1))
  done
  ck "ini 内容一致数" "$isame" "4"
  # LUT 数学一致性：从 ini 读 lut_size，校验文件大小 = lut_size^3 * 3 * 4
  INI="$T/odm/etc/camera/basictone/setting/SimTool_Palette_Photo_Normal.ini"
  if [ -f "$INI" ]; then
    LS=$(grep -A3 '^\[SCLut\]' "$INI" | grep '^lut_size' | head -1 | sed 's/[^0-9]//g')
    NUM=$(grep -A3 '^\[SCLut\]' "$INI" | grep '^SCLutNum' | head -1 | sed 's/[^0-9]//g')
    if [ -n "$LS" ] && [ -n "$NUM" ]; then
      expect=$((LS * LS * LS * 3 * 4))
      actual=$(wc -c < "$T/odm/etc/camera/basictone/palette/HDR/SC16Lut0" 2>/dev/null | tr -d ' ')
      ck "LUT 尺寸= lut_size^3*3*4" "$actual" "$expect"
      # 实际文件数应等于 SCLutNum
      cnt=$(find "$T/odm/etc/camera/basictone/palette/HDR" -name 'SC*Lut*' 2>/dev/null | wc -l | tr -d ' ')
      ck "HDR LUT 个数= SCLutNum" "$cnt" "$NUM"
    else
      echo "  SKIP ini 里没解析到 lut_size/SCLutNum"
    fi
  fi
  rm -f "$T/mod/_pfd.sh"
else
  echo "  SKIP 文件不存在"
fi

# ---------- 测试 service.sh autoreg ----------
echo
echo "=== service.sh autoreg ==="
if [ -f "$MOD_SRC/service.sh" ] && [ -f "$DBP" ]; then
  rewrite "$MOD_SRC/service.sh" > "$T/mod/_svc.sh"
  touch "$T/data/.pkg_installed"
  rm -f "$T/data/sql_trace.log"
  sh "$T/mod/_svc.sh" >/dev/null 2>&1
  if [ -f "$T/data/sql_trace.log" ]; then
    d=$(grep -c 'DELETE FROM' "$T/data/sql_trace.log" 2>/dev/null || true)
    ck "幂等 DELETE 次数" "${d:-0}" "3"
    i=$(grep -c 'INSERT INTO modules_state' "$T/data/sql_trace.log" 2>/dev/null || true)
    ck "INSERT modules_state 次数" "${i:-0}" "1"
  else
    echo "  FAIL autoreg 未执行（sql_trace.log 不存在）"; FAIL=$((FAIL+1))
  fi
  rm -f "$T/mod/_svc.sh"
else
  echo "  SKIP 缺 service.sh 或数据库"
fi

# ---------- 测试 customize.sh 三分支 ----------
echo
echo "=== customize.sh ==="
if [ -f "$MOD_SRC/customize.sh" ]; then
  APK_SRC=$(find "$MOD_SRC" -maxdepth 1 -name '*.apk' | head -1)
  if [ -n "$APK_SRC" ]; then
    # 分支 A：首次安装（pm path 失败）
    rm -f "$T/data/.pkg_installed"
    cp "$APK_SRC" "$T/mod/PaletteUnlock.apk"
    rewrite "$MOD_SRC/customize.sh" > "$T/mod/_cu.sh"
    OUT=$(sh "$T/mod/_cu.sh" 2>&1)
    echo "$OUT" | grep -q '模块已安装' && r=installed || r=other
    ck "首次装走已安装分支" "$r" "installed"
    ck "首次装后 mod 内 APK 被移除" "$([ -f "$T/mod/PaletteUnlock.apk" ] && echo still || echo removed)" "removed"
    ck "备份到 /data" "$([ -f "$T/data/palette_unlock/PaletteUnlock.apk" ] && echo yes || echo no)" "yes"

    # 分支 B：升级（pm path 成功）
    cp "$APK_SRC" "$T/mod/PaletteUnlock.apk"
    OUT=$(sh "$T/mod/_cu.sh" 2>&1)
    echo "$OUT" | grep -q '已更新（保留原有作用域配置）' && r=updated || r=other
    ck "升级走保留配置分支" "$r" "updated"

    # 分支 C：APK 尺寸异常（<100KB）
    head -c 50000 "$APK_SRC" > "$T/mod/PaletteUnlock.apk"
    OUT=$(sh "$T/mod/_cu.sh" 2>&1)
    echo "$OUT" | grep -q 'APK 尺寸异常' && r=caught || r=missed
    ck "尺寸异常被拦截" "$r" "caught"

    # 分支 D：尺寸够但不是 zip
    head -c 200000 /dev/zero | tr '\0' 'A' > "$T/mod/PaletteUnlock.apk"
    OUT=$(sh "$T/mod/_cu.sh" 2>&1)
    echo "$OUT" | grep -q '不是有效 zip' && r=caught || r=missed
    ck "非 zip 被拦截" "$r" "caught"

    rm -f "$T/mod/_cu.sh"
  else
    echo "  SKIP 模块内无 APK（构建后的包才有）"
  fi
else
  echo "  SKIP 文件不存在"
fi

# ---------- 测试 uninstall.sh ----------
echo
echo "=== uninstall.sh ==="
if [ -f "$MOD_SRC/uninstall.sh" ]; then
  rewrite "$MOD_SRC/uninstall.sh" > "$T/mod/_un.sh"
  touch "$T/data/.pkg_installed"
  mkdir -p "$T/data/palette_unlock"
  touch "$T/data/palette_unlock/x.apk"
  sh -x "$T/mod/_un.sh" > "$T/data/_un_trace.log" 2>&1
  if [ -n "${DEBUG_TEST:-}" ]; then echo "--- uninstall trace ---"; cat "$T/data/_un_trace.log"; fi
  [ -f "$T/data/.pkg_installed" ] && r=still || r=removed
  ck "卸载 APK" "$r" "removed"
  [ -d "$T/data/palette_unlock" ] && r2=still || r2=removed
  ck "删备份目录" "$r2" "removed"
  rm -f "$T/mod/_un.sh"
else
  echo "  SKIP 文件不存在"
fi

echo
echo "=============================="
echo " 通过 $PASS   失败 $FAIL"
echo "=============================="
[ "$FAIL" = "0" ] || exit 1
