# 一加 6T 调色盘一体化模块

> 由 **DeepSeek Harness** 生成

## 一句话

刷入这一个 zip，重启，在 LSPosed 里给 `调色盘强开` 勾上 `com.oplus.camera` 作用域即可。

## 它做了什么

1. **安装 LSPosed 模块** `PaletteUnlock.apk`（自动，无需手动装）
2. **铺入 18 个官方 3D LUT + 4 个 ini** 到 `/odm/etc/camera/basictone/`
3. 尝试**自动注册 LSPosed 作用域**（设备有 sqlite3 时）

## 安装

```bash
adb push palette_unlock_v1.1.zip /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/palette_unlock_v1.1.zip'
adb reboot
```

重启后：

1. 打开 LSPosed 管理器
2. 找到「调色盘强开」
3. 作用域勾选 `com.oplus.camera`
4. 强制停止相机再打开

## 验证

```bash
# 资源是否铺好
adb shell su -c 'ls /odm/etc/camera/basictone/palette/HDR/'   # 应有 9 个文件

# 模块是否生效
adb shell su -c 'cat /sdcard/Android/data/com.oplus.camera/files/palette_unlock.log'
```

日志里应看到：

```
PaletteSeekbarFixHook installed=2
PaletteColorsFix installed=2
CameraConfig.d(com.oplus.feature.color.palette.support) -> true
```

## 卸载

```bash
adb shell su -c 'rm -rf /data/adb/modules/palette_unlock'
adb uninstall com.palette.unlock
adb reboot
```

## 注意

- 需要已 root + LSPosed 正常工作
- **必须硬重启**，软重启无效（Zygisk 注入时机问题）
- 若自动注册作用域失败，手动在 LSPosed 里勾选即可
