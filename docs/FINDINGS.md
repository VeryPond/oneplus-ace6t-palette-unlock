# 逆向结论

设备：OnePlus Ace 6T（PLR110 / SM8845 / ColorOS 17.0.0.101 / 相机 7.006.123）
分析对象：`/product/app/OplusCamera/OplusCamera.apk`（177,503,773 B，34 个 dex）

---

## 1. 资源侧：本机型缺料

| 路径 | Ace 6T | GT8 Pro | 一加 16 |
|---|---|---|---|
| `/odm/etc/camera/basictone/palette/HDR/SC16Lut0..8` | **无** | 有 | 有 |
| `/odm/etc/camera/basictone/palette/SDR/SC16Lut0..8` | **无** | 有 | 有 |
| `/odm/etc/camera/basictone/setting/SimTool_Palette_*.ini` | **无** | 有 | 有 |
| `/odm/etc/camera/basictone/lmt/SC16Lut0..8` | 有 | 有 | 有 |

`lmt/` 下的 SC16Lut 与 `palette/` 下的**不是同一批**，前者是基础色调 LUT，
后者是调色盘专用的 9 档预设。

## 2. 代码侧：类齐全，但缺 SDK

相机 APK 里调色盘相关类基本都在：

```
com.oplus.camera.common.utils.Lut3dDataManager
com.oplus.camera.common.view.PaletteRollingNumView
com.oplus.camera.feature.filter.view.CameraPaletteFilterCOUISeekbar
com.oplus.ocs.camera.CombineLutHelper
com.oplus.ocs.camera.ipuapi.process.palette.PaletteProcessUnit
com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors
com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView
com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteSeekbarColors
```

**但是** `ipusdk.processunit.filter.palette` 这一层的四个类只有引用没有定义：

```
PaletteColorTable          type_ids 有，class_defs 无
PaletteSeekbarColorTable   type_ids 有，class_defs 无
PaletteColorRow            type_ids 有，class_defs 无
PaletteSeekbarColorRow     type_ids 有，class_defs 无
```

这是 OPPO 的可选 SDK 分层：相机 APK 假设这几个类由另一个组件提供，
但本机型没有随包下发。

## 3. 开关链路

### 3.1 总开关

```java
// ja.k0#i0(String) —— 调色盘总判定
public static boolean i0(String str) {
    if (CameraConfig.d("com.oplus.feature.color.palette.support", false, false)) {
        return ReportThread.MSG_COMMON.equals(str)
            || "portrait".equals(str)
            || FilterProcessParameter.FilterGroupType.NIGHT.equals(str);
    }
    return false;
}
```

### 3.2 能力位

```java
// BaseMode 的子类，getSupportFunction(String)
switch (str.hashCode()) {
    case -1946495957:
        if (str.equals("key_support_palette")) b10 = 9;
        break;
    ...
}
switch (b10) {
    case 9:
        return ReportThread.MSG_COMMON.equals(this.T0)
            && CameraConfig.d("com.oplus.feature.color.palette.support", false, false);
}
```

**palette 能力位的最终条件就是 CameraConfig 那个 key**。

### 3.3 LUT 初始化分流

```java
// Lut3dDataManager#f(int, int, String)
if (!this.L) { return false; }               // L = CameraConfig.d("com.oplus.camera.basic.tone.lut.support")
boolean zI0 = k0.i0(str);                     // palette 判定
if (zI0) {
    init = this.M.init(mLmt, mOdt, mSetting, i10, i11, "/odm/etc/camera/basictone/palette");
} else {
    init = this.M.init(mLmt, mOdt, mSetting, i10, i11);   // 无 palette 路径
}
```

第六个参数是**硬编码**的 `/odm/etc/camera/basictone/palette`。

### 3.4 崩溃链

开关强行打开后，走到的调用链：

```
盘 → FilterEffectUIManager.j()
   → PaletteColors.get(String)              <-- 这里抛 NoClassDefFoundError
   → ag.r.r1()  (FilterPresenter)
   → ag.r.L3()
```

## 4. 混淆名因机而异（重要）

`getSupportFunction` 的实现类名**不同 ROM 不同**：

| ROM | 通用拍照 | 人像 | 夜景 |
|---|---|---|---|
| GT8 Pro（24831） | `fm.m3` | `hm.d1` | `hm.t0` |
| Ace 6T（PLR110） | `em.g3` | `gm.e1` | `gm.s0` |

所以模块**不能硬编码类名**，改成反射 `com.oplus.camera.module.BaseMode` 再
匹配 `getSupportFunction(String)->boolean` 的方法。

实际上 `BaseMode` 自身也声明了该方法，直接挂它即可覆盖子类默认实现。

## 5. 方法

- 资源：EROFS 解析 `gt8rom/odm.img`，直接读 inode 数据
- DEX：自写 DEX 解析器（`tools/dexscan/dexlib.py`），解析 string_ids / type_ids /
  class_defs / code_item，做 const-string 引用与 class_defined 判定
- 反编译：jadx 1.5.6 单类模式
- 真机：`adb shell logcat` + `dumpsys activity top` + `uiautomator`


## 6. 八层 hook 架构（v2.2）

调色盘从「UI 入口」到「实际调色」要过 5 个断点，对应 8 层 hook：

