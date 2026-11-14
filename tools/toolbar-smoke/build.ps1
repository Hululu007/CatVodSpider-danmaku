param([string]$OutputName = 'r2')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$outputRoot = Join-Path $root 'build/toolbar-smoke'
$out = [IO.Path]::GetFullPath((Join-Path $outputRoot $OutputName))
if (-not $out.StartsWith($outputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must stay inside build/toolbar-smoke' }
$sdk = 'C:/Users/38033/AppData/Local/Android/Sdk'
$java = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin'
$bt = Join-Path $sdk 'build-tools/36.0.0'
$android = Join-Path $sdk 'platforms/android-36/android.jar'
$env:JAVA_HOME = Split-Path $java
$env:Path = "$java;$env:Path"
New-Item -ItemType Directory -Force -Path $out,(Join-Path $out 'classes'),(Join-Path $out 'dex') | Out-Null
function Assert-Exit { if ($LASTEXITCODE -ne 0) { throw "Tool failed: $LASTEXITCODE" } }
& "$bt/aapt.exe" package -f -M "$PSScriptRoot/AndroidManifest.xml" -S "$PSScriptRoot/res" -I $android -F "$out/resources.apk"
Assert-Exit
$gson = 'C:/Users/38033/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.13.2/48b8230771e573b54ce6e867a9001e75977fe78e/gson-2.13.2.jar'
$src = @('ToolbarHostMap.java','ToolbarTheme.java','ToolbarDialogFocus.java','HostDanmakuSelection.java','PlayerToolbarController.java','DanmakuConfig.java','DanmakuConfigManager.java') | ForEach-Object {Join-Path $root "app/src/main/java/com/github/catvod/spider/$_"}
$src += Get-ChildItem "$PSScriptRoot/src" -Recurse -Filter '*.java' | ForEach-Object FullName
& "$java/javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath "$android;$gson" -d "$out/classes" @src
Assert-Exit
& "$java/jar.exe" cf "$out/classes.jar" -C "$out/classes" .
Assert-Exit
& "$bt/d8.bat" --lib $android --min-api 25 --output "$out/dex" "$out/classes.jar" $gson
Assert-Exit
Copy-Item -LiteralPath "$out/resources.apk" -Destination "$out/unsigned.apk" -Force
$zip = [IO.Compression.ZipFile]::Open("$out/unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$out/dex/classes.dex",'classes.dex') | Out-Null
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$root/device_inspect/toolbar/T3/host.apk",'assets/host-t3.apk') | Out-Null
} finally { $zip.Dispose() }
& "$bt/zipalign.exe" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
Assert-Exit
if (-not (Test-Path "$out/smoke.jks")) {
    & "$java/keytool.exe" -genkeypair -keystore "$out/smoke.jks" -storepass toolbarcheck -keypass toolbarcheck -alias smoke -keyalg RSA -validity 3650 -dname 'CN=Toolbar Local Check'
    Assert-Exit
}
& "$bt/apksigner.bat" sign --ks "$out/smoke.jks" --ks-pass pass:toolbarcheck --key-pass pass:toolbarcheck --out "$out/toolbar-smoke.apk" "$out/aligned.apk"
Assert-Exit
Write-Output "Built $out/toolbar-smoke.apk"
