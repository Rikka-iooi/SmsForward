[CmdletBinding()]
param(
    [switch]$DebugBuild,
    [switch]$Install,
    [string]$SdkPath = ""
)

$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }

function Fail([string]$message) {
    Write-Host ""
    Write-Host "BUILD FAILED: $message" -ForegroundColor Red
    exit 1
}

function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "C:\Program Files\JetBrains\PyCharm 2025.3.3\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Java\jdk-17"
    )
    if (Test-Path "C:\Program Files\JetBrains") {
        Get-ChildItem "C:\Program Files\JetBrains" -Directory | ForEach-Object {
            $candidates += (Join-Path $_.FullName "jbr")
        }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\javac.exe"))) {
            return $candidate
        }
    }
    return $null
}

$jdk = Find-Jdk
if (-not $jdk) { Fail "No JDK with javac.exe found. Set JAVA_HOME to a JDK 17 or newer." }

if (-not $SdkPath) {
    if ($env:ANDROID_SDK_ROOT) { $SdkPath = $env:ANDROID_SDK_ROOT }
    elseif ($env:ANDROID_HOME) { $SdkPath = $env:ANDROID_HOME }
    else { $SdkPath = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
}
if (-not (Test-Path $SdkPath)) { Fail "Android SDK not found: $SdkPath" }

$buildTools = Get-ChildItem (Join-Path $SdkPath "build-tools") -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName "aapt2.exe") } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $buildTools) { Fail "No Android build-tools with aapt2.exe found." }
$bt = $buildTools.FullName

$androidJar = $null
$platforms = Get-ChildItem (Join-Path $SdkPath "platforms") -Directory |
    Sort-Object {
        $v = $_.Name -replace '^android-', ''
        if ($v -match '^\d+$') { $v = "$v.0" }
        [version]$v
    } -Descending
foreach ($platform in $platforms) {
    $jar = Join-Path $platform.FullName "android.jar"
    if (Test-Path $jar) { $androidJar = $jar; break }
}
if (-not $androidJar) { Fail "No platforms/android-*/android.jar found." }

$aapt2 = Join-Path $bt "aapt2.exe"
$zipalign = Join-Path $bt "zipalign.exe"
$apksigner = Join-Path $bt "apksigner.bat"
$d8 = Join-Path $bt "d8.bat"
$javac = Join-Path $jdk "bin\javac.exe"
$keytool = Join-Path $jdk "bin\keytool.exe"

$python = $null
foreach ($candidate in @("C:\python\python.exe", (Get-Command python.exe -ErrorAction SilentlyContinue).Source)) {
    if ($candidate -and (Test-Path $candidate)) { $python = $candidate; break }
}
if (-not $python) { Fail "Python 3 is required to package classes.dex into the APK." }

$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk "bin") + ";" + $env:Path

Write-Host "JDK:          $jdk"
Write-Host "Build tools:  $bt"
Write-Host "android.jar:  $androidJar"
Write-Host "Mode:         $(if ($DebugBuild) {'debug'} else {'release'})"

