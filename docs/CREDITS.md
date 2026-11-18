# 参考与致谢

## 关键参考

| 项目 | 用途 |
|---|---|
| [X8CamUnlock](https://github.com/) （com.yg.x8camunlock） | 调色盘解锁的整体思路：强制能力位 + 资源搬家 + 滑块桩。本项目的「打桩」思路来源于它的 `PaletteSeekbarFix` |
| [libxposed/api](https://github.com/libxposed/api) | 模块框架接口（API 102） |

## 工具

| 工具 | 用途 |
|---|---|
| jadx 1.5.6 | 单类反编译 |
| Android SDK build-tools 34.0.0 | aapt2 / d8 / zipalign / apksigner |
| 自写 DEX 解析器 | 批量扫描 const-string 引用、判断 class_defs 是否定义 |

## 资源来源

LUT 与 ini 资源提取自 OnePlus GT8 Pro 的 `odm.img`（`my_product.type=24831`，ColorOS 17）。

## 生成说明

本仓库的代码、文档、构建脚本与真机验证流程由 **DeepSeek Harness** 生成。
所有逆向结论均标注了具体来源（类名 / 方法名 / dex 文件 / 行号），
无法确证的部分在文档中明确标注为推测。
