# TimeGate precision clock - Windows one-click launcher (Node.js NOT required)
# Starts a localhost-only PowerShell clock service and mounts ONLY /api/race/clock through Tailscale Serve.
# Existing root RCC Serve routes are not reset.

$ErrorActionPreference = 'Stop'

$clockScript = Join-Path $PSScriptRoot 'timegate_precision_clock_server.ps1'
$pidFile = Join-Path $PSScriptRoot '.timegate_precision_clock.pid'
$outLog = Join-Path $PSScriptRoot 'timegate_precision_clock.out.log'
$errLog = Join-Path $PSScriptRoot 'timegate_precision_clock.err.log'
$port = 8766
$requiredVersion = 4

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

function Stop-OldClockProcess {
    if (Test-Path $pidFile) {
        $pidText = (Get-Content $pidFile -Raw).Trim()
        if ($pidText -match '^\d+$') {
            Stop-Process -Id ([int]$pidText) -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 250
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
}

# Reuse only a fully working current-version clock process.
$healthy = $false
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
    $clock = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/race/clock?selftest=1" -TimeoutSec 1
    $healthy = ($health.ok -eq $true -and [int]$health.version -ge $requiredVersion -and [int64]$clock.server_time_ms -gt 1000000000000)
} catch {}

if (-not $healthy) {
    Stop-OldClockProcess
    Remove-Item $outLog,$errLog -Force -ErrorAction SilentlyContinue

    $p = Start-Process -FilePath $psExe `
        -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $clockScript + '"'),'-Port',"$port") `
        -WorkingDirectory $PSScriptRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -PassThru
    Set-Content -Path $pidFile -Value $p.Id -Encoding ascii

    $ready = $false
    $clockCheck = $null
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Milliseconds 100
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
            $clockCheck = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/race/clock?selftest=1" -TimeoutSec 1
            if ($health.ok -eq $true -and [int]$health.version -ge $requiredVersion -and [int64]$clockCheck.server_time_ms -gt 1000000000000) {
                $ready = $true
                break
            }
        } catch {}
    }
    if (-not $ready) {
        throw "Clock service failed its real /api/race/clock self-test. Check $outLog and $errLog"
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

$clockCheck = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/race/clock?finalcheck=1" -TimeoutSec 2

Write-Host ''
Write-Host 'TimeGate precision clock is READY.' -ForegroundColor Green
Write-Host "Clock version: $($clockCheck.version)"
Write-Host "Server time ms: $($clockCheck.server_time_ms)"
Write-Host "Processing ms: $($clockCheck.processing_ms)"
Write-Host "Local:     http://127.0.0.1:$port/api/race/clock"
Write-Host 'Tailnet:   https://<active-rcc-node>.ts.net/api/race/clock'
Write-Host ''
Write-Host 'Current Tailscale Serve configuration:'
& $tailscaleExe serve status
