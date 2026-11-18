# 踩过的坑

## 1. libxposed 模块必须有三件套

```
assets/xposed_init                  -> com.palette.unlock.MainHook
META-INF/xposed/module.prop         -> minApiVersion=102 / targetApiVersion=102
META-INF/xposed/java_init.list      -> com.palette.unlock.MainHook
```

**aapt2 link 不会把 META-INF 打进 APK**，必须打包后用 python zipfile 或
`aapt add` 手工注入。

只放 `assets/xposed_init` 的话，模块能装上、LSPosed 也能识别出包名，
但**入口类不会被加载**，表现是什么都不发生。

## 2. resources.arsc 必须不压缩

重打包 APK 时如果对 `resources.arsc` 做了 deflate：

```
INSTALL_FAILED: Targeting R+ (version 30 and above) requires the resources.arsc
of installed APKs to be stored uncompressed and aligned on a 4-byte boundary
```

python zipfile 写 zip 时必须对这个条目用 `ZIP_STORED`。

## 3. 卸载重装会丢 LSPosed 配置

LSPosed 的 `modules_config.db` 里：

- `modules` 表：靠包名扫描自动重建
- `modules_state` 表：**不会重建**（enabled 标志）
- `scope` 表：**不会重建**（作用域）

所以 `adb uninstall` 再装，模块会「在列表里但没启用、没作用域」，
相机里完全没反应。

需要重写这两张表，或者干脆重装后在 LSPosed 管理器里手动勾选。

## 4. 不能手动重启 lspd

```bash
# 以下都会把 bridge 弄断
lspctl stop
./daemon          # 少了 --force
pkill -f lspd
```

断开后的表现：

```
W/LSPosedService: system service is not ready, skip scope request
```

此时 `system_server` 不再被注入，任何模块都不生效，**只能整机重启恢复**。

正确做法：改完数据库，直接 `adb reboot`。

## 5. 软重启无效

「软重启」（只重启 zygote/system_server）不能让 Zygisk 重新注入：

- Zygisk 的注入点在 zygote fork 阶段
- `system_server` 的注入窗口一旦错过就没了
- 判断方法：`cat /proc/uptime`，软重启只增约 200 秒

**必须硬重启（`adb reboot`）。**

## 6. 混淆类名不能硬编码

见 `docs/FINDINGS.md` §4。不同 ROM 的 `getSupportFunction` 实现类名完全不同，
硬编码会导致 `ClassNotFoundException`，hook 静默失败（`installed=0`）。

## 7. Recipe 构造函数签名

`PaletteColorRecipe` 在本机型上没有 `(int[], float[])`。
用错签名的后果是 `getConstructor` 抛 `NoSuchMethodException`，
被 `catch (Throwable)` 吞掉，日志里只有一行：

```
PaletteColorsFix skipped | java.lang.NoSuchMethodException: ...<init> [class [I, class [F]
```

**这个类依然会崩**，因为桩没装上。必须用真机实际存在的签名
`(int[], float[], int[], float[])`。

> 教训：hook 失败要打醒目日志，并且每次装完必须去确认 `installed=N` 的 N 对不对。

## 8. 构建路径不能有中文

`d8`、`aapt2` 对非 ASCII 路径处理有问题，会报：

```
Invalid output: E:\<中文路径>
Output must be a .zip or .jar archive or an existing directory
```

构建时把源码、android.jar、输出目录都复制到 ASCII 路径（本仓库用 `C:\palettebuild`）。

## 9. PowerShell 会把 javac 的 warning 当失败

`javac` 的 unchecked 警告走 stderr，PowerShell 在
`$ErrorActionPreference='Stop'` 下会把它变成 NativeCommandError。
构建脚本里对 javac/d8 这些命令要放宽错误处理，靠 `$LASTEXITCODE` 判断。
