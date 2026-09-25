# ======================================================================
# Allinpay International (allinpayintl.com) key generation & config script
# Generates RSA2 key pair (SHA256withRSA) for CNP product.
#
# Usage:
#   Test env:  powershell -ExecutionPolicy Bypass -File scripts/gen-allinpay-intl-keys.ps1 -Env test
#   Prod env:  powershell -ExecutionPolicy Bypass -File scripts/gen-allinpay-intl-keys.ps1 -Env prod
#
# Output:
#   .keys/allinpay-intl/<env>/merchant-private.pem   - merchant private key (PKCS8)
#   .keys/allinpay-intl/<env>/merchant-public.pem    - merchant public key (X.509 SPKI)
#   .keys/allinpay-intl/<env>/merchant-private.b64   - Base64 single-line (for env var)
#   .keys/allinpay-intl/<env>/merchant-public.b64
#   .env.allinpay-intl.<env>                          - env vars file
# ======================================================================
param(
    [ValidateSet("test", "prod")]
    [string]$Env = "test",
    [string]$MchtId = "",
    [string]$InstNo = "",
    [string]$AllinpayIntlTestPublicKey = ""
)

$ErrorActionPreference = "Stop"

$isProd = ($Env -eq "prod")
$envLabel = if ($isProd) { "PRODUCTION" } else { "TEST" }
$mockValue = if ($isProd) { "false" } else { "true" }

# Output paths
$keysDir = Join-Path $PSScriptRoot "..\.keys\allinpay-intl\$Env"
$envFile = Join-Path $PSScriptRoot "..\.env.allinpay-intl.$Env"

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
Write-Section "1/5  Generate RSA 2048 key pair ($envLabel)"

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
$useOpenSsl = $null -ne $openssl
$privateB64 = $null
$publicB64  = $null

