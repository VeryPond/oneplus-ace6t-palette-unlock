# 一加 Ace 6T 调色盘（Palette）逆向分析报告

> 目标机型：OnePlus Ace 6T / PLR110 / SM8845 / ColorOS V17.0.0 / build PLR110_17.0.0.101(CN01)
> 相机版本：`com.oplus.camera` versionName 7.006.123，versionCode 60000
> 分析日期：2026-11

---

## 1. 核心结论（先看这里）

**6T 的调色盘不是"功能被砍"，而是"资源缺失 + 路径未注入"。**

| 环节 | 状态 |
|---|---|
| 相机 APK 代码 | ✅ 完整（与 OnePlus 15 的 APK **SHA256 完全相同**） |
| `/odm` 下的 palette LUT | ❌ **6T 缺失 18 个 LUT + 4 个 ini** |
| 运行时路径参数 | ❌ **无人改写，相机永远去读 `/odm` 原路径** |
| native 侧双闸门 | ⚠️ `palette flag` 与 `HDR flag` 由 `ProcessPostParam.reserved[]` 提供 |

**前 23 个版本失败的原因**：把 LUT 铺进了只读的 `/odm`，但 **`CombineLutProcessUnitSDK.init` 收到的路径参数从来没变过**，相机根本不会去读那些文件。

---

## 2. 关键证据链

### 2.1 6T 与 OnePlus 15 的相机 APK 完全相同

```
SHA256: d048384ed3dbef0abdf6236be0fa4478640db33ede0e2fe83ad4504b1f9a7228
大小:   177,503,773 字节
```

34 个 dex 逐一比对，**0 个差异**。`classes13.dex` 同为 2,926,040 字节。

**含义**：不存在"6T 缺少 palette 代码"这种情况。差异只能在资源或运行时参数。

### 2.2 `/odm/etc/camera/basictone/` 结构对比

| 子目录 | 6T | OnePlus 15 |
|---|---|---|
| `lmt/` | 52 项 | 51 项 ✅ |
| `odt/` | 5 项 | ✅ |
| `vig/` | 5 项 | ✅ |
| `setting/` | 14 项 | 17 项 |
| `setting_dolby/` | 3 项 | ✅ |
| **`palette/`** | **❌ 不存在** | **✅ 18 文件** |

**6T 缺少的完整清单**：

```
palette/HDR/SC16Lut0..8         9 个，各 49152 字节
palette/SDR/SC16Lut0..8         9 个，各 49152 字节
setting/SimTool_Palette_Photo_Normal.ini
setting/SimTool_Palette_Photo_Portrait.ini
setting/SimTool_Palette_Photo_Night.ini
setting/SimTool_Preview_Palette_Normal.ini
```

### 2.3 LUT 数据格式

```
49152 字节 = 12288 × float32(LE) = 4096 × RGB 三元组
4096 = 16³
16³ × 3 × 4B = 49152 ✅
```

即 **16×16×16 的 3D RGB LUT**，与 ini 里 `[SCLut] lut_size=16 / SCLutNum=9` 严格对应。

### 2.4 路径参数是被谁传进去的

`PaletteProcessUnit.ensureLutInited()` 的 Dalvik 字节码：

```
[ 2]  const/4 #1
[ 6]  const-string '/odm/etc/camera/basictone/lmt'
[ 7]  const-string '/odm/etc/camera/basictone/odt'
[ 8]  const-string '/odm/etc/camera/basictone/setting'
[ 9]  const/4 #1
[10]  const/4 #2
[11]  const-string '/odm/etc/camera/basictone/palette'
[12]  invoke -> CombineLutProcessUnitSDK.init
```

**槽位映射**：

| 槽位 | 含义 |
|---|---|
| `[0]` | lmt |
| `[1]` | odt |
| `[2]` | setting |
| `[3]` | 1 |
| `[4]` | 2 |
| `[5]` | **palette** |

### 2.5 native 侧的双闸门

`libCombineLut.so` 中 `Param_BasicTone_CPU::BasicTonePaletteProcessNext` 的 ARM64 反汇编：

```asm
; 第一关
add  x0, x21, #0x98           ; ProcessPostParam + 0x98
add  x1, x1, #0x250           ; 键名 "palette flag"
bl   #0x2a1a0                 ; ParseReservedFlag（std::string -> int）
str  w0, [x19, #0xe90]        ; m_iPaletteFlag
cbz  w0, #0x2a07c             ; ← 为 0 就直接返回，不做任何调色

; 第二关
add  x0, x21, #0xb0
add  x1, x1, #0xc5c           ; 键名 "HDR flag"
bl   #0x2a1a0
str  w0, [x19, #0xe94]        ; m_iHdrFlag
```

