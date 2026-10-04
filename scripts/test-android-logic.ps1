$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $projectRoot 'work/java-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$tests = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'android/tests') -Filter '*Test.java'
$sources = $tests | ForEach-Object { Join-Path $projectRoot ('android/app/src/main/java/dev/niannian/reminder/' + $_.BaseName.Replace('Test','') + '.java') }
& javac -encoding UTF-8 -d $output $sources $tests.FullName
if ($LASTEXITCODE -ne 0) { throw 'Java 检查编译失败' }
foreach ($test in $tests) {
    & java -cp $output ('dev.niannian.reminder.' + $test.BaseName)
    if ($LASTEXITCODE -ne 0) { throw "检查失败：$($test.Name)" }
}
