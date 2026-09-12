# Saved with a UTF-8 BOM on purpose. Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI,
# so the Vietnamese changelog below reaches update.json as mojibake and the in-app update
# screen shows it that way. The BOM is what makes it read this file as UTF-8.
# Packages the freshly built release APKs the way the in-app updater expects:
# renamed per-ABI assets plus an update.json whose schema mirrors v1.0.6's.
param([string]$Version = "1.0.30")

$ErrorActionPreference = "Stop"
$repo = "E:\Project\kotori"
$src = Join-Path $repo "app\build\outputs\apk\release"
$out = Join-Path $repo ".transwork\release-v$Version"
New-Item -ItemType Directory -Force $out | Out-Null

# 64-bit only since 1.0.8. Anything not built is simply not published; listing an abi here that
# the build no longer produces would abort the packaging on a file that is gone on purpose.
$map = @{
    "app-x86_64-release.apk"    = @{ abi = "x86_64";    name = "kotori-x86_64-v$Version.apk" }
    "app-arm64-v8a-release.apk" = @{ abi = "arm64-v8a"; name = "kotori-arm64-v8a-v$Version.apk" }
}

$meta = Get-Content (Join-Path $src "output-metadata.json") -Raw | ConvertFrom-Json
$versionCode = ($meta.elements | Measure-Object -Property versionCode -Maximum).Maximum

$assets = @()
foreach ($entry in $map.GetEnumerator()) {
    $srcFile = Join-Path $src $entry.Key
    if (-not (Test-Path $srcFile)) { throw "missing $srcFile" }
    $dst = Join-Path $out $entry.Value.name
    Copy-Item $srcFile $dst -Force
    $assets += [ordered]@{
        abi    = $entry.Value.abi
        url    = $entry.Value.name
        sha256 = (Get-FileHash $dst -Algorithm SHA256).Hash.ToLowerInvariant()
        size   = (Get-Item $dst).Length
    }
}
# The universal APK first, matching the v1.0.6 feed the updater already parses.
$assets = @($assets | Where-Object { $_.abi -eq "universal" }) + @($assets | Where-Object { $_.abi -ne "universal" })

$feed = [ordered]@{
    schema      = 1
    versionCode = [long]$versionCode
    versionName = $Version
    changelog   = 'Làm cuộn truyện webtoon mượt hơn trên màn hình tần số quét cao: Kotori yêu cầu 90/120 Hz khi kéo hoặc fling và trở về mặc định khi dừng để tiết kiệm pin. Khôi phục chữ ký phát hành gốc để điện thoại đang ở v1.0.27 có thể cập nhật trực tiếp mà không mất dữ liệu.'
    releaseUrl  = "https://github.com/tailolicon/kotori/releases/tag/v$Version"
    assets      = $assets
}
# WriteAllText with BOM-less UTF8: Out-File -Encoding utf8 writes a BOM on Windows PowerShell 5,
# and kotlinx.serialization on the phone rejects BOM-prefixed JSON outright — the in-app updater
# then reports an error instead of the new version. Shipped exactly once; never again.
[IO.File]::WriteAllText(
    (Join-Path $out "update.json"),
    ($feed | ConvertTo-Json -Depth 5),
    (New-Object System.Text.UTF8Encoding($false))
)
Write-Output "versionCode=$versionCode"
Get-ChildItem $out | Select-Object Name, Length | Format-Table -AutoSize