`SCLutMallocPalette` 用 `m_iHdrFlag` 决定读哪个子目录：

```asm
ldr  w8, [x19, #0xe94]
add  x9, x9, #0x9a8           ; "HDR"
add  x10, x10, #0x2ba         ; "SDR"
cmp  w8, #0
csel x8, x10, x9, eq          ; HdrFlag==0 → "SDR"，否则 "HDR"
```

**这两个 flag 不在任何 ini 文件里**（搜遍 16 个 setting ini 无匹配），由 IPU engine 内部维护。

### 2.6 `processLut` 是真正的调色入口

```java
float[] processLut(int, int, float, float, float, boolean isHdr) {
    if (!ensureLutInited()) {
        IPULog.e("PaletteProcessUnitxxxx", "[palette] processLut, init failed");
        return;                                    // ← 直接退出
    }
    CombineLutProcessUnitSDK.process(...);
    IPULog.i("[palette] processLut X, tone: ..., isHdr: ..., lut: ...");
}
```

**调试时只需 grep 这两条日志。**

---

## 3. 滑块为什么无效

### 3.1 `setTone` / `setSaturation` 全 APK 零调用

`PaletteProcessParameter$Builder` 提供了 27 个 setter，其中包括：

```java
setTone(int)
setSaturation(int)
```

扫描全部 class_data 的 `invoke-*` 指令，匹配 method_ids 4915(`setSaturation`) 与 4923(`setTone`)：

```
!!! 全 APK 没有任何地方调用 setTone / setSaturation
```

穷举 `iput` 系列指令（`0x59-0x5f`、`0xeb`、`0xf8-0xff`）后，`mTone`/`mSaturation` 的写入点只有 4 处：

| 位置 | 性质 |
|---|---|
| `PaletteProcessParameter.<init>` | 构造器初始化 |
| `PaletteProcessParameter$Builder.<init>` | 构造器初始化 |
| `Builder.setSaturation` | **零调用** |
| `Builder.setTone` | **零调用** |

### 3.2 滑块只画 UI

`PalettePanelView` 调用的全部类：`Canvas / Paint / Path / Animator / Color / RectF / LinearGradient / BlurMaskFilter ...`

**没有 `PaletteProcessUnit`。**

滑动链路：
```
onToneSaturationChanged
  → dispatchToneSaturationFromPosition
  → 更新界面色带
  → ✗ 到此为止
```

### 3.3 结论

**滑块改变的是界面颜色，不改变图像处理的任何一个像素。**

---

## 4. 为什么滤镜有效而调色盘无效

| | 滤镜 | 调色盘 |
|---|---|---|
| 数据通路 | Java 直接调 `AncFilterSdk.setFilterIntensity` | native `ProcessPostParam.reserved[]` 里的 flag |
| LSPosed 能改吗 | ✅ 能 | ❌ 不能（native 内部维护） |

---

## 5. 参考方案：X8CamUnlock

在 `Find X8` 上验证可用的第三方模块，方案与本项目的关键差异：

| # | X8CamUnlock | 本项目早期（v2.9 及之前） |
|---|---|---|
| 1 | 资源放**相机私有目录** `/data/data/com.oplus.camera/files/` | 放只读 `/odm` |
| 2 | **hook `init` 改写路径参数** | ❌ 没做 |
| 3 | **扫描 `ODT={0,1}`** 找可用组合 | ❌ 没做 |

### 参考实现的 hook 签名

```java
// 目标
CombineLutProcessUnitSDK.init(String, String, String, int, int, String) -> int

// 筛选条件
parameterTypes.length == 6
  && p[0] == String.class && p[1] == String.class && p[2] == String.class
  && p[3] == Integer.TYPE && p[4] == Integer.TYPE && p[5] == String.class
  && method.getReturnType() == Integer.TYPE

// 改写
args[1] = odtPath;
args[2] = settingPath;
args[5] = palettePath;
```

---

## 6. v3.x 的实现

### 6.1 第十层：路径重定向

```
/data/data/com.oplus.camera/files/palette_unlock/basictone/
├── palette/HDR/SC16Lut0..8   ← 从模块 APK assets 解出
├── palette/SDR/SC16Lut0..8
├── setting/*.ini
└── odt/                       ← 从 /odm 复制
```

hook 两个入口（**双保险**）：

```
CombineLutProcessUnitSDK.init   ← palette 主路径，动态加载
CombineLutHelper.init           ← 老路径，APK 内定义
```

