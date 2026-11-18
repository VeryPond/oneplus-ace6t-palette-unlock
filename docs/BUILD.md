# 构建

## 环境

- Windows + PowerShell
- JDK 21（需要 keytool / javac）
- Android SDK build-tools 34.0.0（aapt2 / d8 / zipalign / apksigner）

## LSPosed 模块

源码在 `src/`，编译期需要 `libxposed/api` 的接口桩（见 `src/stub/`）。

```powershell
# 1) 编译接口桩（只做 classpath，不打进 APK）
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath android.jar -d stubclasses src\stub\**\*.java

# 2) 编译模块
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath android.jar -classpath stubclasses -d classes src\java\**\*.java

# 3) dex
d8.bat --release --min-api 30 --lib android.jar --output dexout classes\**\*.class

# 4) 资源
aapt2 compile -o flat src\res\values\strings.xml src\res\values\arrays.xml

# 5) link
aapt2 link -o unsigned.apk -I android.jar ^
  --manifest AndroidManifest.xml -A assets ^
  --min-sdk-version 30 --target-sdk-version 35 ^
  flat\values_strings.arsc.flat flat\values_arrays.arsc.flat

# 6) 加 classes.dex
aapt add unsigned.apk classes.dex

# 7) 注入 META-INF/xposed（关键，见 PITFALLS #1）
python tools/inject_meta.py unsigned.apk final.apk

# 8) 对齐 + 签名
zipalign -p -f 4 final.apk aligned.apk
apksigner sign --ks palette.jks --v1-signing-enabled true --v2-signing-enabled true --out PaletteUnlock_v1.0.apk aligned.apk
```

完整脚本见 `tools/build.ps1`。

## Magisk 模块

```bash
cd magisk
zip -r palette_unlock_magisk_v1.0.zip . -x "*.git*"
```

打包后根目录必须直接是 `module.prop`，不能多一层目录。

## 验证构建产物

```bash
# 签名（v1/v2/v3 都应通过）
apksigner verify --min-sdk-version 21 --verbose PaletteUnlock_v1.0.apk

# APK 结构
unzip -l PaletteUnlock_v1.0.apk | grep -E "xposed|classes.dex|resources.arsc"
```

注意 `apksigner verify` 默认按 APK 的 minSdk 校验，会显示
`v1 scheme: false`。加 `--min-sdk-version 21` 才是实际签名状态。
