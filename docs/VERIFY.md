# 真机验证手册

> 这份手册的目的是：**让任何人在没有我参与的情况下，也能完整验证 v3.6 是否生效，并把结果准确回报。**
>
> 全程约 10 分钟。

---

## 0. 前置检查

确认设备和环境：

```bash
adb devices              # 应看到设备序列号 + device
adb shell getprop ro.product.name   # PLR110
adb shell getprop ro.build.version.release
adb shell su -c 'id'     # 应有 root
```

**任何一项不符合，先解决再继续。**

---

## 1. 安装

```bash
adb push palette_unlock_v3.6_allinone.zip /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/palette_unlock_v3.6_allinone.zip'
```

预期输出包含 `LSPosed 模块已安装` 或 `已更新（保留原有作用域配置）`。

### 确认模块已装

```bash
adb shell su -c 'ls -la /data/adb/modules/palette_unlock/'
adb shell su -c 'pm path com.palette.unlock'
```

---

## 2. 重启

```bash
adb reboot
```

**必须真重启**，不能只重启相机——LSPosed 注入在进程启动时生效。

等设备起来（约 60 秒）。

---

## 3. 跑自检（关键步骤）

```bash
adb shell su -c 'sh /data/adb/modules/palette_unlock/action.sh'
```

### 预期输出（五段）

```
【A. 资源层】
  LUT 就位: 18/18   尺寸异常: 0
  SHA 匹配官方: 18/18   不匹配: 0
  palette ini: 4/4

【B. 模块层】
  LSPosed 模块已安装: /data/app/.../base.apk
  启用状态: 1   作用域含 com.oplus.camera: 1

【C. 资源部署（第十层 PathInject）】
  相机私有目录: /data/data/com.oplus.camera/files/palette_unlock/basictone
  palette/HDR: 9 个 LUT (期望 9)
  palette/SDR: 9 个 LUT (期望 9)
  setting 下 palette ini: 4 个 (期望 4)
  SC16Lut8 尺寸: 49152 OK

【D. 运行层 hook 日志】
  -- 各层 installed 计数 --

【E. 资源部署日志】
```

### 怎么读

| 段 | 若异常 | 原因 |
|---|---|---|
| **A** | LUT 就位 < 18 | 模块包不完整，重刷 |
| **B** | 未启用 / 作用域缺 | 去 LSPosed 管理器手动勾选 |
| **C** | 目录不存在 | **第十层没挂上**（最关键） |
| **C** | HDR < 9 个 | 部署失败，看 E 段日志 |
| **D** | 某层 installed=0 | 那层 hook 没挂上 |

**C 段是判断 v3.6 是否起作用的直接证据。**

---

## 4. 抓日志

自检通过后，打开相机并操作调色盘：

```bash
# 终端 1：持续抓日志
adb logcat -s PaletteUnlock | tee /tmp/palette.log
```

**在相机里做这几件事**（每步间隔 2-3 秒）：

1. 打开相机（默认拍照模式）
2. 找到调色盘入口（可能在"更多"或取景框边栏）
3. 点开调色盘，滑动底部色带
4. 拍一张照片
5. 退出相机

然后 Ctrl+C 停止抓日志。

---

## 5. 关键日志对照

在 `/tmp/palette.log` 里搜这几个关键词：

### 5.1 部署是否成功

```bash
grep "PathInject" /tmp/palette.log
```

**正常：**
```
PathInject: 当前进程 = com.oplus.camera  filesDir = /data/data/com.oplus.camera/files
PathInject 部署成功: assets=26 系统setting=17 系统odt=4 lut8=49152 settingPath=...
```

**异常：**
```
PathInject: currentApplication() = null
PathInject: 打不开模块 APK
PathInject 部署失败: ... lut8=-1
```

### 5.2 init 是否被拦截

```bash
grep "palette init" /tmp/palette.log
```

**正常：**
```
palette init 原始参数: [0]=lmt [1]=odt [2]=setting [3]=1 [4]=2 [5]=palette
palette init 探测 odt=0 code=0
palette init 成功！odt=0  setting=/data/data/com.oplus.camera/files/palette_unlock/basictone/setting
```

**异常：**
```
palette init 探测 odt=0 code=-999
palette init 探测 odt=1 code=-999
palette init 全部 ODT 尝试失败 bestCode=...
```

### 5.3 调色是否真的在跑

```bash
grep -E "ensureLutInited|processLut" /tmp/palette.log
```

**三种结果：**

