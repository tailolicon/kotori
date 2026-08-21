# Saved with a UTF-8 BOM on purpose. Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI,
# so the Vietnamese changelog below reaches update.json as mojibake and the in-app update
# screen shows it that way. The BOM is what makes it read this file as UTF-8.
# Packages the freshly built release APKs the way the in-app updater expects:
# renamed per-ABI assets plus an update.json whose schema mirrors v1.0.6's.
param([string]$Version = "1.0.26")

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
    changelog   = "Sửa loạt lỗi hiển thị bản dịch trên trang truyện. • Chữ tràn ra ngoài bóng thoại: khi bộ dò nhả ra một hộp trùm hai bóng riêng biệt, hai câu bị gộp làm một rồi viết trải qua cả khoảng tranh giữa chúng — có trang viết rộng 716px trong một vùng rộng 112px. Nay một hộp phải đáng tin cho cả việc làm khung lẫn việc gộp, hoặc không dùng cho việc nào. • Bóng thoại rỗng: thán từ như HEY!! bị dịch thành đúng dấu câu !!, STOP... thành ... — chữ vẽ tay bị xoá đi để đóng lại một dấu chấm than. Nay không có gì để viết thì không xoá, và lời nhắc cấm hẳn kiểu trả lời đó. • Nền tô nham nhở: chỗ xoá chữ trên nền có hạt nhiễu bị vá thành một mảng mịn lộ rõ. Nay hạt nhiễu quanh đó được bê sang chỗ vá. • Bản dịch rơi nhầm ô: một panel ghi REC hiện ra lời thoại của bóng khác. Nay một vùng chữ ngắn vẫn đủ sức bác bỏ câu trả lời dài không liên quan gì tới nó. • Có lúc JSON thô bị vẽ thẳng lên trang khi máy chủ trả lời dở dang; nay bị chặn. • Gemini báo quá tải thì tự chuyển sang model khác thay vì bỏ cuộc. Trước đây chỉ chuyển khi hết quota, nên gặp lúc model đông người dùng thì thêm bao nhiêu API key cũng không cứu được — cả trang sẽ không dịch. Kiểm chứng: chạy lại toàn bộ 46 trang một chương thật, soi từng vùng thay đổi so với ảnh gốc; 48/48 ảnh trong bộ kiểm thử giữ nguyên từng pixel."
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