```
① UI 打开        ← hook1 CameraConfig + hook2 SupportFunction + hook6 桩
       ↓
② ProcessUnit 创建 ← hook8 IPUClient.isFeatureSupport
       ↓
③ LUT 纹理创建    ← hook3 Lut3dDataManager.e
       ↓
④ LUT 数据正确    ← 资源层（官方 LUT）
       ↓
⑤ 参数下发        ← hook7 FilterSdk.isInited
```

| # | 目标 | 真实签名 | 作用 |
|---|---|---|---|
| 1 | `CameraConfig` | `d(String,Z,Z)->Z` 等 | 配置键强制 true |
| 2 | `BaseMode.getSupportFunction` 及其 7 个子类 | `(String)->Z` | 能力位 key_support_palette |
| 3 | `Lut3dDataManager.e` | `(int,int,String)->Z` | LUT 初始化，失败则无纹理 |
| 4 | `CameraParameter` | 字段扫描 | palette CaptureRequest.Key |
| 5 | `AncFilterSdk/IpuFilterSdk/FilterIpuAdapter.isInited` | `()->Z` | 滤镜 SDK 就绪判定 |
| 6 | `PaletteColors/PaletteSeekbarColors.get` | `(String)->XxxRecipe` | 绕过缺失的 Table 类 |
| 7 | `FilterSdk.isInited` | `()->Z` | 强度回调前置关卡 |
| 8 | `IPUClient.isFeatureSupport` / `createFeature` | `(Context,IPUFeature)->Z` / `(Context,Class)->IProcessUnit` | ProcessUnit 创建入口 |

### 关键类位置（一加 6T / PLR110）

| 类 | dex | 备注 |
|---|---|---|
| `Lut3dDataManager` | classes.dex | 30 个方法，`e` 是初始化 |
| `FilterMenuCanvas2D` | classes7.dex | `initBasicToneConvertEnv` 三道门槛 |
| `FilterIpuAdapter` | classes7.dex | 调 `IPUClient.createFeature` |
| `IPUClient` | classes23.dex | 仅 3 个方法 |
| `AncFilterSdk` | classes12.dex | `isInited` 有实现 |
| `PaletteProcessUnit` | classes13.dex | 只被自身调用，被动执行单元 |

### LUT 初始化三道门槛

```java
FilterMenuCanvas2D.initBasicToneConvertEnv() {
    if (!GLModelParam.isNeedBasicToneConvert()) return;      // ①
    if (!CameraConfig.d("com.oplus.camera.preview.hdr.support")) return;  // ②
    if (!Lut3dDataManager.e(mode, hdr, group)) {             // ③
        Log.e("initBasicToneConvert, initializeBasicToneEnv fail");
        return;
    }
    createOrUpdateLut3dTexture();   // 只有全过才创建
}
```

hook3 把 ③ 的返回值强制 true，是让纹理能被创建的关键。

---

## 【2026-11-19 真机验证成功】tmpfs overlay 方案

### 问题

`/odm` 是只读 erofs：

```
/dev/block/dm-38 on /odm type erofs (ro,seclabel,relatime,...)
touch: '/odm/test_write': Read-only file system
```

所以 `post-fs-data.sh` 里的 `cp` 全部失败：

```
WARN palette/HDR/SC16Lut0 目标分区不可写, 已回退到 Magisk overlay 生效
（22 个文件全部如此）
```

### 试过的失败方案

| 方案 | 结果 |
|---|---|
| `cp` 到 /odm | Read-only file system |
| `bind mount` | 目标目录无法 mkdir |
| overlay 到 `/odm/etc` | Invalid argument |
| overlay lowerdir 用 `/data/adb/...` | Invalid argument（SELinux 上下文冲突） |
| mountify 挂载 | busybox 被 OPPO secureguard_new 拦截 |

### 关键发现

```
[secureguard_new][root_check]oplus_report_execveat_new,
  common /data/adb/ksu/bin/busybox result execve_block
```

OPPO 的安全模块在 post-fs-data 阶段拦截 busybox，mountify 因此无法完成挂载。

### 可行方案：tmpfs 中转

```bash
# 1. 复制资源到 tmpfs（/dev 是 tmpfs，不受 SELinux 上下文限制）
cp -a "$SRC/." /dev/palette_src/

# 2. 双 lowerdir overlay，无 upperdir
mount -t overlay overlay \
  -o "lowerdir=/dev/palette_src:/odm/etc/camera/basictone" \
  /odm/etc/camera/basictone
```

**为什么这样能成：**
- tmpfs 与目标上下文兼容
- 两个 lowerdir 不需要 upperdir，不依赖 xattr
- 不需要 mountify、不需要 ext4 镜像、不需要 busybox

### 真机验证结果

```
mount: overlay on /odm/etc/camera/basictone type overlay
       (lowerdir=/dev/palette_src:/odm/etc/camera/basictone)

LUT 完整性: 18/18
palette ini: 4/4
LUT4 SHA: 45c26b247cc62abd  ✅ 与官方一致

native 层:
  Param_BasicTone_Base::BasicToneProcessNext: iPaletteFlag = 1
  BasicTonePaletteProcessNext() Palette mode!
  BasicTonePaletteMalloc() m_iPaletteMalloced = 1
  fuseSCLut() SC4_5_7_8
  BasicTonePaletteProcessNext() Palette process success!
```

### 认知修正

之前判断「native 层 palette flag 不可达」是**错的**。

实际是：**资源到位后，相机自己会把 `iPaletteFlag` 置为 1**，不需要 LSPosed 干预。

之前所有版本失败的根本原因是资源没挂上，而不是 flag 打不开。