if ($useOpenSsl) {
    Write-Host "  Using OpenSSL: $($openssl.Source)" -ForegroundColor Green
    $tmpDir = Join-Path $env:TEMP ("allinpay-intl-keys-" + [guid]::NewGuid().ToString("N"))
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
Write-Section "2/5  Save key files"

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

$merchantPrivPem = Join-Path $keysDir "merchant-private.pem"
$merchantPubPem  = Join-Path $keysDir "merchant-public.pem"

Format-Pem $privateB64 "RSA PRIVATE KEY" | Set-Content -Path $merchantPrivPem -Encoding ascii
Format-Pem $publicB64  "PUBLIC KEY"      | Set-Content -Path $merchantPubPem  -Encoding ascii

Write-Host "  Merchant private (PEM): $merchantPrivPem" -ForegroundColor Yellow
Write-Host "  Merchant public  (PEM): $merchantPubPem"  -ForegroundColor Yellow

$merchantPrivB64 = Join-Path $keysDir "merchant-private.b64"
$merchantPubB64  = Join-Path $keysDir "merchant-public.b64"
Set-Content -Path $merchantPrivB64 -Value $privateB64 -Encoding ascii -NoNewline
Set-Content -Path $merchantPubB64  -Value $publicB64  -Encoding ascii -NoNewline

Write-Host "  Merchant private (B64): $merchantPrivB64"
Write-Host "  Merchant public  (B64): $merchantPubB64"

# Ensure .keys is not tracked by Git
$keysRootGitignore = Join-Path (Join-Path $PSScriptRoot "..\.keys") ".gitignore"
if (-not (Test-Path $keysRootGitignore)) {
    Set-Content -Path $keysRootGitignore -Value "*`n!.gitignore`n" -Encoding ascii
}
Write-Host "  .keys/.gitignore present (secrets not committed)" -ForegroundColor DarkGray

# ----------------------------------------------------------------------
# 3. Determine env-specific values
# ----------------------------------------------------------------------
Write-Section "3/5  Determine env-specific config"

if ($isProd) {
    $baseUrl  = "MUST_SET_ALLINPAY_INTL_BASE_URL"
    $allinpayIntlPublicKey = "MUST_SET_ALLINPAY_INTL_PUBLIC_KEY"
    $pubKeyNote = "PROD: replace with real Allinpay Intl production public key"
    $notifyUrl = "https://api.wine.sg/api/payment/notify"
    $returnUrl = "https://app.wine.sg/payment/result"
    if ([string]::IsNullOrWhiteSpace($MchtId)) {
        $MchtId = "MUST_SET_ALLINPAY_INTL_MCHT_ID"
    }
} else {
    $baseUrl  = "https://test.allinpayhk.com/gateway"
    $allinpayIntlPublicKey = if ([string]::IsNullOrWhiteSpace($AllinpayIntlTestPublicKey)) {
        "MUST_SET_ALLINPAY_INTL_PUBLIC_KEY"
    } else {
        $AllinpayIntlTestPublicKey
    }
    $pubKeyNote = "TEST: set to Allinpay Intl test public key (obtain from portal)"
    $notifyUrl = "http://localhost:8080/api/payment/notify"
    $returnUrl = "http://localhost:8080/payment/result"
    if ([string]::IsNullOrWhiteSpace($MchtId)) {
        $MchtId = "MOCK_MCHT_ID_000"
    }
}

Write-Host "  Base URL:      $baseUrl"
Write-Host "  MchtId:        $MchtId"
Write-Host "  InstNo:        $(if($InstNo){$InstNo}else{'(empty)'})"
Write-Host "  Notify URL:    $notifyUrl"
Write-Host "  Return URL:    $returnUrl"
Write-Host "  Mock:           $mockValue"
Write-Host "  Allinpay Intl public key: $pubKeyNote"

# ----------------------------------------------------------------------
# 4. Generate .env.allinpay-intl.<env>
# ----------------------------------------------------------------------
Write-Section "4/5  Generate env-var file (.env.allinpay-intl.$Env)"

$envLines = New-Object System.Collections.Generic.List[string]
$envLines.Add("# ============================================================")
$envLines.Add("# Allinpay International (allinpayintl.com) $envLabel config")
$envLines.Add("# Generated by gen-allinpay-intl-keys.ps1 -Env $Env")
$envLines.Add("# Load: . scripts/set-allinpay-intl-env.ps1 -Env $Env")
$envLines.Add("# ============================================================")
$envLines.Add("")
$envLines.Add("# Spring profile")
$envLines.Add("SPRING_PROFILES_ACTIVE=$Env")
$envLines.Add("")
$envLines.Add("# ---- Allinpay International CNP ----")
$envLines.Add("# Gateway base URL (test: https://test.allinpayhk.com/gateway)")
$envLines.Add("ALLINPAY_INTL_BASE_URL=$baseUrl")
$envLines.Add("# Merchant ID (15 digits, issued by Allinpay Intl)")
$envLines.Add("ALLINPAY_INTL_MCHT_ID=$MchtId")
$envLines.Add("# Institution number (8 digits, issued by Allinpay Intl)")
$envLines.Add("ALLINPAY_INTL_INST_NO=$InstNo")
$envLines.Add("# Currency: SGD for Singapore market")
$envLines.Add("ALLINPAY_INTL_CURRENCY=SGD")
$envLines.Add("# Mock mode (true=test without real gateway, false=production)")
$envLines.Add("ALLINPAY_INTL_MOCK=$mockValue")
$envLines.Add("")
$envLines.Add("# ---- RSA2 Keys ----")
$envLines.Add("# Merchant private key (PKCS8 Base64) - for request signing (SHA256withRSA)")
$envLines.Add("ALLINPAY_INTL_PRIVATE_KEY=$privateB64")
$envLines.Add("# Allinpay Intl public key (X.509 Base64) - for response/notify verification")
$envLines.Add("# $pubKeyNote")
$envLines.Add("ALLINPAY_INTL_PUBLIC_KEY=$allinpayIntlPublicKey")
$envLines.Add("")
$envLines.Add("# ---- Callback URLs ----")
$envLines.Add("# Async notify URL (must be public HTTPS in prod)")
$envLines.Add("ALLINPAY_INTL_NOTIFY_URL=$notifyUrl")
$envLines.Add("# Frontend redirect URL after payment")
$envLines.Add("ALLINPAY_INTL_RETURN_URL=$returnUrl")
$envLines.Add("")
$envLines.Add("# ---- Transaction types (per Allinpay Intl spec Appendix A) ----")
$envLines.Add("ALLINPAY_INTL_TRANS_TYPE_PURCHASE=Purchase")
$envLines.Add("ALLINPAY_INTL_TRANS_TYPE_QUERY=Query")
$envLines.Add("ALLINPAY_INTL_TRANS_TYPE_REFUND=Refund")

$envLines | Set-Content -Path $envFile -Encoding ascii
Write-Host "  Env file: $envFile" -ForegroundColor Green

# ----------------------------------------------------------------------
# 5. Public key fingerprint & summary
# ----------------------------------------------------------------------
Write-Section "5/5  Public key fingerprint & next steps"

$spkiBytes = [Convert]::FromBase64String($publicB64)
$pubSha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $fingerprint = [Convert]::ToBase64String($pubSha256.ComputeHash($spkiBytes))
}
finally {
    $pubSha256.Dispose()
}

