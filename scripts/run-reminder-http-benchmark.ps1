param(
    [string]$BaseURL = 'http://127.0.0.1:56507',
    [string]$ConfigPath = '',
    [string]$OutputPath = '',
    [string]$SuitePath = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$ConfigPath) { $ConfigPath = Join-Path $root 'release/config.local.json' }
if (!$OutputPath) { throw 'Specify OutputPath for this run.' }
if (!$SuitePath) { $SuitePath = Join-Path $root 'docs/benchmarks/reminder-creation-v1.json' }
$suite = Get-Content -LiteralPath $SuitePath -Raw | ConvertFrom-Json -DateKind String
$cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
if ($cfg.admin_key.Length -lt 32) { throw 'Configured admin key is required.' }
$adminHeaders = @{ Authorization = 'Bearer ' + $cfg.admin_key }
$base = $BaseURL.TrimEnd('/')
$health = Invoke-RestMethod "$base/health" -TimeoutSec 10
# Read-only authorization check before creating one disposable benchmark account.
$null = Invoke-RestMethod "$base/admin/users?limit=1&q=benchmark-preflight-no-match" -Headers $adminHeaders -TimeoutSec 10
$result = [ordered]@{
    lane = 'running_backend_http'; endpoint = "$base/api/v1/ai/parse"; health = $health
    started_at = [DateTimeOffset]::Now.ToString('o'); fixed_clock = $false
    source_commit = (git -C $root rev-parse HEAD); cases = @(); cleanup = 'pending'
    suite_file = (Split-Path $SuitePath -Leaf); suite_sha256 = (Get-FileHash -LiteralPath $SuitePath -Algorithm SHA256).Hash.ToLower()
    method = 'Original inputs, real server clock; original fixed-time gold answers must not be used as-is. One temporary account, no task creation.'
}
$outDir = Split-Path $OutputPath -Parent
$null = New-Item -ItemType Directory -Force -Path $outDir
function Save-Result {
    $json = ($result | ConvertTo-Json -Depth 40).Replace("`r`n", "`n") + "`n"
    [IO.File]::WriteAllText($OutputPath, $json, [Text.UTF8Encoding]::new($false))
}
$testUserId = $null
try {
    $credentials = @{ email = ('benchmark-' + [guid]::NewGuid().ToString('N') + '@example.com'); password = [guid]::NewGuid().ToString('N') }
    $user = Invoke-RestMethod "$base/admin/users" -Method Post -Headers $adminHeaders -ContentType 'application/json; charset=utf-8' -Body ($credentials | ConvertTo-Json -Compress) -TimeoutSec 15
    $testUserId = $user.id
    $login = Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body ($credentials | ConvertTo-Json -Compress) -TimeoutSec 15
    $headers = @{ Authorization = 'Bearer ' + $login.token }
    $lastStart = $null
    foreach ($case in $suite.cases) {
        # Respect the existing 6 requests/minute limit without retries or extra accounts.
        if ($lastStart) {
            $remaining = 11000 - ([DateTimeOffset]::Now - $lastStart).TotalMilliseconds
            if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int]$remaining) }
        }
        $lastStart = [DateTimeOffset]::Now
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-WebRequest "$base/api/v1/ai/parse" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body (@{text=$case.input} | ConvertTo-Json -Compress) -TimeoutSec 80 -SkipHttpErrorCheck
        $watch.Stop()
        $record = [ordered]@{
            id=$case.id; request_started_at=$lastStart.ToString('o'); response_received_at=[DateTimeOffset]::Now.ToString('o')
            http_status=[int]$response.StatusCode; elapsed_ms=$watch.ElapsedMilliseconds; input=$case.input
            original_fixture_now=$case.now; server_date=$response.Headers.Date; raw_response=$response.Content
            response=($response.Content | ConvertFrom-Json -DateKind String)
        }
        $result.cases += $record
        Save-Result
        Write-Output "$($case.id): HTTP $($record.http_status), $($record.elapsed_ms) ms"
    }
    $account = Invoke-RestMethod "$base/admin/users/$testUserId" -Headers $adminHeaders -TimeoutSec 10
    $result.task_count_after = $account.task_count
    $result.finished_at = [DateTimeOffset]::Now.ToString('o')
} finally {
    if ($testUserId) {
        try {
            $null = Invoke-RestMethod "$base/admin/users/$testUserId" -Method Delete -Headers $adminHeaders -TimeoutSec 15
            $verify = Invoke-WebRequest "$base/admin/users/$testUserId" -Headers $adminHeaders -SkipHttpErrorCheck -TimeoutSec 10
            if ($verify.StatusCode -ne 404) { throw 'Temporary account cleanup could not be verified.' }
            $result.cleanup = 'temporary_account_deleted_verified_404'
        } catch {
            $result.cleanup = 'failed'; Save-Result; throw
        }
    }
    Save-Result
}
