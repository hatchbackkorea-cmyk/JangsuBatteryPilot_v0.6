param(
    [int]$Port = 8767
)

$ErrorActionPreference = 'Stop'

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
Write-Host "[TimeGate Clock] listening on http://127.0.0.1:$Port"

$EpochUtc = [DateTime]::SpecifyKind([DateTime]'1970-01-01 00:00:00', [DateTimeKind]::Utc)
function Now-EpochMs {
    return [int64][math]::Floor(([DateTime]::UtcNow - $script:EpochUtc).TotalMilliseconds)
}
function Qpc-Now { return [System.Diagnostics.Stopwatch]::GetTimestamp() }
function Qpc-ToMs([long]$deltaTicks) {
    return ($deltaTicks * 1000.0) / [System.Diagnostics.Stopwatch]::Frequency
}

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $client.NoDelay = $true
            $stream = $client.GetStream()
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII, $false, 4096, $true)

            $receiveQpc = Qpc-Now
            $receiveWallMs = Now-EpochMs

            $requestLine = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($requestLine)) { continue }
            while ($true) {
                $line = $reader.ReadLine()
                if ($null -eq $line -or $line.Length -eq 0) { break }
            }

            $parts = $requestLine.Split(' ')
            $method = if ($parts.Length -gt 0) { $parts[0] } else { '' }
            $rawPath = if ($parts.Length -gt 1) { $parts[1] } else { '/' }
            $path = $rawPath.Split('?')[0]

            if ($method -eq 'GET' -and $path -eq '/health') {
                $status = '200 OK'
                $body = '{"ok":true,"service":"timegate-precision-clock","version":5}'
            }
            elseif ($method -eq 'GET' -and ($path -eq '/' -or $path -eq '/clock' -or $path -eq '/api/race/clock')) {
                $sendQpc = Qpc-Now
                $sendWallMs = Now-EpochMs
                $processingMs = Qpc-ToMs ($sendQpc - $receiveQpc)
                $status = '200 OK'
                $body = ('{{"ok":true,"version":5,"source":"timegate-race-server-powershell","server_time_ms":{0},"server_receive_ms":{1},"server_send_ms":{2},"server_receive_qpc":{3},"server_send_qpc":{4},"qpc_frequency":{5},"processing_ms":{6}}}' -f 
                    [int64]$sendWallMs,
                    [int64]$receiveWallMs,
                    [int64]$sendWallMs,
                    [int64]$receiveQpc,
                    [int64]$sendQpc,
                    [int64][System.Diagnostics.Stopwatch]::Frequency,
                    ([math]::Round($processingMs, 3).ToString([Globalization.CultureInfo]::InvariantCulture)))
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
                'X-TimeGate-Clock: v5',
                '',
                ''
            ) -join "`r`n"

            $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
            $stream.Write($headerBytes, 0, $headerBytes.Length)
            $stream.Write($bodyBytes, 0, $bodyBytes.Length)
            $stream.Flush()
        }
        catch { Write-Warning $_.Exception.Message }
        finally { try { $client.Close() } catch {} }
    }
}
finally { $listener.Stop() }
