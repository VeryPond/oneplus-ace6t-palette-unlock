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
