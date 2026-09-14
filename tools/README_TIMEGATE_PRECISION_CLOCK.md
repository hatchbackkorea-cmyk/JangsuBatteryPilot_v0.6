# TimeGate Precision Clock

Camera Gate v0.34.99+ already probes the active RACE server at `/api/race/clock` before falling back to public NTP.

This folder contains a small local clock endpoint for the RCC Windows machine:

- `timegate_precision_clock_server.js` — localhost clock service on `127.0.0.1:8766`
- `START_TIMEGATE_CLOCK.cmd` — double-click launcher
- `start_timegate_precision_clock.ps1` — starts the service and adds only `/api/race/clock` to Tailscale Serve HTTPS 443
- `stop_timegate_precision_clock.ps1` — removes only that route and stops the process

## Start

On the machine that currently owns the active RCC Tailscale hostname, update this repository and double-click:

`tools\START_TIMEGATE_CLOCK.cmd`

The launcher does **not** reset Tailscale Serve. Existing RCC `/` routes are preserved; it only adds `/api/race/clock`.

Local check:

`http://127.0.0.1:8766/api/race/clock`

Tailnet check:

`https://<active-rcc-node>.ts.net/api/race/clock`

A healthy response contains `server_time_ms`, `server_receive_ms`, `server_send_ms`, and `processing_ms` with cache disabled.

## App behavior

The Android client takes 10 RACE clock samples, keeps the five lowest-RTT samples, and uses the median offset. If the endpoint is unavailable it automatically falls back to NTP, so enabling or disabling this service does not break Camera Gate.

For a local/Tailscale RCC path the expected uncertainty is primarily limited by half of the best network RTT plus sample spread. The app displays the measured value; no fixed accuracy is assumed.
