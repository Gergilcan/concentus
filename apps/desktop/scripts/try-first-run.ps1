# Launches the app as if on a machine that has never seen Claude Code, so the first-run page can
# be worked on without uninstalling anything.
#
#   pwsh -File scripts/try-first-run.ps1            # no CLI, no login  -> both warnings
#   pwsh -File scripts/try-first-run.ps1 -WithLogin # CLI + login present -> skipped entirely
#
# Nothing real is touched: HOME, USERPROFILE and APPDATA all point at a throwaway folder, so the
# app gets its own data directory, its own database and its own keyring entry, and your actual
# Claude sign-in is neither read nor modified.
param(
    # Plants a stub CLI and a login file, to check the page correctly stays out of the way.
    [switch]$WithLogin,
    # Start from an empty profile. Off by default so repeat runs are fast — a fresh one pays for
    # initdb again.
    [switch]$Fresh
)

$ErrorActionPreference = 'Stop'
$desktop = Split-Path -Parent $PSScriptRoot
$sandbox = Join-Path $env:TEMP 'concentus-first-run'

if ($Fresh -and (Test-Path $sandbox)) {
    Write-Host "Clearing $sandbox"
    Remove-Item -Recurse -Force $sandbox
}
New-Item -ItemType Directory -Force -Path (Join-Path $sandbox 'AppData\Roaming') | Out-Null

if ($WithLogin) {
    # The two things detection looks for: a login file in the home directory, and the CLI in one of
    # the locations it installs itself into. The stub is never executed — only its existence is
    # checked — so it does not need to be a real binary.
    New-Item -ItemType Directory -Force -Path (Join-Path $sandbox '.local\bin') | Out-Null
    Set-Content -Path (Join-Path $sandbox '.claude.json') -Value '{}' -Encoding utf8
    Set-Content -Path (Join-Path $sandbox '.local\bin\claude.exe') -Value 'stub' -Encoding utf8
    Write-Host 'Simulating a machine that IS signed in — expect no welcome page.'
} else {
    Write-Host 'Simulating a machine with no Claude Code — expect the welcome page.'
}

$env:USERPROFILE = $sandbox
$env:HOME = $sandbox
$env:APPDATA = Join-Path $sandbox 'AppData\Roaming'
# Drop the real CLI's directory from PATH too, or discovery finds your actual installation and the
# simulation is only half true.
$env:PATH = (($env:PATH -split ';') | Where-Object { $_ -notlike '*\.local\bin*' }) -join ';'
# VS Code's extension host exports this, and it makes Electron run as plain Node — which fails with
# "Cannot read properties of undefined (reading 'requestSingleInstanceLock')".
$env:ELECTRON_RUN_AS_NODE = $null

Write-Host "Data directory: $($env:APPDATA)\Concentus"
Write-Host "Logs:           $($env:APPDATA)\Concentus\logs`n"

Set-Location $desktop
& npm exec -- electron .
