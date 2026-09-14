# TimeGate precision clock v6 - Windows one-click launcher (Node.js NOT required)
# Uses Tailscale FUNNEL because the TimeGate phone app accesses rcc-home without joining the tailnet.
# Keeps RCC root '/' on 127.0.0.1:8000 and exposes /api/race/clock on 127.0.0.1:8767.

$ErrorActionPreference = 'Stop'

$clockScript = Join-Path $PSScriptRoot 'timegate_precision_clock_server.ps1'
$pidFile = Join-Path $PSScriptRoot '.timegate_precision_clock.pid'
$outLog = Join-Path $PSScriptRoot 'timegate_precision_clock.out.log'
$errLog = Join-Path $PSScriptRoot 'timegate_precision_clock.err.log'
$port = 8767
$requiredVersion = 5

if (-not (Test-Path $clockScript)) { throw "Clock server not found: $clockScript" }
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
if (-not $tailscaleExe) { throw 'Tailscale CLI was not found.' }

# Reuse a healthy v5 clock on 8767, otherwise start one from this folder.
$healthy = $false
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
    $clockCheck = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/race/clock?selftest=1" -TimeoutSec 1
    $healthy = ($health.ok -eq $true -and [int]$health.version -eq $requiredVersion -and [int64]$clockCheck.server_time_ms -gt 1000000000000)
} catch {}

if (-not $healthy) {
    if (Test-Path $pidFile) {
        $pidText = (Get-Content $pidFile -Raw).Trim()
        if ($pidText -match '^\d+$') { Stop-Process -Id ([int]$pidText) -Force -ErrorAction SilentlyContinue }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 200
    }
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
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Milliseconds 100
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/health" -TimeoutSec 1
            $clockCheck = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/race/clock?selftest=1" -TimeoutSec 1
            if ($health.ok -eq $true -and [int]$health.version -eq $requiredVersion -and [int64]$clockCheck.server_time_ms -gt 1000000000000) {
                $ready = $true
                break
            }
        } catch {}
    }
    if (-not $ready) {
        $err = if (Test-Path $errLog) { (Get-Content $errLog -Raw -ErrorAction SilentlyContinue) } else { '' }
        throw "Clock v5 failed self-test on port $port. $err"
    }
}

# IMPORTANT: configure port 443 as FUNNEL, not Serve. The TimeGate phone may be outside the tailnet.
$rootTarget = 'http://127.0.0.1:8000'
$clockTarget = "http://127.0.0.1:$port"

& $tailscaleExe funnel --bg --yes --https=443 $rootTarget
if ($LASTEXITCODE -ne 0) { throw 'Tailscale Funnel root route failed.' }

& $tailscaleExe funnel --bg --yes --https=443 --set-path=/api/race/clock $clockTarget
if ($LASTEXITCODE -ne 0) { throw 'Tailscale Funnel clock route failed.' }

Write-Host ''
Write-Host 'TimeGate public Funnel + precision clock is READY.' -ForegroundColor Green
Write-Host "Clock version: $($clockCheck.version)"
Write-Host "Server time ms: $($clockCheck.server_time_ms)"
Write-Host "Processing ms: $($clockCheck.processing_ms)"
Write-Host "Local clock: http://127.0.0.1:$port/api/race/clock"
Write-Host ''
Write-Host 'Current Tailscale Funnel configuration:'
& $tailscaleExe funnel status
