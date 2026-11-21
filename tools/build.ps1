$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$SRCROOT = 'E:\灵感构图\_palette_work'
$ROOT = 'C:\palettebuild'
$SDK  = 'E:\灵感构图\androidsdk'
$BT   = Join-Path $SDK 'build-tools\34.0.0'
$PLATSRC = Join-Path $SDK 'platforms\android-34\android.jar'
$PLAT = 'C:\palettebuild\android.jar'
$OUT  = Join-Path $SRCROOT 'out'
$SIGNED = Join-Path $OUT 'PaletteUnlock_v1.0.apk'

foreach ($p in @($BT,$PLATSRC)) { if (!(Test-Path $p)) { throw "missing: $p" } }
if (Test-Path $ROOT) { Remove-Item $ROOT -Recurse -Force }
New-Item -ItemType Directory -Path $ROOT -Force | Out-Null
Copy-Item $PLATSRC $PLAT -Force
New-Item -ItemType Directory -Path $OUT -Force | Out-Null
$env:ANDROID_HOME = $SDK

Write-Host '=== stage sources (ASCII path) ==='
Copy-Item (Join-Path $SRCROOT 'lsposed_src') (Join-Path $ROOT 'project') -Recurse -Force
Copy-Item (Join-Path $SRCROOT 'libxposed_stub') (Join-Path $ROOT 'stub') -Recurse -Force
$PROJ = Join-Path $ROOT 'project'

Write-Host '=== javac (stub as classpath only, NOT bundled) ==='
$stubCls = Join-Path $ROOT 'stubclasses'
New-Item -ItemType Directory -Path $stubCls -Force | Out-Null
$stubSrcs = Get-ChildItem -Path (Join-Path $ROOT 'stub') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $PLAT -d $stubCls $stubSrcs 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'javac stub failed' }

$cls = Join-Path $ROOT 'classes'
New-Item -ItemType Directory -Path $cls -Force | Out-Null
$srcs = Get-ChildItem -Path (Join-Path $PROJ 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $PLAT -classpath $stubCls -d $cls $srcs 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '=== d8 ==='
$dexout = Join-Path $ROOT 'dexout'
New-Item -ItemType Directory -Path $dexout -Force | Out-Null
$classes = Get-ChildItem -Path $cls -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $BT 'd8.bat') --release --min-api 30 --lib $PLAT --output $dexout $classes 2>&1 | Out-String | Write-Host
if (!(Test-Path (Join-Path $dexout 'classes.dex'))) { throw 'd8 failed' }
Write-Host ('  classes.dex ' + (Get-Item (Join-Path $dexout 'classes.dex')).Length + ' B')

Write-Host '=== aapt2 link ==='
$unaligned = Join-Path $ROOT 'unaligned.apk'
$flat = Join-Path $ROOT 'flat'
New-Item -ItemType Directory -Path $flat -Force | Out-Null
$resFiles = Get-ChildItem -Path (Join-Path $PROJ 'res') -Recurse -File | ForEach-Object { $_.FullName }
& (Join-Path $BT 'aapt2.exe') compile -o $flat $resFiles 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
$flats = Get-ChildItem -Path $flat -File | ForEach-Object { $_.FullName }
& (Join-Path $BT 'aapt2.exe') link -o $unaligned -I $PLAT --manifest (Join-Path $PROJ 'AndroidManifest.xml') -A (Join-Path $PROJ 'assets') --min-sdk-version 30 --target-sdk-version 35 --version-code 1 --version-name 1.0 $flats 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '=== add classes.dex + META-INF/xposed ==='
Push-Location $dexout
& (Join-Path $BT 'aapt.exe') add $unaligned 'classes.dex' 2>&1 | Out-String | Write-Host
Pop-Location
# libxposed 必需：META-INF/xposed/module.prop 与 java_init.list（aapt2 link 不会带进去）
$xd = Join-Path $PROJ 'META-INF\xposed'
if (!(Test-Path $xd)) { throw "missing libxposed metadata dir: $xd" }
Push-Location $PROJ
& (Join-Path $BT 'aapt.exe') add $unaligned 'META-INF\xposed\module.prop' 'META-INF\xposed\java_init.list' 2>&1 | Out-String | Write-Host
Pop-Location

Write-Host '=== zipalign ==='
$aligned = Join-Path $ROOT 'aligned.apk'
& (Join-Path $BT 'zipalign.exe') -p -f 4 $unaligned $aligned 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '=== sign ==='
$ErrorActionPreference = 'Continue'
$kt = 'C:\Program Files\Java\jdk-21.0.12.1\bin\keytool.exe'
$ks = Join-Path $ROOT 'palette.jks'
& $kt -genkeypair -keystore $ks -storepass palette123 -keypass palette123 -alias palette -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Palette Unlock, O=Self, C=CN' 2>&1 | Out-String | Write-Host
# 分两步：先只用 v1 签（让 META-INF 落进 zip），再 v2+v3 覆盖（v1 会保留）
$v1apk = Join-Path $ROOT 'v1.apk'
& (Join-Path $BT 'apksigner.bat') sign --ks $ks --ks-pass pass:palette123 --key-pass pass:palette123 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false --out $v1apk $aligned 2>&1 | Out-String | Write-Host
& (Join-Path $BT 'apksigner.bat') sign --ks $ks --ks-pass pass:palette123 --key-pass pass:palette123 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out $SIGNED $v1apk 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& (Join-Path $BT 'apksigner.bat') verify --print-certs $SIGNED 2>&1 | Out-String | Write-Host
Write-Host ('BUILD OK -> ' + $SIGNED + '  ' + [math]::Round((Get-Item $SIGNED).Length/1KB,1) + ' KB')
