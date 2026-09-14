# TimeGate precision clock - stop/remove only the clock route.
$ErrorActionPreference = 'Continue'

$pidFile = Join-Path $PSScriptRoot '.timegate_precision_clock.pid'
$tailscale = Get-Command tailscale -ErrorAction SilentlyContinue

if ($tailscale) {
    & $tailscale.Source serve --https=443 --set-path=/api/race/clock off
}

if (Test-Path $pidFile) {
    $pidText = (Get-Content $pidFile -Raw).Trim()
    if ($pidText -match '^\d+$') {
        Stop-Process -Id ([int]$pidText) -Force -ErrorAction SilentlyContinue
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

Write-Host 'TimeGate precision clock route/service stopped.'
