# ======================================================================
# Allinpay (TongLian) key generation & configuration script
# Supports both test (sandbox) and production environments.
#
# Usage:
#   Test env:  powershell -ExecutionPolicy Bypass -File scripts/gen-allinpay-keys.ps1 -Env test
#   Prod env:  powershell -ExecutionPolicy Bypass -File scripts/gen-allinpay-keys.ps1 -Env prod
#
# Output:
#   .keys/<env>/allinpay-merchant-private.pem   - merchant private key (upload PUBLIC to Allinpay)
#   .keys/<env>/allinpay-merchant-public.pem    - merchant public key
#   .env.allinpay.<env>                         - env vars for the target env
# ======================================================================
param(
    [ValidateSet("test", "prod")]
    [string]$Env = "test",
    [string]$OrgId  = "00000000",
    [string]$CusId  = "000000000000000",
    [string]$AppId  = "wine-app",
    # Allinpay sandbox public key (fixed, only used for test env)
    [string]$AllinpaySandboxPublicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDYXfu4b7xgDSmEGQpQ8Sn3RzFgl5CE4gL4TbYrND4FtCYOrvbgLijkdFgIrVVWi2hUW4K0PwBsmlYhXcbR+JSmqv9zviVXZiym0lK3glJGVCN86r9EPvNTusZZPm40TOEKMVENSYaUjCxZ7JzeZDfQ4WCeQQr2xirqn6LdJjpZ5wIDAQAB"
)

$ErrorActionPreference = "Stop"

$isProd = ($Env -eq "prod")
$envLabel = if ($isProd) { "PRODUCTION" } else { "TEST (sandbox)" }
$sandboxValue = if ($isProd) { "false" } else { "true" }

# Output paths
$keysDir = Join-Path $PSScriptRoot "..\.keys\$Env"
$envFile = Join-Path $PSScriptRoot "..\.env.allinpay.$Env"

function Write-Section($msg) {
    Write-Host ""
    Write-Host ("=" * 64) -ForegroundColor Cyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host ("=" * 64) -ForegroundColor Cyan
}

# ----------------------------------------------------------------------
# DER helpers (fallback when OpenSSL is absent)
# ----------------------------------------------------------------------
function Der-Integer([byte[]]$value) {
    $i = 0
    while ($i -lt $value.Length - 1 -and $value[$i] -eq 0) { $i++ }
    $trimmed = $value[$i..($value.Length - 1)]
    if (($trimmed[0] -band 0x80) -ne 0) {
        $trimmed = ,[byte]0x00 + $trimmed
    }
    $len = Der-Length $trimmed.Length
    return ,[byte]0x02 + $len + $trimmed
}

function Der-Length([int]$len) {
    if ($len -lt 0x80) { return ,[byte]$len }
    $bytes = @()
    $n = $len
    while ($n -gt 0) { $bytes = ,[byte]($n -band 0xFF) + $bytes; $n = $n -shr 8 }
    return ,[byte](0x80 -bor $bytes.Length) + $bytes
}

function Der-OctetString([byte[]]$value) {
    $len = Der-Length $value.Length
    return ,[byte]0x04 + $len + $value
}

function Der-BitString([byte[]]$value) {
    $inner = ,[byte]0x00 + $value
    $len = Der-Length $inner.Length
    return ,[byte]0x03 + $len + $inner
}

function Der-Sequence([byte[][]]$parts) {
    $content = @()
    foreach ($p in $parts) { $content += $p }
    $len = Der-Length $content.Length
    return ,[byte]0x30 + $len + $content
}

function Build-Pkcs1PrivateKey($p) {
    $parts = @()
    $parts += ,(Der-Integer ([byte[]]@(0x00)))
    $parts += ,(Der-Integer $p.Modulus)
    $parts += ,(Der-Integer $p.Exponent)
    $parts += ,(Der-Integer $p.D)
    $parts += ,(Der-Integer $p.P)
    $parts += ,(Der-Integer $p.Q)
    $parts += ,(Der-Integer $p.DP)
    $parts += ,(Der-Integer $p.DQ)
    $parts += ,(Der-Integer $p.InverseQ)
    return Der-Sequence $parts
}

function Build-Pkcs1PublicKey($p) {
    $parts = @()
    $parts += ,(Der-Integer $p.Modulus)
    $parts += ,(Der-Integer $p.Exponent)
    return Der-Sequence $parts
}

function Build-Pkcs8PrivateKey($p) {
    $pkcs1 = Build-Pkcs1PrivateKey $p
    $oid = [byte[]]@(0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01)
    $nullParam = [byte[]]@(0x05, 0x00)
    $algId = Der-Sequence @($oid, $nullParam)
    $inner = @()
    $inner += (Der-Integer ([byte[]]@(0x00)))
    $inner += $algId
    $inner += (Der-OctetString $pkcs1)
    return Der-Sequence $inner
}

