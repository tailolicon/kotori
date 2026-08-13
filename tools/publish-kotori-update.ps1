[CmdletBinding()]
param(
    [int]$Port = 8765,
    [long]$VersionCode = 0,
    [string]$Changelog = "Bản dựng Kotori mới",
    [string]$UpdateUrl = "",
    [string]$ReleaseUrl = "",
    [string]$AssetSuffix = "",
    [ValidateSet("Release", "Update", "Debug")]
    [string]$Variant = "Update",
    [int]$MuMuVmIndex = 0,
    [string]$MuMuCliPath = "E:\Program Files\Netease\MuMuPlayer\nx_main\mumu-cli.exe",
    [switch]$SkipBuild,
    [switch]$SkipAdbReverse,
    [switch]$NoServe
)

$ErrorActionPreference = "Stop"
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$variantDirectory = $Variant.ToLowerInvariant()
$outputDir = Join-Path $repoRoot "app\build\outputs\apk\$variantDirectory"
$metadataPath = Join-Path $outputDir "output-metadata.json"
$feedDir = Join-Path $repoRoot ".update-feed"
$updateUrl = if ([string]::IsNullOrWhiteSpace($UpdateUrl)) {
    "http://127.0.0.1:$Port/update.json"
} else {
    $UpdateUrl
}
$adb = Get-Command adb -ErrorAction SilentlyContinue
$mumuCli = if (Test-Path -LiteralPath $MuMuCliPath) {
    (Resolve-Path -LiteralPath $MuMuCliPath).Path
} else {
    (Get-Command mumu-cli -ErrorAction SilentlyContinue).Source
}