$portalEnv = if ($isProd) { "production" } else { "test" }
Write-Host ""
Write-Host "  [Merchant public key - upload to Allinpay Intl $portalEnv portal]" -ForegroundColor Yellow
Write-Host "  Fingerprint (SHA256 B64): $fingerprint" -ForegroundColor White
Write-Host "  Public key (B64 single line):" -ForegroundColor White
Write-Host "  $publicB64" -ForegroundColor DarkCyan
Write-Host ""

if ($isProd) {
    Write-Host "  [Allinpay Intl production public key]" -ForegroundColor Yellow
    Write-Host "  Placeholder set; you MUST obtain & set ALLINPAY_INTL_PUBLIC_KEY" -ForegroundColor Red
    Write-Host "  and ALLINPAY_INTL_BASE_URL from Allinpay Intl portal" -ForegroundColor Red
} else {
    Write-Host "  [Allinpay Intl test public key]" -ForegroundColor Yellow
    if ([string]::IsNullOrWhiteSpace($AllinpayIntlTestPublicKey)) {
        Write-Host "  Not provided; mock mode is enabled so testing works without it" -ForegroundColor DarkYellow
        Write-Host "  Obtain from Allinpay Intl test portal when ready" -ForegroundColor DarkYellow
    } else {
        Write-Host "  Provided via -AllinpayIntlTestPublicKey parameter" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host ("=" * 64) -ForegroundColor Green
Write-Host "  Done! ($envLabel)" -ForegroundColor Green
Write-Host ("=" * 64) -ForegroundColor Green
Write-Host ""
Write-Host "  Next steps:" -ForegroundColor Cyan
Write-Host "  1. Upload merchant public key to Allinpay Intl $portalEnv portal"
Write-Host "     (Merchant Center -> API Config -> Merchant Public Key)"
Write-Host "  2. Obtain from Allinpay Intl portal:"
Write-Host "     - MchtId (merchant ID, 15 digits)"
Write-Host "     - InstNo (institution number, 8 digits)"
if ($isProd) {
    Write-Host "     - Production base URL"
    Write-Host "     - Allinpay Intl production public key"
}
Write-Host "  3. Edit .env.allinpay-intl.$Env to fill in the obtained values"
Write-Host "  4. Load env vars: . scripts/set-allinpay-intl-env.ps1 -Env $Env"
Write-Host "  5. Start app: java -cp ... com.wine.WineApplication"
Write-Host ""
Write-Host "  Verify with mock mode first (test env):" -ForegroundColor DarkGray
Write-Host "    SPRING_PROFILES_ACTIVE=test -> mock=true, no real gateway calls" -ForegroundColor DarkGray
Write-Host ""
