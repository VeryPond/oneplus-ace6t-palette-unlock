# 一加 Ace 6T 调色盘移植 —— 实现逻辑（v6.8）

## 0. 目标
机器：OnePlus Ace 6T（PLR110），ColorOS 17.0.0.101，相机 com.oplus.camera 7.006.123
目标：把一加16（PYB110 / OTA 17.0.1.201）的「调色盘 + 滤镜」完整搬过来，并做到原生体验（可调影调/饱和度/滤镜强度/柔光，可切滤镜）

## 1. 整体架构：两个模块 + 一层运行时注入

```
Magisk/KernelSU 模块 palette_unlock（管「资源与 native 环境」）
   ├─ overlay 挂 /odm/etc/camera/basictone（只读分区，只能 overlay）
   ├─ 铺 22 个 palette 资源（HDR/SDR SC16Lut0-8 + 4 个 ini）
   └─ Meishe 滤镜库修正（bind mount）

LSPosed 模块 com.palette.unlock（管「Java 层开关与渲染链」）
   ├─ 8~11 层 hook（下面逐层说）
   ├─ 补丁 dex（把一加16 的真实色表类塞进相机 ClassLoader）
   └─ 资源二次落点：assets → app 私有目录（PathInject 重定向）

相机进程 com.oplus.camera
   ├─ 类加载：相机 dex + 我们注入的补丁 dex
   ├─ 资源读取：/odm（overlay）+ app 私有 files/palette_unlock/basictone（重定向后）
   └─ 渲染：Meishe 滤镜 SDK + 64³ LUT + 16³ SC LUT
```

## 2. 为什么必须 overlay，不能直接写 /odm
/odm 是 erofs 只读。改写唯一可行路径是 overlay mount，且 **lowerdir 不能用 /data/adb**（SELinux 上下文冲突，内核返回 EINVAL），必须先把资源复制到 tmpfs（/dev/palette_src）再挂：

```sh
mount -t overlay overlay -o lowerdir=/dev/palette_src:/odm/etc/camera/basictone /odm/etc/camera/basictone
```

模块的 post-fs-data.sh 做这件事，并校验 18 个 LUT 数量。

## 3. 补丁 dex：让 6T 相机认识「真实色表」
6T 相机 APK 里没有 `PaletteColorTable` / `PaletteColorRow` / `PaletteSeekbarColorTable` 这些类（一加16 才有）。
做法：把一加16 的这几个类编译成 6 个独立 dex 打在模块 assets/patch/ 下，运行时：

1. `InMemoryDexClassLoader` 加载这 6 个 dex
2. 把它们的 Element **注入相机 ClassLoader 的 DexPathList**（反射 pathList → dexElements 合并）
3. 这样 `PaletteColors.get(key)` 就能返回**真实色表**（不是 stub）

日志证据：
```
补丁 dex 加载完成: 6 个
注入前: 相机 dexElements=1, 补丁=1  →  注入后: 2
补丁色表可用: true
真实 PaletteColors.get(fuji_cc.bin) sec=[…]/[0.0,0.43,1.0]/d0 emp=[…]
```

> 打包注意：LSPosed 用 zip 里**第一个 .dex**（classes.dex）做模块入口，所以补丁 dex 必须排在最后；resources.arsc 必须 STORED + 4 字节对齐；只签 v3。

## 4. 开关层：让相机「以为」自己支持调色盘
| Hook | 作用 |
|---|---|
| CameraConfigHook | 强制 `com.oplus.feature.color.palette.support = true` |
| SupportFunctionHook | 泛匹配 47 个方法，让 `key_support_palette` 等返回 true |
| SupportFlagHook | 同上（另一套混淆名） |
| IPUFeatureHook | `isFeatureSupport(PALETTE)` → true；`createFeature` 放行 PaletteProcessUnit |
| CameraParameterHook | 补齐相机参数表里缺失的 palette 项 |
| PaletteProcessUnitHook | 观测/接管 PaletteProcessUnit 全链路（影调重算、LUT 上传、t0.G 应用） |

## 5. 滤镜黑屏的根因与修复（v5.8）
现象：切滤镜 → 预览全黑，不崩、HAL 仍出帧、成片正常。

根因：
```
/product/lib64/libMsEffectSdk.so      370264B  没有 nativeInitWithOptions 这个 JNI 符号
/my_product/lib64/libMsEffectSdk.so   377968B  有（3 处）
```
相机加载的是 /product 那份 → `MeisheRender.initMsRenderUtil` 抛 `UnsatisfiedLinkError` → 滤镜 SDK 上下文从未初始化 → 一选滤镜就输出空帧。

修复：把 /my_product 的正确版本 bind mount 到 /product/lib64，并做两层开机自愈：
- `/data/adb/post-fs-data.d/zz_meishe_lib_fix.sh`（早期）
- `/data/adb/service.d/zz_meishe_lib_fix.sh`（晚期再兜一次，防 OPCameraPro 覆盖）

