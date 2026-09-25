# ======================================================================
# Load Allinpay env vars for the target environment into current session
# Usage: . scripts/set-allinpay-env.ps1 -Env test    (or prod)
# Note the leading dot = dot-source (required for env vars to persist)
# ======================================================================
param(
    [ValidateSet("test", "prod")]
    [string]$Env = "test"
)

$EnvFile = Join-Path $PSScriptRoot "..\.env.allinpay.$Env"

if (-not (Test-Path $EnvFile)) {
    Write-Host "[ERROR] Env file not found: $EnvFile" -ForegroundColor Red
    Write-Host "Run first: powershell -ExecutionPolicy Bypass -File scripts/gen-allinpay-keys.ps1 -Env $Env" -ForegroundColor Yellow
    exit 1
}

$count = 0
Get-Content $EnvFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $idx = $line.IndexOf('=')
    if ($idx -lt 0) { return }
    $key = $line.Substring(0, $idx).Trim()
    $val = $line.Substring($idx + 1)
    [System.Environment]::SetEnvironmentVariable($key, $val, "Process")
    $count++
}

Write-Host "[OK] Loaded $count Allinpay env vars (env=$Env) into current session" -ForegroundColor Green
Write-Host "    SPRING_PROFILES_ACTIVE = $env:SPRING_PROFILES_ACTIVE"
Write-Host "    ALLINPAY_ORG_ID        = $env:ALLINPAY_ORG_ID"
Write-Host "    ALLINPAY_CUS_ID        = $env:ALLINPAY_CUS_ID"
Write-Host "    ALLINPAY_APP_ID        = $env:ALLINPAY_APP_ID"
Write-Host "    ALLINPAY_NOTIFY_URL    = $env:ALLINPAY_NOTIFY_URL"
$pkPreview = if ($env:ALLINPAY_PRIVATE_KEY.Length -gt 32) {
    $env:ALLINPAY_PRIVATE_KEY.Substring(0, 32) + "..."
} else {
    $env:ALLINPAY_PRIVATE_KEY
}
Write-Host "    ALLINPAY_PRIVATE_KEY (first 32): $pkPreview"
Write-Host ""
Write-Host "Tip: start the app in this session:" -ForegroundColor Cyan
Write-Host "  . scripts/start-app.ps1 -Env $Env" -ForegroundColor White
