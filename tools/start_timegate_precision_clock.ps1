# TimeGate precision clock v5 - Windows one-click launcher (Node.js NOT required)
# v5 deliberately uses a fresh local port (8767) so any old hidden v3/v4 process on 8766 cannot block startup.

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

# Stop only a v5 process launched from THIS folder.
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
$clockCheck = $null
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

# Replace only the /api/race/clock mount; RCC root '/' remains untouched.
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
if (-not $serveOk) { throw 'Tailscale Serve clock route failed.' }

Write-Host ''
Write-Host 'TimeGate precision clock v5 is READY.' -ForegroundColor Green
Write-Host "Clock version: $($clockCheck.version)"
Write-Host "Server time ms: $($clockCheck.server_time_ms)"
Write-Host "Processing ms: $($clockCheck.processing_ms)"
Write-Host "Local: http://127.0.0.1:$port/api/race/clock"
Write-Host ''
Write-Host 'Current Tailscale Serve configuration:'
& $tailscaleExe serve status