校验方式：`md5sum /product/lib64/libMsEffectSdk.so` 应为 `01ffc9546ea77019f17d9e340a121948`。

## 6. 影调轴失效的根因与修复（v6.3→v6.7）
三个独立问题叠在一起：

### 6.1 资源路径：相机进程读不到 /odm
`Lut3dDataManager` 里写死了：
```
Y  = /odm/etc/camera/filters_lut/
Z  = /odm/etc/camera/basictone/lmt
a0 = /odm/etc/camera/basictone/odt
b0 = /odm/etc/camera/basictone/setting
```
但相机进程访问 /odm 时目录「不可列」（PathInject 日志反复出现）。于是把 **LMT 41 个文件（23MB）+ ODT 2 个文件（6MB）也打进 APK assets**，在 `CombineLutProcessUnitSDK.init(lmt, odt, setting, int, int, paletteStr)` 这个 hook 里把三个路径参数全部改成 app 私有目录：

```
palette init 原始参数: [0]=lmt [1]=odt [2]=setting …
palette init 成功！odt=0 setting=/data/user/0/com.oplus.camera/files/palette_unlock/basictone/setting
```

### 6.2 写路径被强制归零（直接原因）
调色盘每次拖动会调用：
```
k0.onToneSaturationChanged [0]=-17 [1]=-5 [2]=2     ← tone=-17, saturation=-5
f0.l2(-17) / f0.l2(-5)                              ← 影调 / 饱和度各走一遍
ag.u.d(tone, flag, str)                             ← 持久化写入
```
v5.1 遗留的「风格效果开关」hook 在开关 off 时把 **tone 改成 0 再写入**：
```
风格效果=off, ag.u.d(-17) -> 传 0
```
→ 影调永远以 0 保存，画面当然不变。修复：删除这条强制归零（保留观测），日志变成：
```
ag.u.d(57, true, common_fuji_cc.bin_rear) 放行（v6.7 不归零）
```
（此前 v5.9 已删掉读取侧的 `ag.u.f` / `ag.x.f` 归零，那两条会造成饱和度轴被钉死。）

### 6.3 ini 开关
影调链路需要 LMT 和 ODT 通道，模块 ini 原来写的是 `LMT=0 / ODT=1(仅 forAppSwitch)`，改成：
```
[switch]  LMT=1  SC=1  ODT=1
```
SC 是饱和度（一直开，所以饱和度一直能用）。

## 7. 渲染链（当前实际跑通的路径）
```
拖动色盘
 └─ k0.onToneSaturationChanged(tone, sat)
     ├─ f0.l2(tone)  ── tone 进入 PaletteProcessUnit
     ├─ f0.l2(sat)   ── sat 进入 PaletteProcessUnit
     └─ ag.u.d(tone) ── 持久化（现在不再被归零）
          ↓
     CLH.process(a, b, sat, c) -> float[786432]   ← 64³ LUT，由饱和度驱动
          ↓
     Lut3dDataManager.p()  →  k/i 标志位
          ↓
     gl.s.v(LUT, 64, true)  →  t0.G(float[])  →  GPU 上屏
          ↓
     PaletteSeekbarColors / PaletteColors 用补丁 dex 提供的真实色表渲染 UI 渐变
```

## 8. 安全阀（可回退）
调试 prop（都在 `debug.palette.*`）：
| prop | 默认 | 作用 |
|---|---|---|
| camparam / supportflag / lut3d / ppu / pathinject | 1 | 各层 hook 总开关 |
| lut3dforce | 1 | Lut3dDataManager.e() 返回 false 时强制 true |
| forceupload | **0** | CLH 算完 LUT 后由我们硬推 gl.s.v —— 会覆盖预览槽位，**默认必须关** |
| forcet0 | **0** | p() 里强制 k/i=true —— 同上，开了会饱和度+影调双死 |

## 9. 验证方法（全程用这套）
1. `adb shell screencap` → 裁剪预览区 (60,400)-(1200,1700) 求 mean RGB：黑屏 ≈0.2~0.5，正常 ≈80~120，滤镜切换后各次值应互不相同
2. `uiautomator dump` 读 `palette_tone_value` / `palette_saturation_value` / `palette_filter_strength_value`
3. `logcat -b crash | grep -c signal` 必须为 0；`logcat | grep initMSContext failed` 必须为 0
4. 必须 `am force-stop` 后重启相机，否则 `am start` 复用旧进程会得到假结果

## 10. 交付物
| 文件 | 说明 |
|---|---|
| palette_unlock_v6.8_allinone.zip | 一体化模块（v6.8 / RC68）：APK + 22 palette 资源 + ini(LMT/ODT=1) + overlay + Meishe 修复 |
| v67_signed.apk | LSPosed 模块 APK（versionCode 67，内含 65 个 assets：18 palette + 4 ini + 2 ODT + 41 LMT） |
| zz_meishe_lib_fix.sh | 滤镜库修正脚本（已装到设备两处启动目录） |