function Build-SpkiPublicKey($p) {
    $pkcs1 = Build-Pkcs1PublicKey $p
    $oid = [byte[]]@(0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01)
    $nullParam = [byte[]]@(0x05, 0x00)
    $algId = Der-Sequence @($oid, $nullParam)
    $inner = @()
    $inner += $algId
    $inner += (Der-BitString $pkcs1)
    return Der-Sequence $inner
}

# ----------------------------------------------------------------------
# 1. Generate RSA 2048 key pair
# ----------------------------------------------------------------------
Write-Section "1/4  Generate RSA 2048 key pair ($envLabel)"

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
$useOpenSsl = $null -ne $openssl
$privateB64 = $null
$publicB64  = $null

if ($useOpenSsl) {
    Write-Host "  Using OpenSSL: $($openssl.Source)" -ForegroundColor Green
    $tmpDir = Join-Path $env:TEMP ("allinpay-keys-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmpDir | Out-Null
    try {
        $privPem = Join-Path $tmpDir "priv.pem"
        $privDer = Join-Path $tmpDir "priv.der"
        $pubDer  = Join-Path $tmpDir "pub.der"

        cmd /c "openssl genrsa -out `"$privPem`" 2048 2>nul"
        cmd /c "openssl pkcs8 -topk8 -nocrypt -in `"$privPem`" -outform DER -out `"$privDer`" 2>nul"
        cmd /c "openssl rsa -in `"$privPem`" -pubout -outform DER -out `"$pubDer`" 2>nul"

        $privateB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($privDer))
        $publicB64  = [Convert]::ToBase64String([IO.File]::ReadAllBytes($pubDer))
    }
    finally {
        Remove-Item $tmpDir -Recurse -Force
    }
}
else {
    Write-Host "  OpenSSL not found, using .NET manual DER construction" -ForegroundColor Yellow
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    try {
        $p = $rsa.ExportParameters($true)
        $pkcs8Der = Build-Pkcs8PrivateKey $p
        $spkiDer  = Build-SpkiPublicKey $p
        $privateB64 = [Convert]::ToBase64String($pkcs8Der)
        $publicB64  = [Convert]::ToBase64String($spkiDer)
    }
    finally {
        $rsa.Dispose()
    }
}

Write-Host "  Private key Base64 length: $($privateB64.Length)" -ForegroundColor Green
Write-Host "  Public key Base64 length:  $($publicB64.Length)"  -ForegroundColor Green

# ----------------------------------------------------------------------
# 2. Save key files
# ----------------------------------------------------------------------
Write-Section "2/4  Save key files"

if (-not (Test-Path $keysDir)) {
    New-Item -ItemType Directory -Path $keysDir -Force | Out-Null
    Write-Host "  Created dir: $keysDir" -ForegroundColor DarkGray
}

function Format-Pem($base64, $label) {
    $lines = @()
    $lines += "-----BEGIN $label-----"
    for ($i = 0; $i -lt $base64.Length; $i += 64) {
        $len = [Math]::Min(64, $base64.Length - $i)
        $lines += $base64.Substring($i, $len)
    }
    $lines += "-----END $label-----"
    return ($lines -join "`n")
}

$merchantPrivKeyPem = Join-Path $keysDir "allinpay-merchant-private.pem"
$merchantPubKeyPem  = Join-Path $keysDir "allinpay-merchant-public.pem"

Format-Pem $privateB64 "RSA PRIVATE KEY" | Set-Content -Path $merchantPrivKeyPem -Encoding ascii
Format-Pem $publicB64  "PUBLIC KEY"      | Set-Content -Path $merchantPubKeyPem  -Encoding ascii

Write-Host "  Merchant private (PEM): $merchantPrivKeyPem" -ForegroundColor Yellow
Write-Host "  Merchant public  (PEM): $merchantPubKeyPem"  -ForegroundColor Yellow

$merchantPrivKeyB64 = Join-Path $keysDir "allinpay-merchant-private.b64"
$merchantPubKeyB64  = Join-Path $keysDir "allinpay-merchant-public.b64"
Set-Content -Path $merchantPrivKeyB64 -Value $privateB64 -Encoding ascii -NoNewline
Set-Content -Path $merchantPubKeyB64  -Value $publicB64  -Encoding ascii -NoNewline

Write-Host "  Merchant private (B64): $merchantPrivKeyB64"
Write-Host "  Merchant public  (B64): $merchantPubKeyB64"

# Ensure .keys is not tracked by Git
$gitignore = Join-Path (Join-Path $PSScriptRoot "..\.keys") ".gitignore"
if (-not (Test-Path $gitignore)) {
    Set-Content -Path $gitignore -Value "*`n!.gitignore`n" -Encoding ascii
}
Write-Host "  .keys/.gitignore present (secrets not committed)" -ForegroundColor DarkGray

# ----------------------------------------------------------------------
# 3. Generate .env.allinpay.<env>
# ----------------------------------------------------------------------
Write-Section "3/4  Generate env-var file (.env.allinpay.$Env)"

