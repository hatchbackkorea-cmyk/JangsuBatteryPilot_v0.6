param(
    [int]$Port = 8766
)

$ErrorActionPreference = 'Stop'

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
Write-Host "[TimeGate Clock] listening on http://127.0.0.1:$Port"

# Compatible with Windows PowerShell 5.1 / older .NET Framework builds.
$EpochUtc = [DateTime]::SpecifyKind([DateTime]'1970-01-01 00:00:00', [DateTimeKind]::Utc)
function Now-EpochMs {
    return [int64][math]::Floor(([DateTime]::UtcNow - $script:EpochUtc).TotalMilliseconds)
}

function Qpc-Now {
    return [System.Diagnostics.Stopwatch]::GetTimestamp()
}

function Qpc-ToMs([long]$deltaTicks) {
    return ($deltaTicks * 1000.0) / [System.Diagnostics.Stopwatch]::Frequency
}

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $client.NoDelay = $true
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 4096, $true)

            $receiveQpc = Qpc-Now
            $receiveWallMs = Now-EpochMs

            $requestLine = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($requestLine)) {
                $client.Close()
                continue
            }

            while ($true) {
                $line = $reader.ReadLine()
                if ($null -eq $line -or $line.Length -eq 0) { break }
            }

            $parts = $requestLine.Split(' ')
            $method = if ($parts.Length -gt 0) { $parts[0] } else { '' }
            $rawPath = if ($parts.Length -gt 1) { $parts[1] } else { '/' }
            $path = $rawPath.Split('?')[0]

            $status = '200 OK'
            if ($method -eq 'GET' -and $path -eq '/health') {
                $body = '{"ok":true,"service":"timegate-precision-clock","version":4}'
            }
            elseif ($method -eq 'GET' -and ($path -eq '/' -or $path -eq '/clock' -or $path -eq '/api/race/clock')) {
                $sendQpc = Qpc-Now
                $sendWallMs = Now-EpochMs
                $processingMs = Qpc-ToMs ($sendQpc - $receiveQpc)
                $payload = [ordered]@{
                    ok = $true
                    version = 4
                    source = 'timegate-race-server-powershell'
                    server_time_ms = [int64]$sendWallMs
                    server_receive_ms = [int64]$receiveWallMs
                    server_send_ms = [int64]$sendWallMs
                    server_receive_qpc = [int64]$receiveQpc
                    server_send_qpc = [int64]$sendQpc
                    qpc_frequency = [int64][System.Diagnostics.Stopwatch]::Frequency
                    processing_ms = [math]::Round($processingMs, 3)
                }
                $body = $payload | ConvertTo-Json -Compress
            }
            else {
                $status = '404 Not Found'
                $body = '{"ok":false,"error":"not_found"}'
            }

            $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
            $headers = @(
                "HTTP/1.1 $status",
                'Content-Type: application/json; charset=utf-8',
                'Cache-Control: no-store, no-cache, must-revalidate, max-age=0',
                'Pragma: no-cache',
                'Expires: 0',
                'Connection: close',
                "Content-Length: $($bodyBytes.Length)",
                'X-TimeGate-Clock: v4',
                '',
                ''
            ) -join "`r`n"

            $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
            $stream.Write($headerBytes, 0, $headerBytes.Length)
            $stream.Write($bodyBytes, 0, $bodyBytes.Length)
            $stream.Flush()
        }
        catch {
            Write-Warning $_.Exception.Message
        }
        finally {
            try { $client.Close() } catch {}
        }
    }
}
finally {
    $listener.Stop()
}