if ($VersionCode -le 0) {
    # The high base keeps Kotori builds above legacy commit-count versions. Scan both build outputs
    # and connected devices, then advance past whichever code is greatest. This also guarantees two
    # publications from the same commit receive different codes.
    $candidates = [Collections.Generic.List[long]]::new()
    $commitCount = [long](& git -C $repoRoot rev-list --count HEAD)
    if ($LASTEXITCODE -ne 0) { throw "Could not determine the Git commit count." }
    $candidates.Add(1100000000L + $commitCount)

    Get-ChildItem -LiteralPath (Join-Path $repoRoot "app\build\outputs\apk") `
        -Filter "output-metadata.json" -File -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
        $metadataFile = $_.FullName
        try {
            $knownMetadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
            foreach ($element in $knownMetadata.elements) {
                $candidates.Add([long]$element.versionCode + 1)
            }
        } catch {
            Write-Warning "Could not read version metadata at ${metadataFile}: $($_.Exception.Message)"
        }
    }

    if ($mumuCli) {
        $playerInfo = & $mumuCli info --vmindex $MuMuVmIndex | ConvertFrom-Json
        if ($playerInfo.is_android_started) {
            $packageInfo = & $mumuCli sh --vmindex $MuMuVmIndex --cmd "dumpsys package app.mihon.dev"
            $versionLine = $packageInfo | Select-String -Pattern "versionCode=(\d+)" | Select-Object -First 1
            if ($versionLine -and $versionLine.Matches[0].Groups[1].Value) {
                $candidates.Add([long]$versionLine.Matches[0].Groups[1].Value + 1)
            }
        }
    } elseif ($adb) {
        $deviceLines = & $adb.Source devices
        foreach ($line in $deviceLines) {
            if ($line -notmatch "^(\S+)\s+device$") { continue }
            $serial = $Matches[1]
            $packageInfo = & $adb.Source -s $serial shell dumpsys package app.mihon.dev 2>$null
            $versionLine = $packageInfo | Select-String -Pattern "versionCode=(\d+)" | Select-Object -First 1
            if ($versionLine -and $versionLine.Matches[0].Groups[1].Value) {
                $candidates.Add([long]$versionLine.Matches[0].Groups[1].Value + 1)
            }
        }
    }

    $VersionCode = ($candidates | Measure-Object -Maximum).Maximum
}

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        & .\gradlew.bat "assemble$Variant" "-Pkotori-version-code=$VersionCode" "-Pkotori-update-url=$updateUrl"
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $metadataPath)) {
    throw "Missing update build metadata: $metadataPath"
}

if (Test-Path -LiteralPath $feedDir) {
    $resolvedFeed = [IO.Path]::GetFullPath($feedDir)
    if (-not $resolvedFeed.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clear a feed directory outside the repository: $resolvedFeed"
    }
    Remove-Item -LiteralPath $resolvedFeed -Recurse -Force
}
New-Item -ItemType Directory -Path $feedDir | Out-Null

$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$first = $metadata.elements | Select-Object -First 1
$assetVersion = if ([string]::IsNullOrWhiteSpace($AssetSuffix)) {
    [string]$first.versionName
} else {
    $AssetSuffix
}
$assets = foreach ($element in $metadata.elements) {
    $sourceApk = Join-Path $outputDir $element.outputFile
    if (-not (Test-Path -LiteralPath $sourceApk)) { throw "Missing APK: $sourceApk" }

    $abiFilter = $element.filters | Where-Object { $_.filterType -eq "ABI" } | Select-Object -First 1
    $abi = if ($abiFilter) { $abiFilter.value } else { "universal" }
    $fileName = if ($abi -eq "universal") {
        "kotori-$assetVersion.apk"
    } else {
        "kotori-$abi-$assetVersion.apk"
    }
    $targetApk = Join-Path $feedDir $fileName
    Copy-Item -LiteralPath $sourceApk -Destination $targetApk
    $hash = (Get-FileHash -LiteralPath $targetApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $file = Get-Item -LiteralPath $targetApk

    [ordered]@{
        abi = $abi
        url = $fileName
        sha256 = $hash
        size = $file.Length
    }
}

$manifest = [ordered]@{
    schema = 1
    versionCode = [long]$first.versionCode
    versionName = [string]$first.versionName
    changelog = $Changelog
    releaseUrl = $ReleaseUrl
    assets = @($assets)
}
$manifestJson = $manifest | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText((Join-Path $feedDir "update.json"), $manifestJson, [Text.UTF8Encoding]::new($false))

Write-Host "Kotori update feed ready: $updateUrl"
Write-Host "Version: $($first.versionName) ($($first.versionCode))"
Write-Host "Feed directory: $feedDir"

if (-not $SkipAdbReverse) {
    if ($mumuCli) {
        # A reverse mapping can survive an ADB transport restart while pointing at the dead host
        # connection. Always replace it through MuMu's own transport instead of trusting --list.
        & $mumuCli adb --vmindex $MuMuVmIndex --cmd "reverse --remove tcp:$Port" 2>$null | Out-Null
        & $mumuCli adb --vmindex $MuMuVmIndex --cmd "reverse tcp:$Port tcp:$Port"
        if ($LASTEXITCODE -ne 0) {
            throw "MuMu CLI could not create the update tunnel for player $MuMuVmIndex."
        }
        Write-Host "MuMu player $MuMuVmIndex tunnel ready: tcp:$Port -> tcp:$Port"
    } elseif ($adb) {
        $connectedDevices = @(& $adb.Source devices | ForEach-Object {
            if ($_ -match "^(\S+)\s+device$") { $Matches[1] }
        })
        if ($connectedDevices.Count -eq 0) {
            Write-Warning "No ADB device is connected; connect MuMu/phone before checking for updates."
        }
        foreach ($serial in $connectedDevices) {
            & $adb.Source -s $serial reverse "tcp:$Port" "tcp:$Port"
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "adb reverse failed for $serial."
            }
        }
    } else {
        Write-Warning "adb was not found; run adb reverse tcp:$Port tcp:$Port before checking for updates."
    }
}

if (-not $NoServe) {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        $ownerName = if ($owner) { "$($owner.ProcessName) (PID $($owner.Id))" } else { "PID $($listener.OwningProcess)" }
        throw "Port $Port is already used by $ownerName. Stop that server or choose another -Port."
    }

    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
    if (-not $python) { throw "Python is required to serve the local feed." }

    Write-Host "Serving updates. Keep this window open and press Ctrl+C to stop."
    if ($python.Name -eq "py.exe") {
        & $python.Source -3 -m http.server $Port --bind 127.0.0.1 --directory $feedDir
    } else {
        & $python.Source -m http.server $Port --bind 127.0.0.1 --directory $feedDir
    }
}
