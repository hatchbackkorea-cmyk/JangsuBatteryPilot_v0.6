# Ride Copilot RACE · iOS scaffold

This directory starts the native iOS RACE implementation without forcing the Android app into a cross-platform UI framework.

- Native iOS location source: CoreLocation (`kCLLocationAccuracyBestForNavigation`, background updates)
- Shared semantics: `docs/RACE_PROTOCOL_V1.md`
- Same oriented gate interpolation and distance-time reference delta as Android
- Server/API, event code, GPX, participant identity, sector/finish payloads and broadcast display are shared with Android

The iOS app is intentionally not marked as device-tested yet. An Apple/Xcode signing project and real iPhone test pass are still required before distribution. The user plans to test iOS later when an iPhone is available.
