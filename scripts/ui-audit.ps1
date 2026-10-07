param(
    [ValidateSet('all','account','detail','editor','main','records','arrangement')][string]$Group = 'all',
    [string]$Serial,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SystemScreens
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }
$auditRoot = Join-Path $projectRoot ('work/ui-audit/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Group)
New-Item -ItemType Directory -Force -Path $auditRoot | Out-Null
if (-not $SkipBuild) {
    Push-Location (Join-Path $projectRoot 'android')
    try {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) { throw '测试包构建失败' }
    } finally { Pop-Location }
}
if (-not $SkipInstall) {
    foreach ($apk in @('android/app/build/outputs/apk/debug/app-debug.apk','android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
        & adb @adbArgs install -r (Join-Path $projectRoot $apk)
        if ($LASTEXITCODE -ne 0) { throw '手机未允许测试包安装，请在手机确认 USB 安装后重试。正式应用未修改。' }
    }
}
try {
    $systemValue = if ($SystemScreens) { 'true' } else { 'false' }
    $result = & adb @adbArgs shell am instrument -w -e group $Group -e system $systemValue dev.niannian.reminder.audit.test/dev.niannian.reminder.UiAudit
    $result | Set-Content -LiteralPath (Join-Path $auditRoot 'instrumentation.txt') -Encoding utf8
    $result | Write-Output
    $match = [regex]::Match(($result -join "`n"), 'UI_AUDIT_DIR=(/[^\r\n]+)')
    if ($match.Success) {
        & adb @adbArgs pull $match.Groups[1].Value $auditRoot
        if ($LASTEXITCODE -ne 0) { throw '截图拉取失败' }
    }
    if (($result -join "`n") -notmatch 'PASS:') { throw '实机测试未通过，查看本次 instrumentation.txt；已有截图已保留。' }
    Write-Output "截图和结果：$auditRoot"
} finally {
    & adb @adbArgs shell am force-stop dev.niannian.reminder.audit
    & adb @adbArgs shell am force-stop dev.niannian.reminder.audit.test
}
