# 回滚

## 最快：只停模块

```bash
adb uninstall com.palette.unlock
```

相机立刻恢复原样，**不需要重启**（LSPosed 下次启动相机时就不加载了）。

## 完整：连资源一起清

```bash
# 1. 卸 LSPosed 模块
adb uninstall com.palette.unlock

# 2. 卸 Magisk 资源模块
adb shell su -c 'rm -rf /data/adb/modules/palette_unlock'
adb shell su -c 'rm -f /data/adb/palette_unlock.log'

# 3. 重启
adb reboot
```

## 验证已恢复

```bash
# 资源目录应恢复成原来那样（没有 palette 子目录）
adb shell su -c 'ls /odm/etc/camera/basictone/'

# 相机应正常工作
adb shell am start -n 'com.oplus.camera/.Camera'
```

## 本项目没有改动的东西

- 相机 APK（未改、未重签）
- `/odm` 等只读分区（Magisk 是 overlay 挂载，卸载即消失）
- 系统任何其他分区

**唯一写入设备的是**：

1. `com.palette.unlock` 这个 APK
2. LSPosed 的 `modules_config.db` 里两条记录（modules_state / scope）
3. Magisk 模块目录 `/data/adb/modules/palette_unlock`

## 如果 LSPosed 状态异常

本项目开发过程中反复出现在同一处：手动重启 lspd 导致 bridge 断，
`system server is not ready`，**所有模块不生效**。

这不是本模块的问题，恢复方法只有一个：

```bash
adb reboot
```
