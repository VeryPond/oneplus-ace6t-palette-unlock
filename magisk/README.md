# palette_unlock / 调色盘强开 · 资源注入模块

Magisk / KernelSU 模块。把 OPPO / 一加（ColorOS 17）相机调色盘（Palette）所需的
`basictone` 资源铺到 `/odm/etc/camera/basictone/`。

> **定位：资源兜底 + 可替换。**
> 本模块 **不负责** 打开相机里的功能开关。开关由另一个 LSPosed 模块控制
> （`key_support_palette` / `com.oplus.feature.color.palette.support`）。
> 本模块只保证：**一旦开关打开，磁盘上的 LUT 与 SimTool 配置一定齐全且可被替换。**

---

## 1. 模块信息

| 项 | 值 |
| --- | --- |
| id | `palette_unlock` |
| 版本 | v1.0 (versionCode 1) |
| 目标路径 | `/odm/etc/camera/basictone/` |
| 资源数量 | 22 个（18 个 3D LUT + 4 个 SimTool ini） |
| 资源来源 | GT8 Pro（gt8rom, ColorOS 17, my_product.type=24831）odm.img 的 EROFS 解包 |

### 资源清单

```
system/odm/etc/camera/basictone/
├── palette/
│   ├── HDR/  SC16Lut0 … SC16Lut8     (9 个, 每个 49152 字节)
│   └── SDR/  SC16Lut0 … SC16Lut8     (9 个, 每个 49152 字节)
└── setting/
    ├── SimTool_Palette_Photo_Normal.ini     (613 B)
    ├── SimTool_Palette_Photo_Portrait.ini   (613 B)
    ├── SimTool_Palette_Photo_Night.ini      (613 B)
    └── SimTool_Preview_Palette_Normal.ini   (121 B)
```

`SC16Lut*` 是 16³ 网格的 3D LUT，布局 `R,G,B` 各 16 档 × 3 通道 × 2 字节 = 49152 字节。
ini 中对应 `[SCLut] lut_size=16, SCLutNum=9`，与 9 个 LUT 文件一一对应。

---

## 2. 安装

1. 手机已 root（Magisk 24+ / KernelSU），且已关闭系统「验证启动」相关完整校验。
2. 把 `palette_unlock_magisk_v1.0.zip` 拷到手机。
3. Magisk App → 模块 → 从本地安装 → 选该 zip。
4. **重启**。

安装期 `customize.sh` 会逐文件校验：18 个 LUT 必须都等于 49152 字节，
否则直接 abort，不会留下半截资源。

---

## 3. 部署策略（post-fs-data.sh）

每次开机执行，逻辑是**幂等 + 保守**：

| 目标文件状态 | 动作 | 日志标记 |
| --- | --- | --- |
| 不存在 | 写入（先 `.pl_tmp` 再 `mv`，不留半截文件） | `WRITE` |
| 存在且 sha256 相同 | 跳过，不写盘 | `SAME` |
| 存在但内容不同（ROM 自带版本） | **默认保留原文件**，只记日志 | `KEEP` |
| 目标分区不可写 | 自动回退到 Magisk magic mount | `WARN` |

写入前一定先校验 `SC16Lut8 == 49152` 字节（HDR 与 SDR 各一次），不通过就整轮中止。

### 强制覆盖 ROM 自带资源

默认 `KEEP` 是为了安全：ROM 自带的调色盘资源可能与本模块版本不同，
贸然覆盖有变砖式黑图/偏色风险。确认要覆盖时：

```bash
adb shell su -c "touch /data/adb/palette_unlock.force"
adb reboot
```

取消：

```bash
adb shell su -c "rm -f /data/adb/palette_unlock.force"
```

---

## 4. 验证

### 4.1 分区是否真的挂上了

```bash
adb shell su -c "ls -l /odm/etc/camera/basictone/palette/HDR"
adb shell su -c "ls -l /odm/etc/camera/basictone/palette/SDR"
adb shell su -c "ls -l /odm/etc/camera/basictone/setting/SimTool_Palette_*.ini"
```

期望：HDR / SDR 各 9 个 `SC16Lut0..8`，每个 49152 字节；setting 下 4 个 ini。

### 4.2 尺寸抽查

```bash
adb shell su -c "wc -c /odm/etc/camera/basictone/palette/HDR/SC16Lut8 /odm/etc/camera/basictone/palette/SDR/SC16Lut8"
```

期望两行都是 `49152`。

### 4.3 sha256 抽查（与 rom_resources.json 对照）

```bash
adb shell su -c "sha256sum /odm/etc/camera/basictone/palette/HDR/SC16Lut0 /odm/etc/camera/basictone/palette/SDR/SC16Lut8 /odm/etc/camera/basictone/setting/SimTool_Palette_Photo_Normal.ini"
```

期望：

