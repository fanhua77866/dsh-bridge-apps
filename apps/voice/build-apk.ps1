# Portable build script (Windows PowerShell)
# 前提：已安装 JDK 17、Android SDK（platform 35 + build-tools 35），并设置
#   JAVA_HOME / ANDROID_HOME（或 ANDROID_SDK_ROOT），Gradle 8.9 在 PATH 中
$ErrorActionPreference = 'Stop'

$proj = $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw '请先设置 ANDROID_HOME 或 ANDROID_SDK_ROOT' }
$jdk = $env:JAVA_HOME
if (-not $jdk) { throw '请先设置 JAVA_HOME' }
$gradle = if ($env:GRADLE_HOME) { Join-Path $env:GRADLE_HOME 'bin/gradle.bat' } else { 'gradle.bat' }

$dst = Join-Path $env:TEMP ('build-' + (Split-Path $proj -Leaf))
if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
New-Item -ItemType Directory -Path $dst | Out-Null
Copy-Item (Join-Path $proj '*') $dst -Recurse -Force
Set-Content -Path (Join-Path $dst 'local.properties') -Value ("sdk.dir=" + ($sdk -replace '\\', '\\\\')) -Encoding ascii

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

Push-Location $dst
try { & $gradle assembleDebug --no-daemon --console=plain } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }

$apk = Join-Path $dst 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $apk)) { throw 'apk not found' }
$out = Join-Path $proj 'app-debug.apk'
Copy-Item $apk $out -Force
Write-Host ('OK -> ' + $out) -ForegroundColor Green
