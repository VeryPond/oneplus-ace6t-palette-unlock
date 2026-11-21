# oneplus-ace6t-palette-unlock

**一加 Ace 6T（PLR110）相机调色盘解锁模块**

---

## ✅ v4.1 真机验证（历史里程碑）

```
1. 模块版本:      v4.1
2. 资源挂载:      overlay on /odm/etc/camera/basictone  ✅
3. LUT 完整性:    18/18  ✅
4. palette ini:   4/4    ✅
5. LUT4 SHA:      45c26b247cc62abd  ✅（修复前是损坏数据）
6. native 层:     iPaletteFlag = 1 + Palette process success!
7. LSPosed:       模块在相机进程内正常 hook
```

---

## 🔑 核心突破：tmpfs overlay

`/odm` 是**只读 erofs**，无法 `cp`/`mkdir`，也无法直接 bind mount。

**之前 47 个版本全部失败的根本原因就在这里。**

### 可行的挂载方式

```bash
# 1. 复制资源到 tmpfs（/dev 是 tmpfs，不受 SELinux 上下文限制）
cp -a "$SRC/." /dev/palette_src/

# 2. 双 lowerdir overlay，无 upperdir
mount -t overlay overlay \
  -o "lowerdir=/dev/palette_src:/odm/etc/camera/basictone" \
  /odm/etc/camera/basictone
```

### 试过的失败方案

| 方案 | 结果 |
|---|---|
| `cp` 到 /odm | ❌ Read-only file system |
| `bind mount` | ❌ 目标目录无法 mkdir |
| overlay 到 `/odm/etc` | ❌ Invalid argument |
| overlay lowerdir 用 `/data/adb/...` | ❌ Invalid argument（SELinux 上下文冲突） |
| mountify 挂载 | ❌ busybox 被 OPPO secureguard_new 拦截 |

---

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

## v6.8 更新（当前版本）—— 滤镜 + 影调全部修好

**两个卡的很久的问题都在这一线解决了。**

### ① 切滤镜必黑屏 → Meishe 滤镜库版本错配

```
/product/lib64/libMsEffectSdk.so      370264B   缺 nativeInitWithOptions 这个 JNI 符号
/my_product/lib64/libMsEffectSdk.so   377968B   有这个符号（3 处）
```

相机进程静态地图确认它映射的是 `/product` 那份，于是
`MeisheRender.initMsRenderUtil` 抛 `UnsatisfiedLinkError`，
`IPU_MeisheFilterSdk: initMSContext failed` —— 滤镜 SDK 上下文从未初始化，
一选滤镜就输出空帧（不崩溃、HAL 继续出帧、成片正常）。

**修法**：把 `/my_product` 的正确版本 bind mount 到 `/product/lib64`，
并做两层开机自愈（`post-fs-data.d` 早期 + `service.d` 晚期兜底）：

```bash
md5sum /product/lib64/libMsEffectSdk.so    # 应为 01ffc9546ea77019f17d9e340a121948
```

### ② 影调轴数值变、画面不变 → 三个问题叠加

| # | 问题 | 修法 |
|---|---|---|
| 1 | 相机进程读不到 `/odm`（目录"不可列"），LMT/ODT LUT 没被加载 | 把 **LMT 41 个文件 + ODT 2 个文件也打进 APK assets**，并在 `CombineLutProcessUnitSDK.init(lmt,odt,setting,…)` hook 里把路径参数全指向 app 私有目录 |
| 2 | `ag.u.d(tone,…)` 写入被 v5.1 的「风格效果开关」hook **强制改成 0** | 删除强制归零，只保留观测 |
| 3 | 模块 ini 里 `LMT=0`（影调通道关着） | 改成 `[switch] LMT=1 / SC=1 / ODT=1` |

日志证据（修复前 → 修复后）：

```
风格效果=off, ag.u.d(-17) -> 传 0                    ← 影调被抹成 0 才保存
ag.u.d(57, true, common_fuji_cc.bin_rear) 放行（v6.7 不归零）
```

### 真机验证

| 场景 | 编辑器 | 滤镜1 | 滤镜2 | 滤镜3 | crash | Meishe 报错 |
|---|---|---|---|---|---|---|
| 修复前 | 95.8 | **0.4** | **0.4** | — | 0 | 有 |
| 修复后（重启后仍有效） | 103.8 | **89.6** | **108.8** | — | 0 | 无 |
| v6.7 | 105.3 | **94.4** | **103.4** | **116.6** | 0 | 0 条 |

（截图裁剪预览区 (60,400)-(1200,1700) 取 mean RGB；黑屏≈0.2~0.5，正常≈80~120）

