# TimeGate precision clock - Windows one-click launcher
# Starts a localhost-only clock service and mounts ONLY /api/race/clock through Tailscale Serve.
# Existing root RCC Serve routes are not reset.

$ErrorActionPreference = 'Stop'

$clockScript = Join-Path $PSScriptRoot 'timegate_precision_clock_server.js'
$pidFile = Join-Path $PSScriptRoot '.timegate_precision_clock.pid'
$logFile = Join-Path $PSScriptRoot 'timegate_precision_clock.log'
$port = 8766

if (-not (Test-Path $clockScript)) {
    throw "Clock server not found: $clockScript"
}

$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    throw 'Node.js is required. node.exe was not found in PATH.'
}

$tailscale = Get-Command tailscale -ErrorAction SilentlyContinue
if (-not $tailscale) {
    throw 'Tailscale CLI was not found in PATH.'
}

# Reuse a healthy existing clock process when possible.
$healthy = $false
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
    $healthy = ($health.ok -eq $true)
} catch {}

if (-not $healthy) {
    if (Test-Path $pidFile) { Remove-Item $pidFile -Force -ErrorAction SilentlyContinue }
    if (Test-Path $logFile) { Remove-Item $logFile -Force -ErrorAction SilentlyContinue }

    $p = Start-Process -FilePath $node.Source `
        -ArgumentList @($clockScript) `
        -WorkingDirectory $PSScriptRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError $logFile `
        -PassThru
    Set-Content -Path $pidFile -Value $p.Id -Encoding ascii

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 100
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
            if ($health.ok -eq $true) { $ready = $true; break }
        } catch {}
    }
    if (-not $ready) {
        throw "Clock service failed to start. Check $logFile"
    }
}

# Add only the clock mount. Do NOT run `tailscale serve reset` here: RCC may already own `/`.
& $tailscale.Source serve --bg --yes --https=443 --set-path=/api/race/clock "http://127.0.0.1:$port"
if ($LASTEXITCODE -ne 0) {
    throw "Tailscale Serve clock route failed (exit $LASTEXITCODE)."
}

Write-Host ''
Write-Host 'TimeGate precision clock is READY.' -ForegroundColor Green
Write-Host "Local:     http://127.0.0.1:$port/api/race/clock"
Write-Host 'Tailnet:   https://<this-node>.ts.net/api/race/clock'
Write-Host ''
Write-Host 'Current Tailscale Serve configuration:'
& $tailscale.Source serve status
