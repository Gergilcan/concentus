# Writes a .env for docker-compose, with the encryption key already generated.
#
# The PowerShell twin of setup-env.sh, because this repo is developed on Windows and openssl is
# not there by default. .NET's RandomNumberGenerator is the same quality of randomness.
#
#   .\scripts\setup-env.ps1
#
# Existing values are never overwritten -- re-running fills in what is missing and leaves the rest
# alone, so it is safe on a .env you have already edited.

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (-not (Test-Path .env)) {
    Copy-Item .env.example .env
    Write-Host "Created .env from .env.example"
}

# UTF8 without BOM: docker compose reads .env byte-for-byte and a BOM would end up inside the
# first variable's name, which fails in a way that points nowhere useful.
$encoding = New-Object System.Text.UTF8Encoding $false

function Get-EnvValue([string] $key) {
    $line = Get-Content .env | Where-Object { $_ -match "^$([regex]::Escape($key))=" } | Select-Object -Last 1
    if ($null -eq $line) { return '' }
    return $line.Substring($line.IndexOf('=') + 1)
}

function Set-EnvValue([string] $key, [string] $value) {
    $lines = Get-Content .env
    $pattern = "^$([regex]::Escape($key))="
    if ($lines -match $pattern) {
        # -replace on the whole line, not on the value: a base64 key contains / and + and would
        # otherwise be mangled by regex substitution.
        $lines = $lines | ForEach-Object { if ($_ -match $pattern) { "$key=$value" } else { $_ } }
    } else {
        $lines += "$key=$value"
    }
    [System.IO.File]::WriteAllLines((Resolve-Path .env), $lines, $encoding)
}

if ([string]::IsNullOrWhiteSpace((Get-EnvValue 'CONCENTUS_SECRET_KEY'))) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    Set-EnvValue 'CONCENTUS_SECRET_KEY' ([Convert]::ToBase64String($bytes))
    Write-Host "Generated CONCENTUS_SECRET_KEY. Keep it with the database -- changing it makes"
    Write-Host "every stored credential unreadable."
} else {
    Write-Host "CONCENTUS_SECRET_KEY already set -- left alone."
}

$missing = @()
if ([string]::IsNullOrWhiteSpace((Get-EnvValue 'CLAUDE_CODE_OAUTH_TOKEN'))) {
    $missing += 'CLAUDE_CODE_OAUTH_TOKEN  (run: claude setup-token)'
}
if ([string]::IsNullOrWhiteSpace((Get-EnvValue 'CONCENTUS_ADMIN_EMAIL'))) {
    $missing += 'CONCENTUS_ADMIN_EMAIL    (the account you sign in with)'
}

Write-Host ''
if ($missing.Count -gt 0) {
    Write-Host 'Still to fill in, in .env:'
    $missing | ForEach-Object { Write-Host "  - $_" }
    Write-Host ''
    Write-Host 'Then: docker compose up --build'
} else {
    Write-Host 'Ready. Run: docker compose up --build'
}
