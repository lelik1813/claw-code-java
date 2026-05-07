<#
.SYNOPSIS
    Phase 8 controlled rollout smoke test for claw-code-java.

.DESCRIPTION
    Runs a minimal pass/fail checklist against a running claw-code-java:
    health check, CLI session create, message send, stream attach,
    and auth failure with expected exit code 3.

.PARAMETER BaseUrl
    Server base URL. Default: http://localhost:8080

.PARAMETER ApiKey
    Valid API key for the server.

.EXAMPLE
    .\scripts\phase8-smoke.ps1 -ApiKey my-secret-key
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = $(throw "ApiKey is required: -ApiKey <key>")
)

$ErrorActionPreference = "Stop"
$Pass = 0
$Fail = 0

function Assert-Test {
    param([string]$Name, [bool]$Condition, [string]$Detail = "")
    if ($Condition) {
        Write-Host "  PASS  $Name" -ForegroundColor Green
        $script:Pass++
    } else {
        Write-Host "  FAIL  $Name $(if ($Detail) { "($Detail)" })" -ForegroundColor Red
        $script:Fail++
    }
}

# Resolve CLI entry point
$Jar = Get-ChildItem -Path "target" -Filter "claw-code-java-*.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1

if (-not $Jar) {
    Write-Host "FAIL  claw-code-java JAR not found in target/" -ForegroundColor Red
    exit 1
}

$Cli = "java", "-cp", $Jar.FullName, "com.clawcode.agent.cli.AgentCliApplication"
Write-Host "`nclaw-code-java Phase 8 Smoke Test"
Write-Host "Server: $BaseUrl"
Write-Host "JAR:    $($Jar.Name)`n"

# --- 1. Health check ---
Write-Host "[1/5] Health check"
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
    Assert-Test "GET /actuator/health" ($health.status -eq "UP") "status=$($health.status)"
} catch {
    Assert-Test "GET /actuator/health" $false $_.Exception.Message
}

# --- 2. Auth failure (expect 401) ---
Write-Host "[2/5] Auth failure check"
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/sessions" -Method POST -TimeoutSec 5 2>$null
    Assert-Test "POST /api/sessions without key returns 401" $false "request succeeded unexpectedly"
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Assert-Test "POST /api/sessions without key returns 401" ($code -eq 401) "got $code"
}

# --- 3. CLI session create ---
Write-Host "[3/5] CLI session create"
$sessionOutput = & $Cli @("--base-url", $BaseUrl, "--api-key", $ApiKey, "session", "create") 2>&1
$sessionExit = $LASTEXITCODE
Assert-Test "session create exit 0" ($sessionExit -eq 0) "exit=$sessionExit"

$sessionId = if ($sessionOutput -is [System.Array]) { $sessionOutput[0] } else { $sessionOutput }
$sessionId = $sessionId.Trim()
Assert-Test "session ID returned" ($sessionId -match "^[0-9a-f-]{36}$") "value=$sessionId"

# --- 4. CLI message send ---
Write-Host "[4/5] CLI message send"
if ($sessionId -match "^[0-9a-f-]{36}$") {
    $msgOutput = & $Cli @("--base-url", $BaseUrl, "--api-key", $ApiKey, "message", "send", $sessionId, "smoke test") 2>&1
    $msgExit = $LASTEXITCODE
    Assert-Test "message send exit 0" ($msgExit -eq 0) "exit=$msgExit"
} else {
    Assert-Test "message send exit 0" $false "skipped: no session ID"
}

# --- 5. CLI stream attach ---
Write-Host "[5/6] CLI stream attach"
if ($sessionId -match "^[0-9a-f-]{36}$") {
    $streamProc = Start-Process -FilePath "java" `
        -ArgumentList "-cp", $Jar.FullName, "com.clawcode.agent.cli.AgentCliApplication",
            "--base-url", $BaseUrl, "--api-key", $ApiKey,
            "stream", "attach", $sessionId `
        -NoNewWindow -PassThru -RedirectStandardOutput "$env:TEMP\agent-stream.txt" `
        -RedirectStandardError "$env:TEMP\agent-stream-err.txt"
    Start-Sleep -Seconds 3
    $streamRunning = -not $streamProc.HasExited
    if ($streamRunning) { $streamProc.Kill() }
    $streamOutput = Get-Content "$env:TEMP\agent-stream.txt" -Raw -ErrorAction SilentlyContinue
    Assert-Test "stream attach connects (process ran 3s)" $streamRunning "exited early"
} else {
    Assert-Test "stream attach connects (process ran 3s)" $false "skipped: no session ID"
}

# --- 6. CLI auth failure (expect exit 3) ---
Write-Host "[6/6] CLI auth failure exit code"
& $Cli @("--base-url", $BaseUrl, "--api-key", "wrong-key", "session", "create") 2>$null | Out-Null
$authExit = $LASTEXITCODE
Assert-Test "wrong key exit 3 (EXIT_AUTH)" ($authExit -eq 3) "exit=$authExit"

# --- Summary ---
Write-Host "`nResults: $Pass passed, $Fail failed`n"
if ($Fail -gt 0) { exit 1 }
exit 0