# Determine Allinpay public key for this env
if ($isProd) {
    $allinpayPublicKey = "MUST_SET_ALLINPAY_PUBLIC_KEY"
    $allinpayPubNote = "PROD: replace with real Allinpay production public key"
}
else {
    $allinpayPublicKey = $AllinpaySandboxPublicKey
    $allinpayPubNote = "TEST: Allinpay sandbox public key (fixed)"
}

# Notify/return URLs differ by env
if ($isProd) {
    $notifyUrl = "https://api.wine.sg/api/payment/notify"
    $returnUrl = "https://app.wine.sg/payment/result"
}
else {
    $notifyUrl = "http://localhost:8080/api/payment/notify"
    $returnUrl = "http://localhost:8080/payment/result"
}

$envLines = New-Object System.Collections.Generic.List[string]
$envLines.Add("# Allinpay $envLabel config - generated by gen-allinpay-keys.ps1 -Env $Env")
$envLines.Add("# Activate profile: `$env:SPRING_PROFILES_ACTIVE='$Env'")
$envLines.Add("# Load: . scripts/set-allinpay-env.ps1 -Env $Env")
$envLines.Add("")
$envLines.Add("# Spring profile (dev=test, prod=production)")
$envLines.Add("SPRING_PROFILES_ACTIVE=$Env")
$envLines.Add("# Org id (issued by Allinpay)")
$envLines.Add("ALLINPAY_ORG_ID=$OrgId")
$envLines.Add("# Primary merchant id (issued by Allinpay; sub-merchant uses order cusid)")
$envLines.Add("ALLINPAY_CUS_ID=$CusId")
$envLines.Add("# App id (issued by Allinpay)")
$envLines.Add("ALLINPAY_APP_ID=$AppId")
$envLines.Add("# Merchant private key (PKCS8 Base64) - request signing")
$envLines.Add("ALLINPAY_PRIVATE_KEY=$privateB64")
if ($isProd) {
    $envLines.Add("# Allinpay production public key (X.509 Base64) - MUST set to real value")
}
else {
    $envLines.Add("# Allinpay sandbox public key (X.509 Base64) - fixed for test env")
}
$envLines.Add("ALLINPAY_PUBLIC_KEY=$allinpayPublicKey")
$envLines.Add("# Async notify url (must be public)")
$envLines.Add("ALLINPAY_NOTIFY_URL=$notifyUrl")
$envLines.Add("# Frontend redirect url after payment")
$envLines.Add("ALLINPAY_RETURN_URL=$returnUrl")

$envLines | Set-Content -Path $envFile -Encoding ascii
Write-Host "  Env file: $envFile" -ForegroundColor Green

# ----------------------------------------------------------------------
# 4. Public key fingerprint
# ----------------------------------------------------------------------
Write-Section "4/4  Public key fingerprint"

$spkiBytes = [Convert]::FromBase64String($publicB64)
$pubSha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $fingerprint = [Convert]::ToBase64String($pubSha256.ComputeHash($spkiBytes))
}
finally {
    $pubSha256.Dispose()
}

$portalEnv = if ($isProd) { "production" } else { "sandbox" }
Write-Host ""
Write-Host "  [Merchant public key - upload to Allinpay $portalEnv portal]" -ForegroundColor Yellow
Write-Host "  Fingerprint (SHA256 B64): $fingerprint" -ForegroundColor White
Write-Host "  Public key (B64 single line):" -ForegroundColor White
Write-Host "  $publicB64" -ForegroundColor DarkCyan
Write-Host ""
if ($isProd) {
    Write-Host "  [Allinpay production public key]" -ForegroundColor Yellow
    Write-Host "  Placeholder set; you MUST replace ALLINPAY_PUBLIC_KEY in .env.allinpay.prod" -ForegroundColor Red
}
else {
    Write-Host "  [Allinpay sandbox public key - built-in, no action needed]" -ForegroundColor Yellow
}
Write-Host ""

Write-Host ""
Write-Host ("=" * 64) -ForegroundColor Green
Write-Host "  Done! ($envLabel)" -ForegroundColor Green
Write-Host ("=" * 64) -ForegroundColor Green
Write-Host ""
Write-Host "  Next steps:" -ForegroundColor Cyan
Write-Host "  1. Upload merchant public key to Allinpay $portalEnv portal"
Write-Host "     (Merchant Center -> API Config -> Merchant Public Key)"
Write-Host "  2. Replace orgid/cusid/appid in .env.allinpay.$Env with real values"
if ($isProd) {
    Write-Host "  3. Set ALLINPAY_PUBLIC_KEY to Allinpay production public key"
}
Write-Host "  3. Load env vars: . scripts/set-allinpay-env.ps1 -Env $Env"
Write-Host "  4. Start app: . scripts/start-app.ps1 -Env $Env"
Write-Host ""
