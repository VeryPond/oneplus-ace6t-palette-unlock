# LUT 资源

## 格式

`SC16LutN` = 49,152 字节 = 16³ × 3 × float32 LE = 4096 个 RGB 三元组。

即 **16×16×16 的 3D RGB LUT，float32 存储**。

## 来源

从 GT8 Pro（`my_product.type=24831`）的 `odm.img` 提取：

```
/odm/etc/camera/basictone/palette/HDR/SC16Lut0..8   9 × 49152 B
/odm/etc/camera/basictone/palette/SDR/SC16Lut0..8   9 × 49152 B
/odm/etc/camera/basictone/setting/SimTool_Palette_Photo_{Normal,Portrait,Night}.ini  3 × 613 B
/odm/etc/camera/basictone/setting/SimTool_Preview_Palette_Normal.ini                 121 B
```

## SHA-256 前 16 位

| 文件 | SHA-256 |
|---|---|
| HDR/SC16Lut0 | 694f087ef1d1fa32 |
| HDR/SC16Lut1 | ad281da87cc9ff59 |
| HDR/SC16Lut2 | 8da8aca255cb6895 |
| HDR/SC16Lut3 | b72680c2af5f1628 |
| HDR/SC16Lut4 | b053b86468fea0f1 |
| HDR/SC16Lut5..8 | 97476050e8613292（四个相同） |
| SDR | 与 HDR **逐字节相同** |
| SimTool_Palette_Photo_*.ini | b3debbc9d846312d（三份相同） |
| SimTool_Preview_Palette_Normal.ini | bdd2965393a34389 |

## 为什么 5~8 号是重复的

**这是 ROM 官方原样，不是提取错误。**

官方只做了 5 个独立 LUT 档位（0~4），5~8 用同一份内容填充到 9 个槽位。
实测调色盘后段色阶「没区别」的根因就在这里，与本项目无关。

## 与开源模块自带 LUT 的交叉验证

X8CamUnlock 模块内置了一批 SC16Lut，与本仓库提取的对比：

| 文件 | ROM（GT8 Pro） | X8CamUnlock 内置 | 一致 |
|---|---|---|---|
| SC16Lut0 | 694f087e | 694f087e | 是 |
| SC16Lut1 | ad281da8 | ad281da8 | 是 |
| SC16Lut2 | 8da8aca2 | 8da8aca2 | 是 |
| SC16Lut3 | b72680c2 | b72680c2 | 是 |
| SC16Lut4 | b053b864 | 45c26b24 | 否 |
| SC16Lut5-8 | 97476050 | 各不相同 | 否 |

前 4 个一致，说明确实是同一批官方资源。

## ini 内容

`SimTool_Palette_Photo_*.ini`（613 B）关键段：

```ini
[platform]      forUltra=1  mergeSize=64
[LMTLut]        lut_size=64  LMTLutNum=4
[SCLut]         lut_size=16  SCLutNum=9  Sat=-100/0/100/  Cont=-100/0/100/
[CMCWLut]       lut_size=16  CMCWLutNum=9
[ODTLut]        lut_size=64  ODTLutNum=2
[switch]        LMT=1 SC=1 CWCM=1 ODT=1 Vig=1 Merge=1
```

`[SCLut] lut_size=16 SCLutNum=9` 正好对应 9 个 16³ LUT 文件。

`SimTool_Preview_Palette_Normal.ini`（121 B）只有：

```ini
[platform]     forUltra=1 mergeSize=64
[PaletteSCLut] lut_size=16 SCLutNum=9 Sat=-100/0/100/ Cont=-100/0/100/
```