| 日志 | 含义 | 结论 |
|---|---|---|
| `ensureLutInited() = false` | 卡在 LUT 初始化 | 第十层部署有问题 |
| `processLut ... -> float[0]` | LUT 过了但 native 没产出 | 卡在 native 的 palette flag |
| `processLut ... -> float[393216]` | 正常 | **调色应该在跑** |

---

## 6. 视觉验证（最重要）

日志只是辅助，**最终看照片**。

### 方法：拍三张对比

1. **A 图**：调色盘滑块在最中间（默认）
2. **B 图**：滑块拉到某一端
3. **C 图**：关掉调色盘（用普通拍照）

用同一场景、同一光线、同一构图。

### 判定

| 现象 | 结论 |
|---|---|
| A ≠ B，且差异明显 | ✅ **调色盘生效** |
| A ≈ B（几乎一样） | ❌ 调色没生效 |
| A = B = C | ❌ palette 完全没参与 |

### 量化（可选）

如果有 Python 环境：

```python
from PIL import Image
import numpy as np

a = np.array(Image.open('A.jpg').convert('RGB')).astype(float)
b = np.array(Image.open('B.jpg').convert('RGB')).astype(float)

diff = np.abs(a - b)
print(f'平均差异: {diff.mean():.2f}')
print(f'变化像素占比: {(diff.max(axis=2) > 5).mean() * 100:.1f}%')
```

**参考值：**
- 生效：变化像素 > 30%
- 未生效：变化像素 < 5%

---

## 7. 回报清单

把下面这些发给我（**不要只发"没效果"**）：

### 必需

```
1. 自检五段完整输出
2. grep "PathInject" 的输出
3. grep "palette init" 的输出
4. grep -E "ensureLutInited|processLut" 的输出
5. 三张对比照片（A/B/C）
```

### 可选但有用

```
6. adb shell su -c 'dmesg | tail -50'
7. 相机版本：adb shell dumpsys package com.oplus.camera | grep versionName
8. ROM 版本：adb shell getprop ro.build.display.id
```

### 一键收集脚本

```bash
#!/system/bin/sh
OUT=/sdcard/palette_report_$(date +%Y%m%d_%H%M%S)
mkdir -p "$OUT"

sh /data/adb/modules/palette_unlock/action.sh > "$OUT/selfcheck.txt" 2>&1
logcat -d -s PaletteUnlock > "$OUT/palette.log" 2>&1
dumpsys package com.oplus.camera > "$OUT/camera_pkg.txt" 2>&1
getprop > "$OUT/getprop.txt" 2>&1
ls -laR /data/data/com.oplus.camera/files/palette_unlock/ > "$OUT/deploy_tree.txt" 2>&1

echo "报告已生成: $OUT"
```

把它保存为 `collect.sh`，推送到设备执行：

```bash
adb push collect.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/collect.sh'
adb pull /sdcard/palette_report_xxxx ./
```

---

## 8. 故障排查决策树

```
自检 C 段目录不存在？
├─ 是 → 第十层没挂上
│        ├─ 检查 LSPosed 作用域是否含 com.oplus.camera
│        ├─ 检查相机是否真的启动过（重启后需手动打开一次）
│        └─ 看 logcat 有无 "PathInjectHook skipped"
└─ 否 → 目录存在
         ├─ LUT 数量不足 → 看 E 段部署日志
         └─ LUT 齐全
              ├─ grep 到 "palette init 成功" → 部署 OK，看 processLut
              └─ grep 到 "palette init 全部 ODT 尝试失败" → ODT 扫描失败
                   └─ 试改 PathInjectHook 的 ODT_CANDIDATES 为 {0,1,2}
```

---

## 9. 如果要回滚

```bash
adb shell su -c 'touch /data/adb/modules/palette_unlock/remove'
adb reboot
```

重启后模块与 APK 都会被移除（`uninstall.sh` 会 `pm uninstall`）。

---

## 10. 已知限制（验证前先读）

即使 v3.6 生效，以下四项也**不会**改善：

| # | 限制 | 原因 |
|---|---|---|
| 1 | 滑块色带颜色固定 | `PaletteColorTable` 类在 APK 内不存在（扫 51 个 APK 确认） |
| 2 | 滑块数值可能不影响照片 | `setTone`/`setSaturation` 全 APK 零调用 |
| 3 | 部分 init 调用点可能漏拦截 | `i$a.onClick` 传运行时值而非硬编码路径 |
| 4 | native 的 palette flag 不可控 | 由 IPU engine 内部维护 |

**所以「滑块滑动 + 照片颜色变化」这个完整链条，可能只有前半部分被修复。**

如果实测发现「照片颜色有变化但滑块无效」，那是**符合预期的结果**，不是失败。

---

*本手册由 DeepSeek Harness 生成。*
