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


## 7. hook 静默失效的五种形态（本项目最深刻的教训）

Xposed hook 最可怕的地方是：**装错了不会报错，只是什么都不发生**。
本项目在 v1.0 → v2.2 的过程中，8 层 hook 有 5 层曾经完全没生效：

| 形态 | 错误写法 | 真实情况 | 表现 |
|---|---|---|---|
| **方法名错** | `f(int,int,String)` | `e(int,int,String)` | 匹配不到，静默跳过 |
| **类名少子包** | `filter.AncFilterSdk` | `filter.anc.AncFilterSdk` | ClassNotFoundException 被 catch |
| **类名不存在** | `filter.FilterIpuAdapter` | `filter.ipuadapter.FilterIpuAdapter` | 同上 |
| **签名猜错** | `isFeatureSupport(Class)` | `isFeatureSupport(Context, IPUFeature)` | 匹配不到，静默跳过 |
| **只挂基类** | hook `BaseMode` | 7 个子类全部覆写 | 子类调用绕过 hook |
| **完全空壳** | 只遍历字段打日志 | 根本没调 `module.hook()` | 什么都不做 |

### 正确做法：用 dex 结构量，不要猜

```python
# 1. 拿真实方法签名：method_ids -> proto_ids
mi = method_ids[i]              # (class_idx, proto_idx, name_idx)
off = proto_ids_off + proto_idx*12
shorty, return_type, params_off = struct.unpack_from('<III', data, off)
# params_off 处：u32 个数 + u16 类型索引数组

# 2. 拿真实继承链：class_defs.superclass_idx
cb = class_defs_off + i*32
class_idx, access, superclass_idx, interfaces_off, ... = struct.unpack_from('<IIII...', data, cb)

# 3. 判断是抽象还是有实现：class_data 里 code_off 是否为 0
#    code_off == 0  -> abstract / native
#    code_off != 0  -> 有字节码实现
```

### 排查顺序

1. **先确认入口类被加载**：日志里有没有 `onPackageLoaded pkg=xxx`
2. **再确认每层 hook 的 installed 计数**：`XxxHook installed=N`，N=0 就是没挂上
3. **打印真实参数值**：hook 里先打 `chain.getArg(i)` 再决定强制

本项目就是靠第 2 步发现 5 层 hook 的 `installed=0`。

## 8. EROFS fmt=6 压缩数据不能手写解码

调色盘 LUT 在 `odm` 里，其中 `palette/SC16Lut0-3` 是 `fmt=0`（未压缩），
`SC16Lut4-8` 是 `fmt=6`（压缩）。

手写 EROFS 压缩簇解码（LZ4 + tail-packing + COMPACT_2B 索引）极易出错，
本项目最初的错误提取把 `build.prop` 的明文当成了 LUT 数据，
表现为 LUT4-8 含 `# build prop start` 字符串、`NaN`、`-3.4e38` 极值。

**正确做法**：把整个 `odm.img` 导出，用官方工具解压

```bash
fsck.erofs --extract=/out /path/to/odm.img
```

校验方法（三遍）：

1. `sha256` 与官方 ROM 对比
2. `float32` 值域应全在 [0,1]，非零率 >90%
3. 与同目录 `lmt/SC16Lut*` 做统计特征对比（mean≈0.5、std 0.2~0.3）
