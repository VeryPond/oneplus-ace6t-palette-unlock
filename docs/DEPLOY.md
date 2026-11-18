# 安装与验证

## 前置

- 已解锁 BL 并 root（本项目在 KernelSU 41150 + Zygisk Next 1.5.2 + LSPosed 2.2.1 上验证）
- LSPosed 正常工作，`system_server` 已被注入

检查 LSPosed 是否健康：

```bash
adb shell su -c 'grep "sent service to bridge" /data/adb/lspd/log/verbose_*.log | tail -1'
```

有这行才说明 bridge 通。

## 1. 装 LSPosed 模块

```bash
adb install -r release/PaletteUnlock_v1.0.apk
```

在 LSPosed 管理器里启用模块，作用域勾选 `com.oplus.camera`。

## 2. 装 Magisk 资源模块

```bash
adb push release/palette_unlock_magisk_v1.0.zip /data/local/tmp/pal.zip
adb shell su -c 'ksud module install /data/local/tmp/pal.zip'
```

## 3. 重启

```bash
adb reboot
```

**必须硬重启**（见 `docs/PITFALLS.md` #5）。

## 4. 验证资源已挂载

```bash
adb shell su -c 'ls /odm/etc/camera/basictone/palette/HDR/'
```

应该看到 9 个文件，每个 49152 字节：

```
SC16Lut0 ... SC16Lut8
```

## 5. 验证模块已加载

启动相机，然后：

```bash
adb shell su -c 'cat /sdcard/Android/data/com.oplus.camera/files/palette_unlock.log'
```

**必须看到这些行**（数字要对）：

```
onPackageLoaded pkg=com.oplus.camera
CameraConfigHook installed, methods=6
hooked com.oplus.camera.module.BaseMode.getSupportFunction(String)
SupportFunctionHook done, installed=1
hooked PaletteSeekbarColors.get(String) -> stub recipe
PaletteSeekbarFixHook installed=2
hooked PaletteColors.get(String) -> stub recipe
PaletteColorsFix installed=2
all hooks installed
CameraConfig.d(com.oplus.feature.color.palette.support) -> true
```

如果 `installed` 数字不对，或看到 `skipped`，说明 hook 失败，不要继续往下试。

## 6. 使用

相机 → 右侧竖排第二个按钮（虚线方框）→ 调色盘界面。

应能看到：影调、饱和度、柔光三组控件。

## 排错

| 现象 | 原因 | 处理 |
|---|---|---|
| 模块日志文件不存在 | 模块没加载 | 检查 LSPosed 作用域、是否启用 |
| `installed=0` | 类名或方法签名不匹配 | 看日志里的 ClassNotFound / NoSuchMethod |
| 点击调色盘闪退 | 桩没装上 | 查 `PaletteColorsFix skipped` |
| 调色盘能进但无效果 | LUT 未挂载 | 查 `/odm/.../palette/HDR` 是否有 9 个文件 |
| 相机整体异常 | `/odm` 资源冲突 | 卸载 Magisk 模块重启 |
