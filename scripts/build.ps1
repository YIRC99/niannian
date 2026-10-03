param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$releaseDir = Join-Path $projectRoot 'release'
$backendDir = Join-Path $projectRoot 'backend'
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$oldGOOS = $env:GOOS
$oldGOARCH = $env:GOARCH
$oldCGO = $env:CGO_ENABLED
Push-Location $backendDir
try {
    if (-not $SkipTests) {
        & go test ./...
        if ($LASTEXITCODE -ne 0) { throw '后端测试未通过' }
        & go vet ./...
        if ($LASTEXITCODE -ne 0) { throw '后端静态检查未通过' }
    }
    $env:GOOS = 'linux'
    $env:CGO_ENABLED = '0'
    foreach ($arch in @('amd64','arm64')) {
        $env:GOARCH = $arch
        $folder = Join-Path $releaseDir "niannian-linux-$arch"
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
        & go build -trimpath -ldflags='-s -w' -o (Join-Path $folder 'niannian') ./cmd/server
        if ($LASTEXITCODE -ne 0) { throw "构建 $arch 失败" }
        Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy\README.md') -Destination $folder
        Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy\niannian.service') -Destination $folder
        Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy\install-service.sh') -Destination $folder
        Copy-Item -LiteralPath (Join-Path $backendDir 'config.example.json') -Destination $folder
        & tar -czf (Join-Path $releaseDir "niannian-linux-$arch.tar.gz") -C $releaseDir "niannian-linux-$arch"
        if ($LASTEXITCODE -ne 0) { throw "打包 $arch 失败" }
    }
    Get-ChildItem -LiteralPath $releaseDir -Filter '*.tar.gz' | ForEach-Object {
        $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
        Write-Output "$($hash.Hash.ToLower())  $($_.Name)"
    }
} finally {
    $env:GOOS = $oldGOOS
    $env:GOARCH = $oldGOARCH
    $env:CGO_ENABLED = $oldCGO
    Pop-Location
}