影调/饱和度/滤镜强度三轴均可调，柔光可切。

### 交付物（v6.8）

```
release/palette_unlock_v6.8_allinone.zip   # 一体化模块（15.6MB，含 LMT/ODT 资源）
release/PaletteUnlock_v6.8.apk             # 单独 LSPosed 模块 APK
```

完整实现逻辑见 **[docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)**。

---

## v4.1 —— 历史（tmpfs overlay 突破）

**这一版改变了根本思路。** 之前一直在往只读的 `/odm` 铺 LUT，
但 **`CombineLutProcessUnitSDK.init` 收到的路径参数从来没变过**，
相机根本不会去读那些文件。

现在参考已验证可行的方案（X8CamUnlock，Find X8 上可用）：

| # | 做法 |
|---|---|
| 1 | 资源放**相机私有目录** `/data/data/com.oplus.camera/files/palette_unlock/basictone/` |
| 2 | hook `init` 改写路径参数（slot 1=odt / 2=setting / 5=palette） |
| 3 | 扫描 `ODT={0,1}` 找可用组合 |
| 4 | 9 个 `B3(String)` 类强制 `key_support_palette = true` |

### 版本演进

| 版本 | 关键改动 |
|---|---|
| v3.0 | 路径重定向（第十层） |
| v3.1 | ODT 扫描 + B3 全域（第十一层） |
| v3.2 | 延迟加载重试 + 4 级 Application 回退 |
| v3.3 | `build_verify.py` + `action.sh` 五段自检 |
| v3.4 | 部署前进程名校验 |
| v3.5 | 自检脚本多路径探测 + 精确计数 |
| v3.6 | 部署顺序：先 /odm 后 assets，官方 ini 优先 |
| **v3.7** | **补挂重试 1 次 → 20 次，触发点从 2 个扩到 4 个** |

### 刷完先自检

```bash
adb shell su -c 'sh /data/adb/modules/palette_unlock/action.sh'
```

**C 段最关键**，直接告诉你第十层有没有工作。

详细步骤见 **[docs/VERIFY.md](docs/VERIFY.md)**（真机验证手册）。

## 交付物（v6.8）

```
release/palette_unlock_v6.8_allinone.zip   # 一体化模块（15.6MB，含 LMT/ODT 资源）
release/PaletteUnlock_v6.8.apk             # 单独 LSPosed 模块 APK
```

刷入一体包 + 重启即可（内含滤镜库修正与 LMT/ODT 资源）。
历史版本：`release/palette_unlock_v4.1_allinone.zip`。

### 它做了什么

1. 自动安装 LSPosed 模块（**无桌面图标**）
2. 铺入 18 个官方 3D LUT + 4 个 ini 到 `/odm/etc/camera/basictone/`
3. 尝试自动注册 LSPosed 作用域

### 安装

```bash
adb push palette_unlock_v4.1_allinone.zip /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/palette_unlock_v4.1_allinone.zip'
adb reboot
```

刷入后**硬重启**（软重启无效）。若作用域未自动注册，在 LSPosed 管理器里给
「调色盘强开」勾选 `com.oplus.camera` 即可。

> **桌面不会有图标。** APK 的 manifest 去掉了 `LAUNCHER` intent-filter，
> 模块只以 LSPosed 模块形式存在。要看模块信息，去 LSPosed 管理器 →
> 模块 →「调色盘强开」。

## 本地自测（不需要真机）

脚本逻辑可以在电脑上验证，避免刷机后才发现问题：

```bash
./tools/test_scripts.sh ./magisk
```

它会造一个隔离的桩环境（假 MODPATH、假 /odm、桩化 pm/sqlite3/getprop），
实跑三个脚本并断言 14 项，包括：

- `post-fs-data.sh` 写入 22 个文件、LUT/ini 内容字节级一致
- `service.sh` 的 LSPosed 自动注册幂等（先删后插）
- `customize.sh` 首次装/升级/尺寸异常/非 zip 四分支
- `uninstall.sh` 卸载 APK 与删备份目录

> 为什么需要：静态 review 发现不了 `INSERT OR IGNORE` 在无唯一约束表上
> 不去重、`exit` 之后的死代码、`sed` 误替换子串这类问题，必须实跑。

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
| **[docs/VERIFY.md](docs/VERIFY.md)** | **真机验证手册（10 分钟完整流程）** |
| **[docs/PALETTE_ANALYSIS.md](docs/PALETTE_ANALYSIS.md)** | **完整逆向分析报告（40 轮）** |
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
