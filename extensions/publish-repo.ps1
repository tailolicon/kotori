$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $root) { $root = (Resolve-Path "$PSScriptRoot\..").Path }
Set-Location $root

$env:TMP = "C:\Windows\Temp"
$env:TEMP = "C:\Windows\Temp"

Write-Host "Building Hitomi extension APK..."
& .\gradlew.bat :hitomi-ext:assembleRelease --offline
if ($LASTEXITCODE -ne 0) {
    & .\gradlew.bat :hitomi-ext:assembleRelease
}
if ($LASTEXITCODE -ne 0) { throw "Hitomi APK build failed" }

Write-Host "Generating index.pb / repo.json / index.min.json..."
& .\gradlew.bat :ext-indexgen:generateRepo
if ($LASTEXITCODE -ne 0) { throw "index generation failed" }

Write-Host "Done. Add this store URL in Kotori:"
Write-Host "  https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo/index.pb"