```
694f087ef1d1fa32723f84a932cd8e32ad36800f96a7cd25ad6365256e52da78  .../HDR/SC16Lut0
97476050e86132920ec09824dd0d21e4096500116e397456e073b82a95599b98  .../SDR/SC16Lut8
b3debbc9d846312dcc8dadabe9d27f6adcbfc36e14d818dd58e880e7ffb036ec  .../SimTool_Palette_Photo_Normal.ini
```

### 4.4 看模块日志

```bash
adb shell su -c "tail -n 60 /data/adb/palette_unlock.log"
```

关注 `SUMMARY` 行：`就位=… 保留原文件=… 回退overlay=… 失败=…`。

### 4.5 Magisk 管理器内直接看

模块页 → 该模块 → 点「操作」按钮，运行 `action.sh`，输出当前 LUT 就位数量。

### 4.6 相机侧

资源到位 **不等于** UI 出现调色盘入口。判定开关是否生效：

```bash
adb shell su -c "logcat -d | grep -iE 'Lut3dDataManager|palette|basictone' | tail -n 50"
```

关键行：

- `initializeBasicToneEnv, init result 0, mPaletteFlag: true` → 资源被成功加载
- `initializeBasicToneEnv, init failed!!!` → 资源缺失或被拒
- `palette branch skip process, ... paletteFlag: false` → 开关没打开（问题在 LSPosed 侧，不在本模块）

---

## 5. 回滚 / 卸载

1. Magisk App → 模块 → `调色盘强开资源` → 卸载。
2. **重启**（必须，magic mount 只在重启时撤销）。

卸载脚本只删 `/data/adb/palette_unlock.log`、`.force` 等状态文件，
不动系统任何原始文件。因为资源是挂载上去的，重启后 `/odm` 恢复原样。

紧急回滚（无法进系统时）：Magisk 安全模式开机（长按音量键），
或 `adb shell su -c "touch /data/adb/modules/palette_unlock/disable"` 后重启。

---

## 6. 已知风险点

1. **本模块不打开开关。** 只装本模块，相机 UI 不会有调色盘入口。必须配合 LSPosed 侧模块。
2. **ROM 自带资源优先保留。** 若你的 ROM 已经有 `basictone/palette` 且内容不同，
   默认 `KEEP` 不覆盖（见 §3）。要换成模块资源必须显式开 `.force`。
3. **目标机型上限。** 资源取自 GT8 Pro / ColorOS 17。ColorOS 15/16 的相机若没有
   `basictone/palette` 框架，铺了文件也不会被读取（日志会显示 `init failed` 或直接跳过）。
4. **LUT 尺寸固定。** 若后续 ColorOS 把 `lut_size` 改成 32 或 33，49152 字节的 LUT
   会被相机拒绝。届时需要重新从对应 ROM 提取，不能复用本包。
5. **HDR / SDR 的 Lut5..8 内容相同。** 源 ROM 里 `SC16Lut5..8` 四个文件 sha256 完全一致
   （`9747605…`），这是官方原样，不是打包错误。若调色盘后段色阶看起来"没有区别"，
   根因在 ROM 资源本身，本模块无法改变。
6. **SimTool 三个 Palette ini 内容完全相同**（Normal / Portrait / Night 同一 sha256），
   同样是官方原样。
7. **/odm 通常是只读分区。** 本模块实测路径上以 magic mount 为主；
   `post-fs-data.sh` 的实体写入是兜底分支，失败会打印 `WARN` 并继续，不影响开机。
8. **不要与其它同样覆盖 `/odm/etc/camera` 的模块同时启用**，mount 顺序不可控。
9. **挂载路径假设需真机复核。** 模块按 Magisk 惯例把资源放在 `system/odm/...`，
   由 magic mount 映射到 `/odm/...`。若真机上 `ls /odm/etc/camera/basictone/palette/HDR`
   看不到 9 个 LUT，先确认该 ROM 的 `/odm` 是否是独立分区（而非 `/system/odm` 符号链接），
   两者在部分 ColorOS 版本上行为不同。`post-fs-data.sh` 会先尝试真实写入 `/odm`，
   写不进去才依赖 magic mount，所以两种布局都会尽力覆盖。
10. **本模块不校验 ROM 侧框架。** 若日志出现 `initializeBasicToneEnv, init failed`，
    通常是 ROM 缺少 basictone/palette 框架或 LUT 尺寸不匹配，而非文件没铺进去——
    先用 §4.2 / §4.3 确认文件层面是否 OK。

---

## 7. 文件结构

```
palette_unlock_magisk_v1.0.zip
├── module.prop
├── customize.sh          # 安装期校验 + 权限
├── post-fs-data.sh       # 开机幂等部署
├── service.sh            # boot 完成后复核并记日志
├── uninstall.sh          # 卸载清理状态文件
├── action.sh             # 管理器"操作"按钮
├── README.md
└── system/odm/etc/camera/basictone/
    ├── palette/HDR/SC16Lut0..8
    ├── palette/SDR/SC16Lut0..8
    └── setting/SimTool_Palette_Photo_{Normal,Portrait,Night}.ini
        setting/SimTool_Preview_Palette_Normal.ini
```
