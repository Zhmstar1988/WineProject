# ======================================================================
# Start Spring Boot app with Allinpay env vars for the target environment
# Usage: . scripts/start-app.ps1 -Env test    (or prod)
# ======================================================================
param(
    [ValidateSet("test", "prod")]
    [string]$Env = "test"
)

. "$PSScriptRoot\set-allinpay-env.ps1" -Env $Env

$cp = "target/classes;" + ((Get-Content "$PSScriptRoot\..\cp.txt") -join ';')
Write-Host "Starting WineApplication (profile=$Env)..." -ForegroundColor Cyan
java -cp $cp com.wine.WineApplication