**调用点图谱**：

```
method_id 5204 (CombineLutProcessUnitSDK.init)
├─ PaletteProcessUnit.ensureLutInited   ← 硬编码路径，必被拦截
└─ i$a.onClick                          ← 运行时值，可能漏过（非关键路径）

method_id 16857-16859 (CombineLutHelper.init)
└─ Lut3dDataManager.e (×5)
```

### 6.2 第十一层：B3 能力位全覆盖

`key_support_palette` 在 6T 上分布在 **9 个类**的 `B3(String)` 里：

```
ag.r
com.oplus.camera.feature.filter.view.f0
com.oplus.camera.module.BaseMode          ← 模式基类，必经
com.oplus.camera.ui.CameraUIInterface
hl.k0
ip.c1
ta.p
zg.a
zn.a
```

X8CamUnlock 只挂 `ag.v` 一个（Find X8 的混淆名），6T 上会漏掉 8 个。

### 6.3 延迟加载重试

`CombineLutProcessUnitSDK` 由动态 ClassLoader 提供，`onPackageLoaded` 时可能还不存在。

```java
// 在这两处触发补挂
PaletteProcessUnitHook.ensureLutInited  → PathInjectHook.retryInstallIfNeeded()
IPUFeatureHook.createFeature            → PathInjectHook.retryInstallIfNeeded()
```

---

## 7. 如何验证

### 7.1 刷入后自检

```bash
adb shell su -c 'sh /data/adb/modules/palette_unlock/action.sh'
```

五段输出，**C 段最关键**：

```
【C. 资源部署（第十层 PathInject）】
  相机私有目录: 已创建
  palette/HDR: 9 个  palette/SDR: 9 个
  setting 下 palette ini: 4 个
  SC16Lut8 尺寸: 49152 (期望 49152)
```

### 7.2 关键日志

```bash
adb logcat -s PaletteUnlock
```

三种可能：

| 日志 | 含义 |
|---|---|
| `ensureLutInited() = false` | 卡在 LUT 初始化 |
| `processLut ... -> float[0]` | LUT 通过但 native 没产出数据 |
| `processLut ... -> float[393216]` | 正常，调色在跑 |

---

## 8. 已知限制

1. **`palette flag` 与 `HDR flag` 不可达** —— 由 IPU engine 在 native 侧维护，
   LSPosed 无法直接修改。若真机上 `ensureLutInited` 成功但 `processLut` 返回空，
   说明卡在这里。
2. **滑块无效无法通过 LSPosed 修复** —— `setTone`/`setSaturation` 零调用是
   APK 自身设计，即使强行注入值，`processLut` 也是从自己的 6 个形参读，
   不从 `PaletteProcessParameter` 读。
3. **`i$a.onClick` 路径可能漏拦截** —— 它传运行时配置值而非硬编码路径，
   v3.3 的 `contains("palette")` 过滤可能不命中。但那是 UI 点击路径，
   不是调色主流程。
4. **B3 混淆类名随 ROM 版本变化** —— 第 11 层的 9 个类名只对
   `PLR110 7.006.123` 有效，换版本需重扫。

---

## 9. 方法论总结

### 有效的做法

1. **用 `method_ids -> proto_ids` 拿真实方法签名**，不要猜
2. **扫 `invoke-*` 指令确认方法真被调用**，不要只看定义存在
3. **扫 `iput` 指令穷举字段写入路径**，不要只看 setter
4. **字节码顺序 + 槽位映射交叉验证**
5. **真机 libCombineLut.so 的 ARM64 反汇编**（注意不是 x86）
6. **本地桩环境实跑 shell 脚本**

### 踩过的坑

| 坑 | 后果 |
|---|---|
| 用 x86 反汇编 ARM64 .so | 得到乱码，误判"VA≠offset" |
| 只核对文件大小不看 ELF 魔数 | 用无效数据做了 5 轮分析 |
| 只读注释不读实现 | 5 个 hook 层静默失效 |
| 只看"类存在"不看"被调用" | `CombineLutHelper` 白追了 3 轮 |
| 签名脚本用残留旧文件 | 产出一个 dex=28612 的假包 |
| `exit` 之后的代码 | 自动注册功能从未执行 |

### 最重要的一条

**当分析陷入僵局时，回头看看之前写过的报告。**

第 24 轮发现 `work/x8/report_palette.md`（19,233 字符），
那是自己早前分析 X8CamUnlock 时写的完整逆向报告——
**正确答案一直在本地，只是忘了**。

---

*本报告由 DeepSeek Harness 生成。*
