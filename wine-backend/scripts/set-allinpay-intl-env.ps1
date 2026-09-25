# ======================================================================
# Load Allinpay International env vars into current session
# Usage: . scripts/set-allinpay-intl-env.ps1 -Env test    (or prod)
# Note the leading dot = dot-source (required for env vars to persist)
# ======================================================================
param(
    [ValidateSet("test", "prod")]
    [string]$Env = "test"
)

$EnvFile = Join-Path $PSScriptRoot "..\.env.allinpay-intl.$Env"

if (-not (Test-Path $EnvFile)) {
    Write-Host "[ERROR] Env file not found: $EnvFile" -ForegroundColor Red
    Write-Host "Run first: powershell -ExecutionPolicy Bypass -File scripts/gen-allinpay-intl-keys.ps1 -Env $Env" -ForegroundColor Yellow
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

Write-Host "[OK] Loaded $count Allinpay Intl env vars (env=$Env) into current session" -ForegroundColor Green
Write-Host "    SPRING_PROFILES_ACTIVE    = $env:SPRING_PROFILES_ACTIVE"
Write-Host "    ALLINPAY_INTL_BASE_URL    = $env:ALLINPAY_INTL_BASE_URL"
Write-Host "    ALLINPAY_INTL_MCHT_ID     = $env:ALLINPAY_INTL_MCHT_ID"
Write-Host "    ALLINPAY_INTL_INST_NO     = $env:ALLINPAY_INTL_INST_NO"
Write-Host "    ALLINPAY_INTL_CURRENCY     = $env:ALLINPAY_INTL_CURRENCY"
Write-Host "    ALLINPAY_INTL_MOCK         = $env:ALLINPAY_INTL_MOCK"
Write-Host "    ALLINPAY_INTL_NOTIFY_URL   = $env:ALLINPAY_INTL_NOTIFY_URL"
Write-Host "    ALLINPAY_INTL_RETURN_URL   = $env:ALLINPAY_INTL_RETURN_URL"
$pkPreview = if ($env:ALLINPAY_INTL_PRIVATE_KEY.Length -gt 32) {
    $env:ALLINPAY_INTL_PRIVATE_KEY.Substring(0, 32) + "..."
} else {
    $env:ALLINPAY_INTL_PRIVATE_KEY
}
Write-Host "    ALLINPAY_INTL_PRIVATE_KEY (first 32): $pkPreview"
$pubPreview = if ($env:ALLINPAY_INTL_PUBLIC_KEY.Length -gt 32) {
    $env:ALLINPAY_INTL_PUBLIC_KEY.Substring(0, 32) + "..."
} else {
    $env:ALLINPAY_INTL_PUBLIC_KEY
}
Write-Host "    ALLINPAY_INTL_PUBLIC_KEY  (first 32): $pubPreview"
Write-Host ""
Write-Host "Tip: start the app in this session:" -ForegroundColor Cyan
Write-Host "  java -cp `"target/classes;$((Get-Content cp.txt) -join ';')`" com.wine.WineApplication" -ForegroundColor White
Write-Host ""
