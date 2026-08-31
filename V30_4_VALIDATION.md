# v0.30.4 validation

- ROAD ability input: Strava ROAD analysis or FTP-only fallback.
- Strava filter is exact `sport_type == Ride`; MTB / Gravel / e-bike / VirtualRide excluded.
- OAuth requests `activity:read_all,activity:write`; granted scopes persisted.
- Power-duration PR windows: 5s / 30s / 1m / 5m / 20m / 60m.
- Outdoor Ride streams feed road grade/speed bins; trainer rides only feed power PR.
- Manual fallback uses one FTP field.
- Auto simulation speed remains target ~50 seconds; no manual multiplier control.
- v0.30.3 FIX1 `${ok}개` interpolation remains fixed.
