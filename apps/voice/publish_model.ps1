# 把训练好的模型发布到桥接器（DSH 语音 App 的模型更新）
# 用法: .\publish_model.ps1 -Name '模型名' -Epoch 500 -ModelDir 'D:\models\ep500'
param(
  [Parameter(Mandatory=$true)][string]$Name,
  [Parameter(Mandatory=$true)][int]$Epoch,
  [Parameter(Mandatory=$true)][string]$ModelDir,
  [string]$BridgeDir = (Join-Path $PSScriptRoot '..\..\bridge\app\voicemodel')
)
$ErrorActionPreference = 'Stop'
$work = Join-Path $env:TEMP ('publish-' + $Name)
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null
foreach ($f in @('model.onnx','tokens.txt','lexicon.txt')) {
  $src = Join-Path $ModelDir $f
  if (-not (Test-Path $src)) { throw "缺少 $f" }
  Copy-Item $src (Join-Path $work $f) -Force
}
$meta = Join-Path $work 'meta.json'
[ordered]@{ name = $Name; epoch = $Epoch } | ConvertTo-Json | Set-Content $meta -Encoding UTF8
$zip = Join-Path $env:TEMP ('publish-' + $Name + '.zip')
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $work 'model.onnx'),(Join-Path $work 'tokens.txt'),(Join-Path $work 'lexicon.txt'),$meta -DestinationPath $zip -Force
New-Item -ItemType Directory -Force -Path $BridgeDir | Out-Null
Copy-Item $zip (Join-Path $BridgeDir 'latest.apk') -Force
$sha = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $zip).Length
$ver = [ordered]@{
  name = $Name; versionCode = $Epoch; versionName = ('ep' + $Epoch)
  size = $size; sha256 = $sha
  publishedAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
}
[System.IO.File]::WriteAllText((Join-Path $BridgeDir 'version.json'), ($ver | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ('已发布: ' + $Name + ' (ep' + $Epoch + ') -> ' + $BridgeDir) -ForegroundColor Green