$out = Join-Path $root "build"
$gen = Join-Path $out "gen"
$classes = Join-Path $out "classes"
$dex = Join-Path $out "dex"
$dist = Join-Path $root "dist"
foreach ($dir in @($out, $gen, $classes, $dex, $dist)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
foreach ($dir in @($gen, $classes, $dex)) {
    Get-ChildItem $dir -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
}

$manifestSource = Join-Path $root "AndroidManifest.xml"
$manifest = Join-Path $out "AndroidManifest.xml"
$manifestText = Get-Content $manifestSource -Raw
if ($DebugBuild) {
    $debugReceiver = @'
        <receiver
            android:name=".DebugSmsReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.hp.smsforward.DEBUG_SMS" />
                <action android:name="com.hp.smsforward.DEBUG_WEBHOOK" />
                <action android:name="com.hp.smsforward.DEBUG_EMAIL" />
                <action android:name="com.hp.smsforward.DEBUG_TEST" />
                <action android:name="com.hp.smsforward.DEBUG_DISABLE" />
                <action android:name="com.hp.smsforward.DEBUG_PDU" />
                <action android:name="com.hp.smsforward.DEBUG_SEND_SMS" />
            </intent-filter>
        </receiver>
'@
    $manifestText = $manifestText.Replace("<!-- DEBUG_COMPONENTS -->", $debugReceiver)
    $manifestText = $manifestText.Replace("    <application",
        "    <uses-permission android:name=`"android.permission.SEND_SMS`" />`r`n`r`n    <application")
}
Set-Content -Path $manifest -Value $manifestText -Encoding UTF8

$resZip = Join-Path $out "resources.zip"
Write-Host "Compiling resources..."
& $aapt2 compile --dir (Join-Path $root "res") -o $resZip
if ($LASTEXITCODE -ne 0) { Fail "aapt2 compile failed." }

$unsignedApk = Join-Path $out "app-unsigned.apk"
Write-Host "Linking resources..."
$aapt2Args = @(
    "link",
    "-o", $unsignedApk,
    "-I", $androidJar,
    "--manifest", $manifest,
    "--java", $gen,
    "--min-sdk-version", "26",
    "--target-sdk-version", "34",
    "--version-code", "1",
    "--version-name", "1.0",
    $resZip
)
if ($DebugBuild) { $aapt2Args += "--debug-mode" }
& $aapt2 @aapt2Args
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link failed." }

$sourceFiles = @(Get-ChildItem (Join-Path $root "java") -Recurse -Filter *.java)
$sourceFiles += @(Get-ChildItem $gen -Recurse -Filter *.java)
if (-not $DebugBuild) {
    $sourceFiles = @($sourceFiles | Where-Object { $_.Name -ne "DebugSmsReceiver.java" })
}
Write-Host "Compiling Java ($($sourceFiles.Count) files)..."
$sourcePaths = $sourceFiles | ForEach-Object { $_.FullName }
& $javac --release 11 -encoding UTF-8 -cp $androidJar -d $classes @sourcePaths
if ($LASTEXITCODE -ne 0) { Fail "javac failed." }

Write-Host "Dexing..."
$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --lib $androidJar --min-api 26 --output $dex @classFiles
if ($LASTEXITCODE -ne 0) { Fail "d8 failed." }

$dexFile = Join-Path $dex "classes.dex"
if (-not (Test-Path $dexFile)) { Fail "d8 did not produce classes.dex." }

$unalignedApk = Join-Path $out "app-with-dex.apk"
Copy-Item $unsignedApk $unalignedApk -Force
& $python -c "import sys, zipfile; apk, dex = sys.argv[1], sys.argv[2]; z = zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED); z.write(dex, 'classes.dex'); z.close()" $unalignedApk $dexFile
if ($LASTEXITCODE -ne 0) { Fail "Could not add classes.dex to the APK." }

$alignedApk = Join-Path $out "app-aligned.apk"
& $zipalign -f 4 $unalignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { Fail "zipalign failed." }

$signingDir = Join-Path $root "signing"
$keystore = Join-Path $signingDir "smsforward.keystore"
$properties = Join-Path $signingDir "keystore.properties"
New-Item -ItemType Directory -Force -Path $signingDir | Out-Null

if (-not (Test-Path $keystore) -or -not (Test-Path $properties)) {
    Write-Host "Generating a private signing key..."
    $storePass = [guid]::NewGuid().ToString("N")
    $keyPass = $storePass
    & $keytool -genkeypair -v `
        -keystore $keystore `
        -storetype PKCS12 `
        -storepass $storePass `
        -keypass $keyPass `
        -alias "smsforward" `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=SMS Forwarder, OU=Personal, O=Local, L=Shanghai, ST=Shanghai, C=CN"
    if ($LASTEXITCODE -ne 0) { Fail "keytool failed." }
    @(
        "storePassword=$storePass",
        "keyPassword=$keyPass",
        "keyAlias=smsforward"
    ) | Set-Content -Path $properties -Encoding UTF8
} else {
    $props = @{}
    Get-Content $properties | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { $props[$matches[1]] = $matches[2] }
    }
    $storePass = $props["storePassword"]
    $keyPass = $props["keyPassword"]
    if (-not $storePass -or -not $keyPass) { Fail "signing/keystore.properties is incomplete." }
}

$signedApk = Join-Path $out "app-signed.apk"
& $apksigner sign `
    --ks $keystore `
    --ks-key-alias "smsforward" `
    --ks-pass "pass:$storePass" `
    --key-pass "pass:$keyPass" `
    --min-sdk-version 26 `
    --out $signedApk `
    $alignedApk
if ($LASTEXITCODE -ne 0) { Fail "apksigner sign failed." }

& $apksigner verify --print-certs $signedApk | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "apksigner verify failed." }

$name = if ($DebugBuild) { "SmsForwarder-debug.apk" } else { "SmsForwarder-release.apk" }
$artifact = Join-Path $dist $name
Copy-Item $signedApk $artifact -Force
$hash = (Get-FileHash $artifact -Algorithm SHA256).Hash

Write-Host ""
Write-Host "APK:    $artifact" -ForegroundColor Green
Write-Host "SHA256: $hash"
Write-Host "Size:   $([Math]::Round((Get-Item $artifact).Length / 1KB, 1)) KB"

if ($Install) {
    $adb = Join-Path $SdkPath "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { Fail "adb not found at $adb" }
    Write-Host "Installing on the connected phone..."
    & $adb install -r -g $artifact
    if ($LASTEXITCODE -ne 0) { Fail "adb install failed. Check USB debugging and the phone screen." }
    & $adb shell am start -n "com.hp.smsforward/.MainActivity"
    Write-Host "Installed. App launched." -ForegroundColor Green
}
