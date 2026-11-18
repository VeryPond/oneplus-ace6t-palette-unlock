# 一加 Ace 6T（PLR110 / SM8845）相机调色盘强开

> 本仓库由 DeepSeek Harness 生成。供研究与个人设备维护使用。改动有风险，务必先阅读回滚章节。

针对 OnePlus Ace 6T 做了一件事：把这个机型 ROM 里**没有开放**的相机「调色盘（Palette）」功能打开，并在真机上验证可用。

Ace 6T 的 `/odm/etc/camera/basictone/` 下**根本没有 `palette/` 目录**，相机 APK 里的调色盘代码也依赖两个本机型不存在的 SDK 类。所以这个仓库做的是三件事：

1. 把一加 15 / GT8 Pro 那批官方 3D LUT 资源移植过来（Magisk 模块）
2. 用 LSPosed 模块强制打开能力位，并**打桩**绕过缺失的两个类
3. 在真机上验证调色盘界面与调色功能确实可用

## 结果

真机实测（PLR110 / ColorOS 17.0.0.101 / 相机 7.006.123）：

| 项目 | 状态 |
|---|---|
| 调色盘入口 | 出现，可进入 |
| 影调 / 饱和度 / 柔光 | 可调 |
| 相机稳定性 | 不崩溃 |

界面元素（`dumpsys activity top` 实测）：

```
PalettePanelView               {467,0-804,337}    id=palette_panel_view
CameraPaletteFilterCOUISeekbar {471,372-801,442}  id=palette_filter_seekbar
PaletteRollingNumView                             id=palette_filter_strength_value
```

## v2.1 更新（当前版本）

本轮做了两件大事：

### A. 修复 5 处从未生效的 hook

用 `method_ids` → `proto_ids` 逐层核对真实签名后发现：

| 层 | 问题 | 修复 |
|---|---|---|
| 3 Lut3d | 找 `f(int,int,String)`，真名是 `e` | v2.0 |
| 5 FilterSdk | 类名少一层子包 | v2.0 |
| 8 IPUFeature | 签名猜成 `(Class)` | v1.9 |
| 2 SupportFunction | 只挂基类 | v1.7 |
| 4 CameraParameter | 完全是空壳 | v1.6 |

### B. 修复部署脚本不覆盖 palette

原 `post-fs-data.sh` 遇到同名不同内容文件会"保留 ROM 原版"，这对 palette 资源是错误的。v2.1 改为强制覆盖。

## v1.5 更新

在上版基础上新增第 7 层 hook `FilterSdkHook`：强制 `AbstractFilterSdk.isInited()` 返回 `true`。

**为什么**：底部「滤镜强度」滑条的回调 `onFilterIntensityChanged` 开头有道关卡——

```java
if (AbstractFilterSdk.isInited()) {  // 未初始化就直接 return
    ...
}
```

本机型的调色盘是强开出来的，SDK 可能从未真正 init。若日志里出现
`Xxx.isInited() 原始=false -> 强制 true`，即为此因。

覆盖 6 个候选类（不存在则静默跳过）：`AncFilterSdk` / `IpuFilterSdk` /
`FilterIpuAdapter` / `AbstractFilterSdk` / `GLFilterSdk` / `CpuFilterSdk`。

## v1.4 更新

**修复了 LUT 数据损坏问题**：之前从 ROM 提取的 LUT4~8 是错误数据（含 `build.prop` 明文），导致调色效果异常。现已用官方 `fsck.erofs` 从一加15 完整 OTA 重新提取，9 个 LUT 全部校验通过。

| LUT | 旧（损坏） | 新（正确） |
|---|---|---|
| 4 | `b053b864` | `45c26b24` |
| 5 | `97476050` | `fd4d798a` |
| 6 | `97476050` | `f3a77390` |
| 7 | `97476050` | `7265cf25` |
| 8 | `97476050` | `38496c97` |

## 交付物

```
release/palette_unlock_v2.1_allinone.zip
```

就这一个文件。刷入它即可。

### 它做了什么

1. 自动安装 LSPosed 模块（**无桌面图标**）
2. 铺入 18 个官方 3D LUT + 4 个 ini 到 `/odm/etc/camera/basictone/`
3. 尝试自动注册 LSPosed 作用域

### 安装

```bash
adb push palette_unlock_v2.1_allinone.zip /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/palette_unlock_v2.1_allinone.zip'
adb reboot
```

刷入后**硬重启**（软重启无效）。若作用域未自动注册，在 LSPosed 管理器里给
「调色盘强开」勾选 `com.oplus.camera` 即可。

> **桌面不会有图标。** APK 的 manifest 去掉了 `LAUNCHER` intent-filter，
> 模块只以 LSPosed 模块形式存在。要看模块信息，去 LSPosed 管理器 →
> 模块 →「调色盘强开」。

## 需要什么

