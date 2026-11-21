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

## v5.1 调色盘归零接入「风格效果」开关

### 问题
调色盘参数关闭相机后总是保留，不受设置里「设置 → 保留设置 → 风格效果」开关控制。

### 开关定义
| 项 | 值 |
|---|---|
| 界面文案 | `camera_setting_preserve_settings_style` |
| 偏好键 | `OptionKey.KEY_PRESERVE_STYLE` = `pref_preserve_style_key` |
| DataKey | `n9/d.Q2` |
| 取值 | `"on"` / `"off"`，默认 `"off"` |
| 读取 | `DataManager.getInstance().b(n9/d.Q2, "off")` |
| 控件 | `CameraSettingPreferenceFragment(p2).Q2 : CameraSwitchPreference` |

### 恢复调用链（关键）
参数被保留的真正原因不是写入，而是**冷启动时被读回来**：

```
相机冷启动
  → ag.r.onPreviewStageChanged
    → dispatcherEventMessagePreviewRequestStage
      → ag.r.A3()
        ├─ if (!P2()) return
        ├─ if (N2()) skip
        ├─ v0 = c2()                          // 场景名，如 common_default_rear
        ├─ ag.u.f(v0)  → 恢复 tone            // 持久化值 100
        ├─ ag.x.f(v0)  → 恢复 saturation
        └─ ag.v.d(v0, b2())                   // 其他参数
```

### 修复
hook `ag.u.f(String)` 与 `ag.x.f(String)`，当开关为 `off` 时返回 `0`：

```java
if (!isPreserveStyleOn()) return 0;
return chain.proceed();
```

### 验证证据
```
ag.u.f 原返回值=100 -> 返回 0
ag.x.f 开关=off，饱和度恢复值强制 0
CLH.process(0,0,0,0) -> mean=0.4999   // 中性 LUT，无调色
```

### 踩过的坑
| 坑 | 说明 |
|---|---|
| 改 `ag.u.d()` 无效 | `d()` 是**写入**动作，真正复活参数的是冷启动的 `f()` **读取** |
| 装完新 APK 无日志 | `am start` 复用旧进程；必须 `am force-stop` + `pkill` |
| `install()` 里直接 `cl.loadClass("n9.d")` | 会让后续 hook 全部中断，必须懒加载 |

## v5.2 恢复 X10 原生行为（放弃强制归零）

### 决策
v5.1 尝试让调色盘参数受「设置 → 保留设置 → 风格效果」开关控制，**失败**。最终决定：**保持与 X10 完全一致的原生行为**，不干预持久化。

### 失败原因（记录以免重蹈）
| 尝试 | 结果 |
|---|---|
| hook `ag.u.d()` 改传 0 | ❌ 它是写入动作，不是复活点 |
| hook `ag.u.f()` 返回 0 | ❌ 只改 UI 显示，内部 `a(100,false)` 已执行 |
| hook `ag.u.b()` 静态读取返回 0 | ❌ 同样只影响显示 |
| `DataManager.b(n9/d.Q2, "off")` | ❌ 读到的恒为 `on` |
| 扫描 `n9/d` 全部 320 个 DataKey 字段 | ❌ 无字段对应 `pref_preserve_style_key` |
| 扫描 `n9/a..j` 全部类 | ❌ 同上 |
| `DataManager.findDataKey(String)` | ❌ 方法不存在 |

### 根因
「风格效果」开关的读取方式是 `CameraSwitchPreference.z("off")`，而它只是 `invoke-super Preference.z(String)`，读的是 **Preference 对象自己缓存的值**。该对象**只在设置页（`p2`）创建时加载**，相机主进程不持有它，因此运行时无法获取开关状态。

### 当前行为
- 调色盘参数**照常持久化**（与 X10 原生一致）
- 补丁色表、LUT 处理、渲染链全部正常
- 无任何强制归零 hook

### 验证
```
归零逻辑残留: 0 条
补丁色表可用: true
hooked PaletteColors.get(String) -> stub recipe
CLH.process(0,0,100,100) -> mean=0.5438
```

## v5.8 切滤镜黑屏根因：Meishe 滤镜库版本错配（已修）

```
/product/lib64/libMsEffectSdk.so      370264 B  md5 d90585ee...  nativeInitWithOptions 符号数 0
/my_product/lib64/libMsEffectSdk.so   377968 B  md5 01ffc954...  nativeInitWithOptions 符号数 3
```

相机 maps 确认映射的是 /product 那份，于是：

```
D nativeloader: Load libMsEffectSdk.so ... ok
E om.oplus.camera: No implementation found for long
   com.meicam.effect.sdk.MsEffectSdkContextUtils.nativeInitWithOptions(int, java.util.Map)
E IPU_MeisheFilterSdk: initMSContext failed
E IPU_MeisheFilterSdk: java.lang.UnsatisfiedLinkError ...
    at MsEffectSdkContextUtils.init(MsEffectSdkContextUtils.java:43)
    at MeisheRender.initMsRenderUtil(MeisheRender.java:308)
```

滤镜 SDK 上下文从未初始化 → 选滤镜即空帧（不崩、HAL 出帧、成片正常）。
修法：bind mount /my_product 版本到 /product/lib64 + post-fs-data.d 与 service.d 双层自愈。
注意：OPCameraPro 模块自带的同款 so 也是坏的（370264B / 符号 0）。

## v5.9 / v6.7 影调轴失效：三层问题

1. **资源路径**：Lut3dDataManager 写死 /odm/etc/camera/basictone/{lmt,odt,setting}，但相机进程访问 /odm 时目录不可列 → LMT/ODT 从未加载。修法：LMT(41)+ODT(2) 打进 APK assets，init hook 改路径到 app 私有目录。
2. **读取侧归零**（v5.9）：ag.u.f / ag.x.f 在「风格效果=off」时返回 0 → 饱和度轴被钉死。
3. **写入侧归零**（v6.7）：ag.u.d(tone,flag,str) 在 off 时把 tone 改 0 再持久化 → 影调轴数值变但画面不变。

```
风格效果=off, ag.u.d(-17) -> 传 0
```

修复后：

```
ag.u.d(57, true, common_fuji_cc.bin_rear) 放行（v6.7 不归零）
```

## ini 开关

影调需要 LMT/ODT 通道：[switch] LMT=1 / SC=1 / ODT=1（SC=饱和度，一直开）。

## 不要碰的两条（会破坏预览）

- debug.palette.forceupload=1：CLH 算完 LUT 后硬推 gl.s.v → 覆盖预览 LUT 槽位
- debug.palette.forcet0=1：p() 里强置 k/i=true → 饱和度+影调双死
