# TimeGate precision clock - Windows one-click launcher (Node.js NOT required)
# Starts a localhost-only PowerShell clock service and mounts ONLY /api/race/clock through Tailscale Serve.
# Existing root RCC Serve routes are not reset.

$ErrorActionPreference = 'Stop'

$clockScript = Join-Path $PSScriptRoot 'timegate_precision_clock_server.ps1'
$pidFile = Join-Path $PSScriptRoot '.timegate_precision_clock.pid'
$outLog = Join-Path $PSScriptRoot 'timegate_precision_clock.out.log'
$errLog = Join-Path $PSScriptRoot 'timegate_precision_clock.err.log'
$port = 8766

if (-not (Test-Path $clockScript)) {
    throw "Clock server not found: $clockScript"
}

$psExe = (Get-Command powershell.exe -ErrorAction Stop).Source

$tailscaleCmd = Get-Command tailscale.exe -ErrorAction SilentlyContinue
$tailscaleExe = if ($tailscaleCmd) { $tailscaleCmd.Source } else { $null }
if (-not $tailscaleExe) {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Tailscale\tailscale.exe'),
        (Join-Path $env:LOCALAPPDATA 'Tailscale\tailscale.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    $tailscaleExe = $candidates | Select-Object -First 1
}
if (-not $tailscaleExe) {
    throw 'Tailscale CLI was not found. Tailscale must be installed on the active RCC server PC.'
}

# Reuse a healthy existing clock process when possible.
$healthy = $false
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
    $healthy = ($health.ok -eq $true)
} catch {}

if (-not $healthy) {
    Remove-Item $pidFile,$outLog,$errLog -Force -ErrorAction SilentlyContinue

    $p = Start-Process -FilePath $psExe `
        -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $clockScript + '"'),'-Port',"$port") `
        -WorkingDirectory $PSScriptRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -PassThru
    Set-Content -Path $pidFile -Value $p.Id -Encoding ascii

    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 100
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
            if ($health.ok -eq $true) { $ready = $true; break }
        } catch {}
    }
    if (-not $ready) {
        throw "Clock service failed to start. Check $outLog and $errLog"
    }
}

# Add only the clock mount. Never reset the existing RCC root route.
$url = "http://127.0.0.1:$port"
$serveOk = $false
$variants = @(
    @('serve','--bg','--yes','--https=443','--set-path=/api/race/clock',$url),
    @('serve','--bg','--yes','--set-path=/api/race/clock',$url),
    @('serve','--bg','--set-path=/api/race/clock',$url)
)
foreach ($args in $variants) {
    & $tailscaleExe @args
    if ($LASTEXITCODE -eq 0) { $serveOk = $true; break }
}
if (-not $serveOk) {
    throw 'Tailscale Serve clock route failed. Existing RCC routes were not reset.'
}

Write-Host ''
Write-Host 'TimeGate precision clock is READY.' -ForegroundColor Green
Write-Host "Local:     http://127.0.0.1:$port/api/race/clock"
Write-Host 'Tailnet:   https://<active-rcc-node>.ts.net/api/race/clock'
Write-Host ''
Write-Host 'Current Tailscale Serve configuration:'
& $tailscaleExe serve status