- 已解锁 BL 并 root（本项目在 KernelSU + Zygisk Next + LSPosed 上验证）
- LSPosed 已正常工作（`system_server` 已注入）
- 目标机型相机 APK 与本文一致或接近

> **为什么还需要 LSPosed**：调色盘的「开关」和「缺失类」都在 Java 运行时层，
> 只有 Xposed 系能在进程内改方法返回值。Magisk 只能做文件系统 overlay，
> 碰不到运行时逻辑。一键包把两者打包在一起，但底层仍然需要 LSPosed 环境。

## 怎么工作

调色盘的总开关只有一个：

```java
// ja.k0#i0(String)
if (CameraConfig.d("com.oplus.feature.color.palette.support", false, false)) {
    return "common".equals(str) || "portrait".equals(str) || NIGHT.equals(str);
}
return false;
```

这个 key 为 false 时，下游全部走不到：`Lut3dDataManager` 不会加载 palette 路径，
`getSupportFunction("key_support_palette")` 恒 false，UI 入口不生成。

所以 LSPosed 模块分四层：

| 层 | 目标 | 作用 |
|---|---|---|
| 1 | `CameraConfig` 所有 `static boolean(String,...)` | 强制 `color.palette.support` = true **【核心】** |
| 2 | `BaseMode.getSupportFunction(String)` | 强制 `key_support_palette` = true；动态挂载，不硬编码子类名 |
| 3 | `PaletteSeekbarColors.get/hasSeekbarColors` | 打桩，绕过缺失的 `PaletteSeekbarColorTable` |
| 4 | `PaletteColors.get/hasPalette` | 打桩，绕过缺失的 `PaletteColorTable` **【决定成败】** |

### 为什么第 3、4 层必须打桩

这个机型的相机 APK 里，`ipusdk.processunit.filter.palette` 包下**四个类只有引用、没有定义**：

```
PaletteColorTable
PaletteSeekbarColorTable
PaletteColorRow
PaletteSeekbarColorRow
```

开关一打开，代码走到 `PaletteColors.get()` 就抛 `NoClassDefFoundError`，相机直接闪退：

```
FATAL EXCEPTION: main
java.lang.NoClassDefFoundError: Failed resolution of:
  com.oplus.ocs.camera.ipusdk.processunit.filter.palette.PaletteColorTable
    at PaletteColors.get(PaletteColors.java:1)
    at cg.k.j(FilterEffectUIManager.java:20)
    at ag.r.r1(FilterPresenter.java:22)
```

打桩后不再调用原方法，直接返回自造的 Recipe。

### 一个容易踩的坑：Recipe 构造函数

`PaletteColorRecipe` 在本机型上**没有** `(int[], float[])` 构造函数，
只有这些：

```
(int[], int[])
(int[], int[], int[])
(int[], int[], int[], int[])
(int[], float[], int[], float[])   <-- 实际使用
(int[], [F, I, [I, [F, I, [I, [F, I, [I, [F, I)
...
```

用错签名会导致 hook 静默失效（`NoSuchMethodException` 被 catch 吞掉），
表现为「桩装上了但这个类还是崩」。

## 文档

| 文件 | 内容 |
|---|---|
| [docs/FINDINGS.md](docs/FINDINGS.md) | 逆向结论：开关链路、逐行源码引用 |
| [docs/LUT.md](docs/LUT.md) | LUT 资源格式、来源、为什么 5~8 号是重复的 |
| [docs/BUILD.md](docs/BUILD.md) | 模块怎么编译打包签名 |
| [docs/DEPLOY.md](docs/DEPLOY.md) | 怎么装、怎么验证、怎么排错 |
| [docs/ROLLBACK.md](docs/ROLLBACK.md) | 怎么完全恢复原样 |
| [docs/PITFALLS.md](docs/PITFALLS.md) | 踩过的所有坑 |
| [docs/CREDITS.md](docs/CREDITS.md) | 参考项目与作者 |

---

## 目录

```
src/           LSPosed 模块源码（Java）
magisk/        Magisk 模块源码
release/       编好的交付包
docs/          说明文档（见上表）
tools/         构建与部署脚本
assets/        从 ROM 提取的 LUT 与 ini
```

## 兼容性

- 设备：OnePlus Ace 6T（`OP6117L1` / `PLR110`）
- 固件：`PLR110_17.0.0.101(CN01)` / ColorOS V17.0.0
- 平台：Snapdragon SM8845
- 相机：`com.oplus.camera` 7.006.123

一加 15 / GT8 Pro 的同批 LUT 资源理论上通用，但**本仓库只在 Ace 6T 上验证过**。

## 已知限制

- 只验证了 UI 与调色操作可用，**出片色彩准确性未逐项比对**
- 依赖硬改开关 + 打桩，非常规路径，后续 OTA 可能失效
- LUT 的 5~8 号在源 ROM 里就是重复内容，属官方原样

## 许可

仅供研究。刷机与改动风险自负。
